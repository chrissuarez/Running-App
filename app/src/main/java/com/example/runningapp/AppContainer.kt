package com.example.runningapp

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.archive.Archiver
import com.example.runningapp.archive.RunArchiveContents
import com.example.runningapp.archive.SafArchiveFolder
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.OpenMeteoWeatherClient
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.WeatherClient
import com.example.runningapp.export.FileProviderGpxFileStore
import com.example.runningapp.export.GpxFileStore
import com.example.runningapp.restore.PendingRestore
import com.example.runningapp.restore.migrationHrProfile
import com.example.runningapp.routes.RouteImporter
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    val gpxFileStore: GpxFileStore by lazy {
        FileProviderGpxFileStore(appContext)
    }

    /**
     * The one way a GPX file becomes a Route (#54), shared by the in-app picker and by another app's
     * "Open with" — see [RouteImporter] for why both go through one door.
     */
    val routeImporter: RouteImporter by lazy {
        RouteImporter(appContext.contentResolver, database.routeDao())
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.sessionDao(),
            sampleDao = database.sampleDao(),
            trackPointDao = database.trackPointDao(),
            intervalStatDao = database.runWalkIntervalStatDao(),
            achievementDao = database.achievementDao(),
            statedBestEffortDao = database.statedBestEffortDao(),
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
            settingsRepository = settingsRepository
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
        applicationScope.launch { sessionRepository.backfillMovingTime() }
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
        applicationScope.launch { sessionRepository.rescueInterruptedRuns(processStartedAtMillis) }
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
        applicationScope.launch { sessionRepository.seedRecordsFromHistory() }
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
        applicationScope.launch { sessionRepository.scoreMissedRecords() }
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
        applicationScope.launch { sessionRepository.backfillEffortScores() }
    }

    /**
     * Lives as long as the process, and deliberately never cancelled — the container itself is a
     * process-wide singleton, so there is no shorter lifetime to bind to. SupervisorJob so one
     * failed background pass cannot take the others down with it.
     */
    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    private val missedRecordsScored = AtomicBoolean(false)

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
