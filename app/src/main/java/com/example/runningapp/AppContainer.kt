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
import com.example.runningapp.map.MapboxOfflineMapStore
import com.example.runningapp.map.OfflineMapDownload
import com.example.runningapp.map.PhoneLocation
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
     * The offline map (#42), on the app's scope so a download outlives the Settings screen that
     * started it.
     */
    val offlineMap: OfflineMapDownload by lazy {
        OfflineMapDownload(
            scope = applicationScope,
            store = MapboxOfflineMapStore(appContext),
            whereAmI = PhoneLocation(appContext)::whereAmI,
            now = System::currentTimeMillis,
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
     * Starts every launch pass, once per process — see [LaunchPasses] for the order and why it is on
     * this container's scope (#477).
     */
    fun payLaunchPassesOnce() = launchPasses.payOnce()

    private val launchPasses: LaunchPasses by lazy {
        LaunchPasses(passes, launchPassesOver({ sessionRepository }, { routeShaping }, processStartedAtMillis))
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
     * Not `once`, unlike the launch passes: this is the answer to one sheet, and there is one sheet
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

    /**
     * When this process began, as far as anything here is concerned — the container is built once,
     * on the way to the first screen, before a Run of this process can exist. See
     * [launchPassesOver] for the rescue it is recorded for.
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
    //
    // A lambda rather than `sessionRepository::setStatedProfile`, so building the queue does not
    // reach through the lazy repository and open the database at container construction.
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
