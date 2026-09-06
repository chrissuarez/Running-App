package com.example.runningapp

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.archive.Archiver
import com.example.runningapp.archive.RunArchiveContents
import com.example.runningapp.archive.SafArchiveFolder
import com.example.runningapp.data.AfterRunWorker
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.OpenMeteoWeatherClient
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.WeatherClient
import com.example.runningapp.diagnostics.RunJournal
import com.example.runningapp.export.ExportFileStore
import com.example.runningapp.export.FileProviderExportFileStore
import com.example.runningapp.restore.PendingRestore
import com.example.runningapp.restore.migrationHrProfile
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.RouteShapeRow
import com.example.runningapp.data.asCourseShape
import com.example.runningapp.routes.CourseShape
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RouteShapeStore
import com.example.runningapp.routes.RouteShaping
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        // Public token only (#40) - kept out of git via local.properties, same pattern as
        // GEMINI_API_KEY. Logged rather than thrown: this init runs on every app launch, so a
        // missing token should disable the map card, not crash the whole app.
        val mapboxAccessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (mapboxAccessToken.isBlank()) {
            Log.w("AppContainer", "MAPBOX_ACCESS_TOKEN is missing - the live map card will not render")
        }
        MapboxOptions.accessToken = mapboxAccessToken
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val coachPrescriptionRepository: CoachPrescriptionRepository by lazy {
        CoachPrescriptionRepository(appContext)
    }

    /**
     * The Run Journal (#310) — what the phone will still be able to say about a lost Run tomorrow.
     *
     * Process-wide rather than the service's own, and deliberately: the file is appended to across
     * every service the process raises, and a journal rebuilt with each one would have two writers
     * on the same file the moment a service outlived its own teardown.
     */
    val runJournal: RunJournal by lazy {
        RunJournal(File(appContext.filesDir, RunJournal.DIRECTORY_NAME))
    }

    val database: AppDatabase by lazy {
        // Whichever settings arrived with a restored archive, for the migration below to band that
        // archive's runs against. Written by the preparation and read by the migration, and those
        // two run one after the other on the single thread that opens the database, so there is no
        // hand-off across threads to arrange.
        var restoredSettings: ArchivedSettings? = null
        AppDatabase.getDatabase(
            appContext,
            // Everything that has to be true of the database file before Room reads a byte of it.
            // Not run here, on the way to a screen, but on the thread that opens the file — see
            // PreparingOpenHelper for why (#121).
            prepare = {
                // The history about to be replaced is the history the seeding mark describes, so the
                // mark goes first (#50). Not left to the settings write below: a bare `.db` backup
                // brings no settings with it, and one written before the record book existed would
                // otherwise restore into an empty book that the seeding pass declines to fill, at
                // this launch and every one after. Cleared ahead of the swap so a kill cannot strand
                // it — a restore that then fails costs one re-measure of unchanged history and
                // arrives at the same book.
                if (PendingRestore.isArmed(appContext)) {
                    runBlocking { settingsRepository.clearHistoryRecordsSeeded() }
                }
                // A restore the runner confirmed and the app relaunched for (#86). First, because it
                // is the one that was explicitly asked for — and once it has run the database
                // exists, which is the condition under which the automatic restore below correctly
                // stands down. Also clears away a pick that was never confirmed, which is otherwise
                // a whole spare database sitting in app storage with nothing to remove it.
                //
                // An archive's settings are written from here rather than at the moment the runner
                // confirmed, so that they only ever land beside the history they were saved with.
                PendingRestore.applyIfArmed(appContext) { archived ->
                    // Held before the write is attempted rather than after it, deliberately. The
                    // migration below has to band the restored runs against the profile they arrived
                    // with, and a DataStore write that fails leaves the restore armed to try again
                    // at the next launch — by which point the migration has already run and cannot
                    // be re-run. Reading the profile straight off the archive takes the migration out
                    // of that race entirely.
                    restoredSettings = archived
                    runBlocking { settingsRepository.restoreArchivedSettings(archived) }
                }
                // If this install has no database of its own yet — a freshly-cleared install — bring
                // run history back from the Downloads copy. No-ops (and never overwrites) when a
                // live database already exists, which includes reinstalls, where Auto Backup has
                // already restored it.
                DatabaseBackupManager.restoreIfDatabaseMissing(appContext)
            }
        ) {
            // The v12 -> v13 zone recompute needs the heart-rate profile, which lives in DataStore
            // rather than the database. Room only invokes this from inside the migration, on the
            // thread that opened the database, so the blocking read never lands on the main thread.
            //
            // Whichever settings belong to the history being opened, and always the pair history is
            // banded against rather than the live one — see [migrationHrProfile] for why the two
            // part company (#112, #172, #267).
            migrationHrProfile(
                restored = restoredSettings,
                phone = runBlocking { settingsRepository.userSettingsFlow.first() },
            )
        }
    }

    val aiCoachClient: AiCoachClient by lazy {
        AiCoachClient()
    }

    val weatherClient: WeatherClient by lazy {
        OpenMeteoWeatherClient()
    }

    val exportFileStore: ExportFileStore by lazy {
        FileProviderExportFileStore(appContext)
    }

    /**
     * The one way a GPX file becomes a Route (#54), shared by the in-app picker and by another app's
     * "Open with" — see [RouteImporter] for why both go through one door.
     */
    val routeImporter: RouteImporter by lazy {
        RouteImporter(appContext.contentResolver, database.routeDao())
    }

    /**
     * The phone changing zone, for every reader of Today to be woken by (#320) — see
     * [systemZoneChanges], which is where the rule and the reasoning live.
     *
     * One stream for the whole process, so there is one receiver however many screens are reading
     * it, and lazy so an app that never opens a reader never registers it.
     */
    val zoneChanges: SharedFlow<Unit> by lazy { systemZoneChanges(appContext, applicationScope) }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.sessionDao(),
            sampleDao = database.sampleDao(),
            trackPointDao = database.trackPointDao(),
            intervalStatDao = database.runWalkIntervalStatDao(),
            runPauseDao = database.runPauseDao(),
            achievementDao = database.achievementDao(),
            statedBestEffortDao = database.statedBestEffortDao(),
            // The runner's named places, and the times run at them (#70).
            segmentDao = database.segmentDao(),
            segmentEffortDao = database.segmentEffortDao(),
            // The shapes Runs recognise each other by (#73).
            runShapeDao = database.runShapeDao(),
            // Every Run's claim at every Record, banked beside the medals so the Records section
            // can show a top ten and a trend (#75).
            runEffortDao = database.runEffortDao(),
            // Whether that banking is part-way through being rebuilt over the whole of history
            // (#75) — raised by the migration that created the table, handed back by the pass that
            // fills it, and read by the Records section so it never quotes an all-time best off a
            // slice.
            recordFillDao = database.recordFillDao(),
            // Read for one thing only: telling the coach where the runner stands against their own
            // targets (#83). Without it the coach is simply told nothing about goals.
            goalDao = database.goalDao(),
            // Where a Run's AI summary is kept once it has been written (#76). Without it a Run's
            // page simply never offers one.
            runSummaryDao = database.runSummaryDao(),
            // Where a Run whose settlement could not write its Walk mark is written down, so the
            // next launch puts the mark back (#371). Without it such a Run says "run" for ever.
            walkMarkDebtDao = database.walkMarkDebtDao(),
            // Where a launch pass that still owes the whole of history a re-measuring is written
            // down, so a Run Summary is not written out of numbers that are about to change (#349).
            historyDebtDao = database.historyDebtDao(),
            // The runner's courses, read only so a live Run's map can draw the one it set out to
            // follow (#56).
            routeDao = database.routeDao(),
            settingsRepository = settingsRepository,
            coachPrescriptionRepository = coachPrescriptionRepository,
            aiCoachClient = aiCoachClient,
            weatherClient = weatherClient,
            // After a delete, re-snapshot history to Downloads so a later Clear-storage restore
            // can't bring the deleted runs back. File IO, so keep it off the caller's (main) thread.
            refreshHistoryBackup = {
                withContext(Dispatchers.IO) {
                    DatabaseBackupManager.backup(appContext, database)
                }
            },
            // The durable version of the line above, for the rescue that finishes a Run whose
            // service was torn down (#309): the process may not outlive the snapshot, so the
            // request goes into WorkManager's database and the copy happens whether this process
            // lives or not. Blocks until that write is done, and is only ever called from IO.
            bookAfterRunWork = { runRowId -> AfterRunWorker.enqueue(appContext, runRowId) },
            // A re-tally of history is all of it or none: see SessionRepository.inTransaction.
            inTransaction = { block -> database.withTransaction { block() } }
        )
    }

    /**
     * Everything the archive is made of, and the folder it goes to (#85).
     *
     * One archiver for both ways of asking — the "Back up now" button and the monthly job — so the
     * unattended backup is the same archive as the deliberate one, built by the same code.
     *
     * The folder is read fresh on every backup rather than captured here: the runner can change it
     * at any time, and a monthly job holding the folder they picked a year ago would keep writing
     * somewhere they had moved on from.
     */
    val archiver: Archiver by lazy {
        val contents = RunArchiveContents(
            context = appContext,
            database = database,
            sessionDao = database.sessionDao(),
            intervalStatDao = database.runWalkIntervalStatDao(),
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            runJournal = runJournal
        )
        Archiver(
            folder = {
                // Through the grant check, so a folder restored onto a new phone without the
                // permission behind it reads as no folder rather than as one that always fails.
                SafArchiveFolder
                    .grantedFolder(appContext, settingsRepository.userSettingsFlow.first().backupFolderUri)
                    ?.let { SafArchiveFolder(appContext, it) }
            },
            contents = { at -> contents.entries(at) },
            onArchived = { at -> settingsRepository.setLastBackupAt(at) },
            now = { System.currentTimeMillis() }
        )
    }

    /**
     * Measures moving time for runs recorded before #163, once per process.
     *
     * On the container's own scope rather than an Activity's: the latch below is process-wide, so a
     * backfill tied to an Activity that the user backs out of mid-pass would be cancelled with the
     * work half done and never start again for the life of the process — leaving some runs quoting
     * pace over one clock and their neighbours over another.
     */
    fun backfillMovingTimeOnce() {
        if (!movingTimeBackfilled.compareAndSet(false, true)) return
        passes.launch("moving-time backfill") { sessionRepository.backfillMovingTime() }
    }

    /**
     * Finishes any Run a previous process left interrupted, once per process (#192).
     *
     * [processStartedAtMillis] is read at construction rather than at the moment the pass runs, and
     * that is what makes the pass safe: it draws the line before this process can have started a Run
     * of its own, so nothing it finds can be a Run being recorded now. Reading the clock inside the
     * pass would move the line to after a runner could have pressed START.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above.
     */
    fun rescueInterruptedRunsOnce() {
        if (!interruptedRunsRescued.compareAndSet(false, true)) return
        passes.launch("interrupted-run rescue") { sessionRepository.rescueInterruptedRuns(processStartedAtMillis) }
    }

    /**
     * Puts the history already recorded to the record book, once per process (#50).
     *
     * After the rescue pass rather than before it, so a Run a previous process left interrupted is
     * finished — and therefore eligible — before history is measured. Ordering is a preference, not
     * a requirement: a rescued Run scores itself, and this pass carries over the rows of any Run it
     * did not see, so either order leaves the same book.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above.
     */
    fun seedRecordsFromHistoryOnce() {
        if (!recordsSeeded.compareAndSet(false, true)) return
        passes.launch("record seeding") { sessionRepository.seedRecordsFromHistory() }
    }

    /**
     * Scores any Run the record book never measured, once per process (#210).
     *
     * Started after the rescue pass and the seeding pass, though nothing makes them run in that
     * order: all three are launched on the same scope and none waits for the others. Nothing needs
     * the order. A Run this pass ran past while it was still interrupted is finished by the rescue
     * and is on the next launch's list; and while history is still owed its seeding this pass
     * declines outright, because that pass measures every Run at once and marks them itself.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above. It
     * matters here as it does for the Effort backfill: a Run scored is a Run marked, so a pass
     * cancelled because the runner backed out of an Activity keeps everything it paid for, but
     * would not be resumed for the life of the process.
     */
    fun scoreMissedRecordsOnce() {
        if (!missedRecordsScored.compareAndSet(false, true)) return
        passes.launch("missed-record scoring") { sessionRepository.scoreMissedRecords() }
    }

    /**
     * Puts any Run the finish never settled to the Plan, once per process (#297).
     *
     * After the rescue pass in the list above and not waiting on it, as none of these do. Ordering
     * costs nothing here either: a Run this pass ran past while it was still interrupted has no end
     * time, so the settlement declines it and leaves its debt for the launch after the rescue
     * finishes it.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above. It
     * matters as much here as anywhere: a Stage settled is a Stage marked, so a pass cancelled
     * because the runner backed out of an Activity keeps every settlement it made, but would not be
     * resumed for the life of the process.
     */
    fun settleMissedStagesOnce() {
        if (!missedStagesSettled.compareAndSet(false, true)) return
        passes.launch("Stage settlement") { sessionRepository.settleStagesMissedAtTheFinish() }
    }

    /**
     * Puts back any Walk mark a settlement judged on but could not write, once per process (#371).
     *
     * After the settling pass in the list above and not waiting on it, as none of these do — and the
     * order genuinely does not matter here either. A debt this pass ran past is one the settling
     * pass raised a moment later, and it is still in the table at the next launch; a debt raised
     * before this pass reads is paid now. Nothing is lost by either order because the debt is
     * durable, which is the whole reason it is stored.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above. It
     * matters here as it does everywhere in this list: each mark discharges its own debt as it
     * lands, so a pass cancelled because the runner backed out of an Activity keeps every mark it
     * put back, but would not be resumed for the life of the process.
     */
    fun payWalkMarkDebtsOnce() {
        if (!walkMarkDebtsPaid.compareAndSet(false, true)) return
        passes.launch("Walk-mark debt") { sessionRepository.payWalkMarkDebts() }
    }

    /**
     * Takes back coaching left standing on Runs that are no longer in history, once per process
     * (#270).
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above, and
     * more sharply here: the delete this finishes was cut short by a process being reclaimed, and a
     * pass tied to the screen the runner deletes from would be the same kind of half-finished work
     * one lifetime further in.
     *
     * Nothing orders this against the passes around it. None of them takes a Run out of history,
     * which is the only thing this reads.
     */
    fun reconcileCoachingOnce() {
        if (!coachingReconciled.compareAndSet(false, true)) return
        passes.launch("coaching reconciliation") { sessionRepository.reconcileCoachingWithHistory() }
    }

    /**
     * Scores the history recorded before the Effort Score shipped, once per process (#62).
     *
     * Started after the rescue pass, though nothing makes them run in that order: both are launched
     * on the same scope and neither waits for the other. Nothing needs the order — a rescued Run is
     * scored as it is finished, and one this pass ran past while it was still interrupted is on the
     * next launch's list. What does keep the two from colliding is the lock they share
     * ([SessionRepository.backfillEffortScores]), not the order they are started in.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above.
     * That matters more here than anywhere: the pass is resumable, but a pass cancelled because the
     * runner backed out of an Activity would not be *resumed* for the life of the process, leaving
     * the trends built on these Scores reading half a history.
     */
    fun backfillEffortScoresOnce() {
        if (!effortScored.compareAndSet(false, true)) return
        passes.launch("Effort Score backfill") { sessionRepository.backfillEffortScores() }
    }

    /**
     * Fills in the weather for the Runs in history that have none, once per process (#81).
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above, and
     * this is the pass that most needed moving there. It shipped with #79 on a `LaunchedEffect` in
     * the Activity's composition, which is precisely the lifetime that cannot hold it: the pass is
     * minutes of fetching over a whole history, and the runner backing out of the screen cancelled
     * it with the work part done and started nothing again for the life of the process. The ticket
     * asks that killing the app mid-backfill and relaunching finishes the job, and that is only true
     * of a pass no screen owns.
     *
     * Nothing orders this against the passes around it. It writes five columns nothing else reads
     * and reads none that anything else writes.
     */
    fun backfillWeatherOnce() {
        if (!weatherBackfilled.compareAndSet(false, true)) return
        passes.launch("weather backfill") { sessionRepository.backfillWeather() }
    }

    /**
     * Puts a newly cut Segment to every Run in history, so it arrives with its efforts and its PR
     * already on it (#70).
     *
     * On the container's own scope for the reason the screen makes unavoidable: saving a Segment is
     * the last thing the creation screen does before it is popped, so a scan launched from the
     * screen — or from the ViewModel scoped to the Activity the runner then backs out of — would be
     * cancelled by the very navigation that follows it, and the new Segment would sit there claiming
     * the runner had never run it.
     *
     * Not `once`, unlike the launch passes: this is one Segment being born, and a runner can cut
     * several.
     */
    fun timeSegmentAgainstHistory(segmentId: Long) {
        passes.launch("Segment timing for segment $segmentId") {
            sessionRepository.timeSegmentAgainstHistory(segmentId)
        }
    }

    /**
     * Pays whatever the Segments and the Runs owe each other, once per process (#70).
     *
     * Two debts, and between them they are the whole reason a Segment can be trusted to know its own
     * history: a Segment cut before efforts existed has never been walked against anything, and
     * either side of a walk can be lost to a process being reclaimed half way through it. On an
     * ordinary launch this reads two empty lists and returns.
     *
     * After the rescue pass in the list above and not waiting on it, as none of these do. A Run this
     * pass ran past while it was still interrupted has no end time, so it is not on the list; the
     * rescue finishes it and walks it against the Segments itself.
     *
     * On the container's own scope, for the same reason the moving-time backfill is — see above. It
     * matters here as it does for the Effort backfill: each side is marked as it is paid, so a pass
     * cancelled because the runner backed out of an Activity keeps everything it paid for, but would
     * not be resumed for the life of the process.
     */
    fun paySegmentTimingOnce() {
        if (!segmentTimingPaid.compareAndSet(false, true)) return
        passes.launch("Segment-timing debt") { sessionRepository.payWhatSegmentTimingOwes() }
    }

    /**
     * Takes the shape of every Run that has never had one, once per process (#73).
     *
     * On the first launch after this shipped that is the whole of the runner's history, which is the
     * backfill the matching is worth having at all: a runner who has been round the same park for a
     * year should be told so on the day it arrives, not a year later. Every launch afterwards reads
     * an empty list and returns.
     *
     * On the container's own scope, for the reason the passes above are: each Run's row is written
     * as it is measured, so a pass cancelled by the runner backing out of an Activity keeps
     * everything it has already done and the next launch takes up the rest.
     */
    fun takeRunShapesOnce() {
        if (!runShapesTaken.compareAndSet(false, true)) return
        passes.launch("Run-shape debt") { sessionRepository.payWhatRunShapesOwe() }
    }

    /**
     * Takes the shape of every saved course that has never had one, once per process (#74).
     *
     * The library's half of [takeRunShapesOnce], and it exists for the same reason: on the first
     * launch after this shipped that is every course the runner keeps, and a library shaped only from
     * now on would leave each of those courses opening on the empty page this ticket exists to fill.
     * Every launch afterwards reads an empty list and returns — a course kept since is shaped in the
     * transaction that kept it ([com.example.runningapp.data.RouteDao.keepRoute]).
     *
     * Cheap beside the Run pass. A course's shape comes off a line already stored rather than off a
     * whole track of fixes, and a library is a handful of rows where history is thousands.
     *
     * On the container's own scope, for the reason the passes above are: each course's row is written
     * as it is measured, so a pass cancelled by the runner backing out of an Activity keeps
     * everything it has already done and the next launch takes up the rest.
     */
    fun takeRouteShapesOnce() {
        if (!routeShapesTaken.compareAndSet(false, true)) return
        passes.launch("Route-shape debt") { routeShaping.payWhatIsOwed() }
    }

    /**
     * Every saved course a Run could be recognised on, watched (#74) — the library as the matching
     * asks about it.
     *
     * Mapped here rather than at each reader so the one place a stored row becomes a
     * [CourseShape] is the one place that decides what an unreadable row means: it is dropped, and
     * the course claims no Runs until it is measured again ([RouteShapeCandidate.asCourseShape]).
     *
     * Never the lines themselves — that is what the shapes table is for
     * ([com.example.runningapp.data.Route.polyline]).
     */
    val savedCourseShapes: Flow<List<CourseShape>> by lazy {
        database.routeShapeDao().getShapedCoursesFlow()
            .map { rows -> rows.mapNotNull { it.asCourseShape() } }
    }

    /** The one taking of course shapes, over this container's own DAOs (#74). */
    private val routeShaping: RouteShaping by lazy {
        val routes = database.routeDao()
        val shapes = database.routeShapeDao()
        RouteShaping(object : RouteShapeStore {
            override suspend fun coursesMissingShapes() = shapes.getRouteIdsMissingShapes()

            // One line, fetched to be measured and let go before the next is asked for — the first
            // rule about this column ([com.example.runningapp.data.Route.polyline]).
            override suspend fun line(routeId: Long) = routes.getRoutePolyline(routeId)

            override suspend fun putShape(row: RouteShapeRow) = shapes.putShape(row)
        })
    }

    /**
     * Stores the runner's answer to a Run's finish sheet and closes the gate behind it, off any
     * screen's lifetime (#297).
     *
     * [markedAsWalk] travels beside [writes] rather than inside it because it is the word the
     * settlement reads — see [SessionRepository.finishSheetAnswered]. Null is a dismissal.
     *
     * On the container's own scope because the sheet's exit *removes the sheet* — the composition
     * that raised it is gone by the time the writes land, so a launch on its scope is cancelled by
     * the runner leaving the app, or by the Activity being destroyed the instant after Save. That
     * cancellation would leave the gate naming this Run: the finish has already declined it, the
     * launch pass has already run for this process, and nothing else would settle the Run until the
     * process was killed.
     *
     * Not `once`, unlike everything above: this is the answer to one sheet, and there is one sheet
     * per Run.
     */
    fun answerFinishSheet(sessionId: Long, markedAsWalk: Boolean?, writes: suspend () -> Unit) {
        passes.launch("finish sheet for run $sessionId") {
            sessionRepository.finishSheetAnswered(sessionId, markedAsWalk, writes = writes)
        }
    }

    /**
     * Lives as long as the process, and deliberately never cancelled — the container itself is a
     * process-wide singleton, so there is no shorter lifetime to bind to. SupervisorJob so one
     * failed background pass cannot take the others down with it.
     *
     * **A SupervisorJob keeps a failure from the siblings; it does not handle it** (#375). Anything
     * escaping a bare `launch` here reaches the default uncaught handler and kills the app, so no
     * background pass is started on this scope directly — they all go through [passes], which names
     * the pass and states the rule once. What is left on it is work with a reader that owns its own
     * failures: a flow this scope keeps hot, and the stated-heart-rate queue below.
     */
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Every deferrable background pass, started and guarded in one place — see [BackgroundPasses]
     * for what "deferrable" buys and what is still allowed to be fatal (#375).
     */
    private val passes = BackgroundPasses(applicationScope)

    /**
     * States a heart rate, or both at once. Ordered — see [StatedHeartRateQueue].
     */
    fun stateHeartRates(maxHr: Int?, restingHr: Int?) = statedHeartRates.state(maxHr, restingHr)

    // A lambda rather than `sessionRepository::setStatedProfile`, so building the queue does not
    // reach through the lazy repository and open the database at container construction.
    private val movingTimeBackfilled = AtomicBoolean(false)
    private val interruptedRunsRescued = AtomicBoolean(false)
    private val recordsSeeded = AtomicBoolean(false)
    private val effortScored = AtomicBoolean(false)
    private val weatherBackfilled = AtomicBoolean(false)
    private val missedRecordsScored = AtomicBoolean(false)
    private val missedStagesSettled = AtomicBoolean(false)
    private val walkMarkDebtsPaid = AtomicBoolean(false)
    private val coachingReconciled = AtomicBoolean(false)
    private val segmentTimingPaid = AtomicBoolean(false)
    private val runShapesTaken = AtomicBoolean(false)
    private val routeShapesTaken = AtomicBoolean(false)

    /**
     * When this process began, as far as anything here is concerned — the container is built once,
     * on the way to the first screen, before a Run of this process can exist. See
     * [rescueInterruptedRunsOnce], which is the whole reason it is recorded.
     */
    private val processStartedAtMillis = System.currentTimeMillis()

    // Anything a previous process left interrupted is finished before this queue takes its first
    // statement — history and the profile live in different stores, so a statement that dies
    // between them needs finishing rather than forgetting (#172).
    //
    // Last of the properties on purpose: building it starts the consumer, which reads the
    // interrupted note straight away and so reaches through `sessionRepository` — and that must
    // not happen while this constructor is still running. The read is on [applicationScope]
    // (Dispatchers.IO), so opening the database here never lands on the main thread even though
    // `runningAppContainer()` is called from `onCreate`.
    private val statedHeartRates = StatedHeartRateQueue(
        scope = applicationScope,
        recover = { sessionRepository.interruptedStatement() }
    ) { maxHr, restingHr ->
        sessionRepository.setStatedProfile(maxHr, restingHr)
    }

}

private var appContainerInstance: AppContainer? = null

fun Context.runningAppContainer(): AppContainer {
    return appContainerInstance ?: synchronized(AppContainer::class.java) {
        appContainerInstance ?: AppContainer(applicationContext).also { appContainerInstance = it }
    }
}
