package com.example.runningapp

import android.content.Context
import android.util.Log
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.OpenMeteoWeatherClient
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.WeatherClient
import com.example.runningapp.export.FileProviderGpxFileStore
import com.example.runningapp.export.GpxFileStore
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
        // If this install has no database of its own yet — a freshly-cleared install — bring run
        // history back from the Downloads copy before Room opens. No-ops (and never overwrites) when
        // a live database already exists, which includes reinstalls, where Auto Backup has already
        // restored it.
        DatabaseBackupManager.restoreIfDatabaseMissing(appContext)
        // The v12 -> v13 zone recompute needs the heart-rate profile, which lives in DataStore
        // rather than the database. Room only invokes this from inside the migration, on its own
        // background thread, so the blocking read never lands on the main thread.
        AppDatabase.getDatabase(appContext) {
            runBlocking { settingsRepository.userSettingsFlow.first().hrProfile }
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

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.sessionDao(),
            sampleDao = database.sampleDao(),
            runWalkIntervalStatDao = database.runWalkIntervalStatDao(),
            trackPointDao = database.trackPointDao(),
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
            }
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
    private val statedHeartRates = StatedHeartRateQueue(applicationScope) { maxHr, restingHr ->
        sessionRepository.setStatedProfile(maxHr, restingHr)
    }

    private val movingTimeBackfilled = AtomicBoolean(false)
}

private var appContainerInstance: AppContainer? = null

fun Context.runningAppContainer(): AppContainer {
    return appContainerInstance ?: synchronized(AppContainer::class.java) {
        appContainerInstance ?: AppContainer(applicationContext).also { appContainerInstance = it }
    }
}
