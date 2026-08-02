package com.example.runningapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.app.PendingIntent
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.UUID
import com.example.runningapp.run.Acquisition
import com.example.runningapp.run.AcquisitionContext
import com.example.runningapp.run.AcquisitionEffect
import com.example.runningapp.run.AcquisitionEvent
import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.AcquisitionState
import com.example.runningapp.run.CueTag
import com.example.runningapp.run.ScannedStrap
import java.util.concurrent.ConcurrentHashMap
import com.example.runningapp.run.IntervalKind
import com.example.runningapp.run.Run
import com.example.runningapp.run.RunConfig
import com.example.runningapp.run.RunControls
import com.example.runningapp.run.RunEffect
import com.example.runningapp.run.RunEvent
import com.example.runningapp.run.RunLifecycle
import com.example.runningapp.run.RunMode
import com.example.runningapp.run.RunPhase
import com.example.runningapp.run.RunState
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.data.averagePaceMinPerKm
import com.example.runningapp.foreground.ForegroundPromotion
import com.example.runningapp.foreground.PromotionHost

// Exactly the Run's lifecycle, under the screen's older names — [RunLifecycle.asSessionStatus] is
// the only thing that writes it, so there is no value here a Run cannot be in.
enum class SessionStatus { IDLE, RUNNING, PAUSED, STOPPING, STOPPED }
enum class SessionPhase { WARM_UP, MAIN, COOL_DOWN }
enum class StructuredWorkoutPhase { RUN, WALK }

// simple data class to hold the state
// How far past each end of the zone range the simulated sweep goes before turning back. The
// sweep exists to drive the cues, and the cues at both extremes only fire from outside the band —
// so it has to leave it, not merely touch it.
private const val SIMULATION_OVERSHOOT_BPM = 10

data class HrState(
    /**
     * Where the Acquisition has got to (ADR 0007). It used to be a sentence, and eleven places read
     * the sentence — including Promotion, which decided a wake lock by searching it for four words.
     */
    val acquisition: AcquisitionState = AcquisitionState(),
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val bpm: Int = 0,

    val avgBpm: Int = 0,
    // The live screen's fallback coach line: how long until the coach next has something to say.
    val coachWaitingLine: String = "Ready",

    val secondsRunning: Long = 0,
    val lastHrAgeSeconds: Long = 0,

    val isSimulating: Boolean = false,

    val currentPhase: SessionPhase = SessionPhase.WARM_UP,
    val phaseSecondsRemaining: Int = 0,
    val phaseSecondsElapsed: Long = 0,
    val isStructuredWorkout: Boolean = false,
    val structuredWorkoutPhase: StructuredWorkoutPhase = StructuredWorkoutPhase.RUN,
    val phaseTimeRemainingSeconds: Int = 0,
    val currentRepeat: Int = 1,
    val totalRepeats: Int = 0,
    val currentIntervalPlannedSeconds: Int = 0,
    val nextIntervalType: StructuredWorkoutPhase? = null,
    val nextIntervalDurationSeconds: Int = 0,
    val workoutProgressPercent: Int = 0,
    val currentIntervalElapsedSeconds: Int = 0,
    /** The run Interval's Trigger: the second heart rate first went above target, if it did. */
    val triggerAtSecond: Int? = null,
    
    // Mission 4: Outdoor Running
    val distanceKm: Double = 0.0,
    val paceMinPerKm: Double = 0.0,

    // Mission 2: Settings Summary
    val userSettings: UserSettings = UserSettings(),

    // The target frozen for the active run (null when idle). The live screen prefers this over
    // userSettings.targetHrZone so its zone label and band match what the coach is actually
    // coaching, even if the global target is changed mid-run.
    val activeTargetZone: HrZone? = null,

    val walkBreaksCount: Int = 0,

    // Post-run "How did that feel?" sheet: DB row id for the session the UI should prompt about
    val activeDbSessionId: Long? = null,

    // The live run's pinned mode ("outdoor"/"treadmill"), published with RUNNING and cleared at
    // stop. Active-run UI must gate distance/map on this, not userSettings.runMode: the settings
    // write from a just-tapped mode toggle is async, so an outdoor run started immediately after
    // the tap would otherwise render as a treadmill run while GPS records underneath.
    val activeRunMode: String? = null,

    // The heart rates the live run's zones are sliced from, pinned at START with everything else
    // in RunConfig (ADR 0002). The screen must prefer this over userSettings.hrProfile for the
    // same reason it prefers activeTargetZone: a resting heart rate stated mid-run moves every
    // zone edge in settings immediately, while the Run keeps coaching and tallying against the
    // pair it started with — so the screen would name a zone the runner is not being coached to,
    // and a total that will not match what gets recorded.
    val activeHrProfile: HrProfile? = null
) {
    /**
     * Is an Acquisition in flight — scanning, connecting, or retrying a Strap?
     *
     * Asked of the phase rather than of its sentence. The three things that need it — the START
     * guard, the record screen's spinner, and Promotion — had already drifted apart once when this
     * was spelled by searching text: the service's copy omitted "Scanning".
     */
    val acquiringStrap: Boolean get() = acquisition.inFlight

    /** What the runner is told, and what a heart-rate row records. See [AcquisitionState]. */
    val connectionStatus: String get() = acquisition.statusLine

    /** Straps this scan has turned up. They outlive the scan, to still be tappable after it ends. */
    val scannedDevices: List<ScannedStrap> get() = acquisition.scanned
}

class HrForegroundService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Detached from serviceScope on purpose: the save-time weather fetch must survive
    // onDestroy() cancelling serviceScope when a run is stopped from the background.
    private val weatherFetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The run's per-sample writes (HR samples, GPS track points) live on their own scope so
    // stopRun() can wait for exactly these inserts to land before snapshotting the DB to
    // Downloads — otherwise the backup can race the tail writes and capture a run missing its
    // final seconds. Like weatherFetchScope, it survives onDestroy() so a background stop still
    // flushes the tail before the snapshot.
    private val recorderWriteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Letting a GATT go outlives the service too. The handle leaves [openGatts] the moment the
    // Acquisition is done with it, and the state that says so publishes before the close runs — so
    // a terminal phase can reach stopSelf() and onDestroy() in between. On serviceScope that
    // cancels the close, and onDestroy's own sweep can no longer see the handle to finish the job.
    private val gattCloseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Exposed state for UI
    private val _hrState = MutableStateFlow(HrState())
    val hrState: StateFlow<HrState> = _hrState.asStateFlow()

    /**
     * Every GATT this service has opened and not yet closed, by address.
     *
     * A map rather than a field because [Acquisition] speaks only in addresses, and a GATT it has
     * abandoned still has to be closable when its callbacks turn up later. Written on
     * [sessionHandlerThread] with one exception that cannot be moved there — onDestroy's sweep,
     * which runs on main precisely because the session thread is being stopped — so the map is
     * concurrent rather than plain.
     */
    private val openGatts = ConcurrentHashMap<String, BluetoothGatt>()

    /**
     * Set once, on main, before onDestroy sweeps [openGatts]. Read on the session thread by a
     * connect that finished too late to be swept, so it can let its own handle go.
     */
    @Volatile
    private var destroyed = false
    private var bluetoothAdapter: BluetoothAdapter? = null

    // UUIDs for Heart Rate Service and Measurement Characteristic
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * The Acquisition's whole state (ADR 0007).
     *
     * Touched ONLY on [sessionHandlerThread], exactly like [runState] — which is what let the scan
     * epoch, the connect sequence counter and the connect lock all go. Every path that used to
     * write a piece of this from whichever thread it happened to be on now posts an event instead.
     */
    private var acquisitionState = AcquisitionState()
    private var isActivityBound = false
    
    // TTS & Audio Focus
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var audioCueManager: AudioCueManager? = null

    /**
     * The queue tickets for cues the Run may still want back, by the name it knows them under
     * (#53). Written from the thread performing the Run's effects and read from there and from the
     * STOP path, which is the binder's, so it is a map that stands two of them.
     */
    private val outstandingCues = ConcurrentHashMap<CueTag, Long>()

    // Mission 4: Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationTracker: LocationTracker? = null
    // The run the last stored track point belonged to — how a resume is told from a run's first fix.
    private var lastTrackedSessionId: Long? = null
    private var lastNotificationZone: HrZone? = null
    private var lastNotificationPhase = SessionPhase.WARM_UP
    private var lastNotificationStatus = SessionStatus.IDLE

    // --- The Run ---
    //
    // The Run itself is a rulebook (ADR 0002): events in, whole state and a list of effects out.
    // What lives here is only the translation — this field, the thread that owns it, the one write
    // that publishes it, and a mapping from each effect to one Android call.
    //
    // Touched ONLY on [sessionHandlerThread]. That is the whole of the thread discipline: not a
    // lock, not a @Volatile, but the fact that Bluetooth callbacks, GPS callbacks, taps and the
    // per-second pulse all reach it through [postRunEvent] and never directly.
    private var runState = RunState.IDLE

    // Mission: Resilient Tracking Loop — now the Run's single inbox, kept off main so a busy UI
    // cannot stall the Run's clock.
    private var sessionHandlerThread: HandlerThread? = null
    private var sessionHandler: Handler? = null
    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            // Simulation feeds the Run ordinary heart-rate events, so it and a real Strap drive
            // identical code — there is no simulated branch anywhere inside the Run.
            // Simulation feeds a Run, so it only has anything to say while one is live — the pulse
            // now also runs before a Run, for the Acquisition's sake.
            if (isSimulationEnabled && runState.lifecycle.isLive) updateSimulationData()
            dispatchRunEvent(RunEvent.Tick(System.currentTimeMillis()))
            // The Acquisition holds its own deadlines — when a scan gives up, when a retry is due —
            // and this is how it learns the time (ADR 0007). A Run that is not live ignores its own
            // tick, so this costs nothing when only the Acquisition needs it.
            dispatchAcquisitionEvent(AcquisitionEvent.Tick)
            val lifecycle = runState.lifecycle
            // The pulse runs while the app is promoted, which is ADR 0001's own rule: a live Run or
            // an in-flight Acquisition. A Run that is over stops it, unless a Strap is still being
            // chased — and STOP drops the pending tick too, so this is the belt to that braces.
            val runNeedsIt = lifecycle != RunLifecycle.IDLE && lifecycle != RunLifecycle.STOPPED
            if (runNeedsIt || acquisitionState.inFlight) {
                sessionHandler?.postDelayed(this, 1000)
            } else {
                Log.d(TAG, "Timer loop exiting - lifecycle is $lifecycle, no acquisition in flight")
            }
        }
    }

    private lateinit var settingsRepository: SettingsRepository
    // What the coach has standing, one slot per Run Type (#175) — today's workout takes the slot
    // of its own kind and no other. Read once at START, like the workout it adapts: a prescription
    // arriving mid-run must not reshape a run in progress.
    @Volatile private var currentPrescriptions: CoachPrescriptions = CoachPrescriptions.NONE
    private lateinit var sessionRepository: SessionRepository
    private var currentSettings = UserSettings()
    // Skip today's plan (#107): a per-run, today-only choice from the record screen. When set, the
    // run attaches no workout — an open-ended run with no warm-up/cool-down/intervals. It never
    // edits the plan, so tomorrow the plan is queued again.
    @Volatile private var skipPlanForToday: Boolean = false

    // Which of the stage's Workouts the runner picked as today's Run (#174). Held only for as long
    // as the process is up and never written down: there is no position-in-week to keep, so a pick
    // that outlives the tap it came from would be the app inventing one. Null means the stage's
    // first, exactly as it was before the runner could pick at all.
    @Volatile private var pickedWorkoutId: String? = null

    private lateinit var database: AppDatabase

    // Both written on main (or a Binder thread) and read on the session thread, which is the Run's
    // inbox: the pulse asks whether to feed the Run a simulated reading, and the published state
    // reports how stale the Strap's last packet is.
    @Volatile private var isSimulationEnabled = false

    private var simulationBpm = 70
    private var simulationDirection = 1

    @Volatile private var lastHrTimestamp = 0L

    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        // The explicit act of beginning a run (#110). Distinct from ACTION_START_FOREGROUND,
        // which now only acquires the strap as a sensor: connecting is no longer starting.
        const val ACTION_START_RUN = "ACTION_START_RUN"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
        const val ACTION_PAUSE_SESSION = "ACTION_PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "ACTION_RESUME_SESSION"
        const val ACTION_FORCE_SCAN = "ACTION_FORCE_SCAN"
        const val ACTION_SET_SIMULATION = "ACTION_SET_SIMULATION"
        // How long onDestroy waits for the session thread's current message to finish before it
        // sweeps the GATTs that message might be touching. Bounded because onDestroy runs on main
        // and an ANR is worse than a handle closed a beat late.
        private const val SESSION_THREAD_JOIN_TIMEOUT_MS = 500L
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        // Set only by explicit Connect taps: marks the connect as a user choice whose strap may
        // be promoted to active on verification. Background auto-connects omit it.
        const val EXTRA_MAKE_ACTIVE = "EXTRA_MAKE_ACTIVE"
        const val EXTRA_SKIP_PLAN = "SKIP_PLAN"
        // START carries the mode the user has selected right now, so a Treadmill/Outdoor switch made
        // just before tapping START is honoured even if its async settings write hasn't landed yet.
        const val EXTRA_RUN_MODE = "EXTRA_RUN_MODE"
        // Which of the stage's Workouts today's Run is (#174). Carried on the same intents as the
        // skip choice, and for the same reason: both are made on the record screen and both have to
        // be in force before the run's configuration is pinned.
        const val EXTRA_WORKOUT_ID = "EXTRA_WORKOUT_ID"
        const val EXTRA_SIMULATION_ENABLED = "SIMULATION_ENABLED"
        const val TAG = "HrService"
    }

    private fun logBleDecision(reason: String, detail: String) {
        Log.d(TAG, "BLE decision: $reason | $detail")
    }

    private fun startHardwareSession(overrideAddress: String?, promoteOnVerify: Boolean = false) {
        if (overrideAddress != null) {
            logBleDecision("direct_connect", "Using override device address=$overrideAddress")
            connectToDevice(overrideAddress, promoteOnVerify)
            return
        }

        val savedAddress = currentSettings.activeDeviceAddress
        if (savedAddress != null) {
            // Reconnecting the already-active strap: verification promotes it anyway via the
            // activeDeviceAddress == deviceAddress term, no explicit promotion needed.
            logBleDecision("saved_device_reconnect", "Using saved activeDeviceAddress=$savedAddress")
            connectToDevice(savedAddress, promoteToActive = false)
        } else {
            logBleDecision("fresh_scan", "No saved device available; starting BLE scan")
            startScanning()
        }
    }

    // ------------------------------------------------------------------------------------------
    // The Run: events in, one published state out, effects performed.
    //
    // This is the whole of what the service knows about a Run. It decides nothing — [Run] does —
    // and it holds nothing the Run holds. See docs/adr/0002-the-run-is-a-rulebook-not-a-service.md.
    // ------------------------------------------------------------------------------------------

    /**
     * Hand something that happened to the Run.
     *
     * Bluetooth callbacks on a Binder thread, GPS callbacks on the location thread, taps on main
     * and the per-second pulse all arrive this way and only this way, so there is a single ordering
     * of everything that happens to a Run and a single thread that touches its state. That is why
     * nothing inside the module needs a lock or a `@Volatile`.
     *
     * The event carries the time it happened rather than the time it is handled: a heart-rate
     * packet's own timestamp is what the cue ladder reasons about, and a queue hop must not move it.
     */
    private fun postRunEvent(event: RunEvent) {
        sessionHandler?.post { dispatchRunEvent(event) }
    }

    /**
     * The Run's inbox. [sessionHandlerThread] only — either posted here by [postRunEvent] or called
     * directly by the pulse, which already runs on it.
     *
     * Publish first, then perform: the notification's own actions are built from the published
     * state, so an effect must never be carried out against a state the app has not been told about.
     */
    private fun dispatchRunEvent(event: RunEvent) {
        val outcome = Run.onEvent(runState, event)
        runState = outcome.state
        publishRun(runState, event.nowMillis)
        outcome.effects.forEach(::perform)
    }

    /**
     * The one write of the Run into [HrState] (#130), replacing some thirty scattered updates.
     *
     * The Run returns its whole state, so there is nothing to decide here beyond naming: which of
     * the Run's fields each of the screen's older names is. The Strap's own writes are untouched,
     * and no UI file is modified.
     *
     * The rule for a Run that is not live: the published state describes a Run in progress, so with
     * none in progress it describes nothing in progress. The totals a finished Run leaves behind
     * stay, exactly as they did.
     */
    private fun publishRun(run: RunState, nowMillis: Long) {
        val live = run.lifecycle.isLive
        val intervals = run.intervals?.takeIf { live }
        val hrAge = if (lastHrTimestamp > 0) (nowMillis - lastHrTimestamp) / 1000 else 0
        _hrState.update {
            it.copy(
                sessionStatus = run.lifecycle.asSessionStatus(),
                secondsRunning = run.secondsRunning,
                walkBreaksCount = run.walkBreaks,
                lastHrAgeSeconds = hrAge,
                isSimulating = isSimulationEnabled,

                avgBpm = run.heartRate.smoothedBpm,
                coachWaitingLine = run.coachWaitingLine(nowMillis),

                currentPhase = run.phase.asSessionPhase(),
                phaseSecondsElapsed = if (live) run.phaseSecondsElapsed else 0,
                phaseSecondsRemaining = if (live) run.phaseSecondsRemaining else 0,

                // True for the whole of a Workout's Run — including its warm-up, before the first
                // Interval opens — and false again once the Intervals are behind it, which is what
                // the field has always meant. Whether there is an Interval to show is [intervals].
                isStructuredWorkout = live && run.config?.isRunWalkMode == true && !run.intervalsFinished,
                // With no Interval in progress the label keeps whatever it last said, which is what
                // a screen still showing the finished Workout's last step says today. A Run that is
                // over publishes RUN, so the next one cannot inherit it.
                structuredWorkoutPhase = intervals?.kind?.asStructuredPhase()
                    ?: if (live) it.structuredWorkoutPhase else StructuredWorkoutPhase.RUN,
                phaseTimeRemainingSeconds = intervals?.secondsRemaining?.coerceAtLeast(0) ?: 0,
                currentRepeat = intervals?.repeat ?: 1,
                totalRepeats = intervals?.totalRepeats ?: 0,
                currentIntervalPlannedSeconds = intervals?.plannedSeconds ?: 0,
                nextIntervalType = intervals?.nextKind?.asStructuredPhase(),
                nextIntervalDurationSeconds = intervals?.nextSeconds ?: 0,
                workoutProgressPercent = intervals?.progressPercent ?: 0,
                currentIntervalElapsedSeconds = intervals?.secondsElapsed ?: 0,
                triggerAtSecond = run.trigger.atSecond,

                // The four the screen must only ever read off a live Run: its pinned target, the
                // pair its zones are sliced from, its pinned mode, and the row the feedback sheet
                // will be attached to.
                activeTargetZone = if (live) run.config?.targetZone else null,
                activeHrProfile = if (live) run.config?.hrProfile else null,
                activeRunMode = if (live) run.config?.runMode?.settingValue else null,
                activeDbSessionId = if (live) run.runRowId else null,
            )
        }
    }

    private fun RunLifecycle.asSessionStatus(): SessionStatus = when (this) {
        RunLifecycle.IDLE -> SessionStatus.IDLE
        RunLifecycle.RUNNING -> SessionStatus.RUNNING
        RunLifecycle.PAUSED -> SessionStatus.PAUSED
        RunLifecycle.STOPPING -> SessionStatus.STOPPING
        RunLifecycle.STOPPED -> SessionStatus.STOPPED
    }

    private fun RunPhase.asSessionPhase(): SessionPhase = when (this) {
        RunPhase.WARM_UP -> SessionPhase.WARM_UP
        RunPhase.MAIN -> SessionPhase.MAIN
        RunPhase.COOL_DOWN -> SessionPhase.COOL_DOWN
    }

    private fun IntervalKind.asStructuredPhase(): StructuredWorkoutPhase = when (this) {
        IntervalKind.RUN -> StructuredWorkoutPhase.RUN
        IntervalKind.WALK -> StructuredWorkoutPhase.WALK
    }

    /**
     * The live screen's fallback coach line: what the coach is waiting on when it has nothing to
     * say. Read off the Run's own coaching state rather than recomputed, so the line cannot
     * describe a coach the Run is not running.
     */
    private fun RunState.coachWaitingLine(nowMillis: Long): String = when {
        heartRate.recent.isEmpty() -> "Ready"
        !controls.coachingEnabled -> "Off"
        coaching.band == ZoneBand.IN || coaching.band == ZoneBand.UNKNOWN -> "Ready"
        else -> "Next: ${coaching.ladder.secondsUntilNextCue(nowMillis)}s"
    }

    /**
     * Everything the Run asked for, each mapped to one call.
     *
     * No branching and no state of its own, deliberately: if this ever needs a decision, the
     * decision belongs in [Run], where it can be tested. That is why this has no seam of its own
     * and is verified on the phone instead.
     */
    private fun perform(effect: RunEffect) {
        when (effect) {
            is RunEffect.CreateRunRow -> createRunRow(effect)
            is RunEffect.FinalizeRun -> finalizeRun(effect)
            is RunEffect.SaveHrSample -> saveHrSample(effect)
            is RunEffect.SaveIntervalStat -> saveIntervalStat(effect)
            is RunEffect.Speak -> speakCue(effect)
            is RunEffect.WithdrawCue -> withdrawCue(effect.tag)
            is RunEffect.Notify -> updateNotification(effect.text)
            RunEffect.StartGps -> startGps()
            RunEffect.StopGps -> locationTracker?.stop()
            RunEffect.ReleaseStrap -> releaseStrapAndTimer()
        }
    }

    /**
     * Insert the Run's row and post its id back.
     *
     * The Run buffers everything it produces until that id lands, so this is asynchronous without
     * anything being dropped and without any lock, flag or gate describing the window: see
     * [Run]'s `finish`.
     */
    private fun createRunRow(effect: RunEffect.CreateRunRow) {
        // Emitted once per Run and by nothing else, which makes it the one place the things a Run
        // needs zeroed but does not own can be zeroed: GPS's distance and pace, and the Strap's
        // last reading and the clock that ages it.
        locationTracker?.resetSessionState()
        // Clear the HR-freshness clock so age is measured within this Run. A strapless Run started
        // after one that had HR would otherwise inherit a stale timestamp, read as a huge
        // lastHrAgeSeconds, and trip the screen's sensor-lost warning on a Run deliberately started
        // without a Strap (#110). The live reading goes with it, in lock-step: with the clock reset
        // a stale packet would look fresh. A real packet repopulates both within a second.
        lastHrTimestamp = 0L
        _hrState.update { it.copy(bpm = 0, distanceKm = 0.0, paceMinPerKm = 0.0) }
        recorderWriteScope.launch {
            val runRowId = database.sessionDao().insertSession(
                RunnerSession(
                    startTime = effect.startedAtMillis,
                    targetZone = effect.targetZoneNumber,
                    runMode = effect.runModeSettingValue,
                    includeInAiTraining = effect.includeInAiTraining,
                )
            )
            Log.d(TAG, "Started DB Session: $runRowId (Mode: ${effect.runModeSettingValue})")
            postRunEvent(RunEvent.RunRowCreated(runRowId, System.currentTimeMillis()))
        }
    }

    private fun saveHrSample(effect: RunEffect.SaveHrSample) {
        val sample = HrSample(
            sessionId = effect.runRowId,
            elapsedSeconds = effect.sample.elapsedSeconds,
            rawBpm = effect.sample.rawBpm,
            smoothedBpm = effect.sample.smoothedBpm,
            connectionState = effect.sample.connectionStatus,
            timestampMillis = effect.sample.atMillis,
            // Pace is GPS's, which the Run starts and stops but never reads.
            paceMinPerKm = _hrState.value.paceMinPerKm,
        )
        recorderWriteScope.launch { database.sampleDao().insertSample(sample) }
    }

    private fun saveIntervalStat(effect: RunEffect.SaveIntervalStat) {
        val stat = effect.stat
        val row = RunWalkIntervalStat(
            sessionId = effect.runRowId,
            intervalIndex = stat.intervalIndex,
            plannedDurationSeconds = stat.plannedDurationSeconds,
            actualRunningDurationBeforeHrTriggerSeconds = stat.actualRunningDurationBeforeHrTriggerSeconds,
            timeIntoIntervalWhenHrExceededCapSeconds = stat.timeIntoIntervalWhenHrExceededCapSeconds,
            hrTriggerEvents = stat.hrTriggerEvents,
            totalTimeSpentWalkingDuringRunIntervalSeconds = stat.totalTimeSpentWalkingDuringRunIntervalSeconds,
            avgHrAtTriggerInInterval = stat.avgHrAtTriggerInInterval,
            avgRecoverySecondsAfterTriggerInInterval = stat.avgRecoverySecondsAfterTriggerInInterval,
        )
        recorderWriteScope.launch { database.runWalkIntervalStatDao().insertIntervalStats(listOf(row)) }
    }

    private fun startGps() {
        // GPS was never stopped through an auto-pause, so the recorder's own flag has had no
        // stop()/discardLastFix() to clear it; a resume must say so explicitly (#39).
        locationTracker?.clearAutoPauseState()
        // The Run asks for GPS only on an outdoor Run. Simulation's veto stays with the tracker,
        // which is where it was: a simulated run records the mode it chose but no route.
        locationTracker?.restartIfNeeded("run", RunMode.OUTDOOR.settingValue, isSimulationEnabled)
    }

    /**
     * Write the finished Run's totals, then everything that hangs off a Run being over: the
     * Downloads snapshot, the weather look-up and the coach's evaluation.
     *
     * The totals come from the module, which watched them accrue — no reading the row back and
     * patching it. What is still read from the outside is what the Run never had: distance, pace
     * and the start position, which are GPS's.
     */
    private fun finalizeRun(effect: RunEffect.FinalizeRun) {
        val runRowId = effect.runRowId
        val totals = effect.totals
        val distanceKm = locationTracker?.getDistanceKm() ?: 0.0
        // The run's totals, not LocationTracker's live pace - that is a rolling 15-second window,
        // so reading it here stored the pace of someone standing still at the finish (#163). The UI
        // derives pace this same way on read, which is what fixes runs already in history; the
        // column is written from the same function so the two can never drift apart.
        val avgPace = averagePaceMinPerKm(totals.durationSeconds, distanceKm)
        val startLocation = locationTracker?.getFirstLocation()

        // weatherFetchScope, not serviceScope: a background STOP (a notification action with the
        // activity unbound) reaches stopSelf() -> onDestroy -> serviceScope.cancel() on the next
        // main-loop message, and a launch not yet dequeued dies before its body — NonCancellable
        // cannot protect a coroutine that never starts.
        weatherFetchScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val session = database.sessionDao().getSessionById(runRowId)
                if (session == null) {
                    Log.w(TAG, "Finalize found no row $runRowId")
                    return@withContext
                }
                val updatedSession = session.copy(
                    endTime = totals.endedAtMillis,
                    durationSeconds = totals.durationSeconds,
                    avgBpm = totals.averageBpm,
                    maxBpm = totals.maxBpm,
                    distanceKm = distanceKm,
                    avgPaceMinPerKm = avgPace,
                    startLatitude = startLocation?.latitude,
                    startLongitude = startLocation?.longitude,
                    zone1Seconds = totals.zoneSeconds.zone1,
                    zone2Seconds = totals.zoneSeconds.zone2,
                    zone3Seconds = totals.zoneSeconds.zone3,
                    zone4Seconds = totals.zoneSeconds.zone4,
                    zone5Seconds = totals.zoneSeconds.zone5,
                    noDataSeconds = totals.noDataSeconds,
                    walkBreaksCount = totals.walkBreaks,
                    isRunWalkMode = totals.isRunWalkMode,
                )
                // Let the Run's still-queued sample and track-point inserts land before the row is
                // stamped as finished. An end time is what everything downstream reads as "this Run
                // is complete" — the history snapshot below, and the GPX export, which offers Share
                // the moment it sees one (#84). Stamping first would let a runner who shares
                // straight after stopping export the Run minus its final seconds.
                recorderWriteScope.coroutineContext.job.children.toList().joinAll()

                database.sessionDao().updateSession(updatedSession)

                // Measured only now the track-point inserts above have landed, so it sees the whole
                // run. This also rewrites avgPaceMinPerKm over the duration-based value set above:
                // pace is quoted against other apps, so it is measured over moving time (#163).
                //
                // The run is already saved by this point, and weatherFetchScope carries no
                // exception handler, so a failure here must not be allowed to take the process
                // down and strand the backup, weather fetch and plan evaluation below it. A run
                // that fails to measure keeps a null moving time and is picked up by the backfill.
                val movingTime = try {
                    sessionRepository.computeMovingTime(runRowId)
                } catch (e: Exception) {
                    Log.w(TAG, "Moving time failed for $runRowId; leaving it to the backfill", e)
                    null
                }

                // Scored after the track-point inserts have landed, for the same reason moving time
                // is: a record measured over half a run is a record nobody ran. Its own attempt,
                // because the Run is already saved and a book that cannot be written must not cost
                // the runner the backup, the weather or the coach's evaluation below.
                try {
                    val earned = sessionRepository.scoreRecords(runRowId)
                    if (earned.isNotEmpty()) {
                        Log.d(TAG, "Run $runRowId earned ${earned.size} achievement(s): " +
                            earned.joinToString { "${it.medal} ${it.type}" })
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not score run $runRowId against the record book", e)
                }

                Log.d(
                    TAG,
                    "Finalized DB Session: $runRowId. Evidence: duration=${updatedSession.durationSeconds} moving=$movingTime"
                )

                // Snapshot run history to Downloads so it survives "Clear storage" (reinstall is
                // covered separately by Auto Backup).
                weatherFetchScope.launch {
                    DatabaseBackupManager.backup(applicationContext, database)
                }

                // Not awaited, so a slow or unreachable weather service cannot hold anything up;
                // missed fetches are retried at next launch.
                val startLatitude = updatedSession.startLatitude
                val startLongitude = updatedSession.startLongitude
                if (updatedSession.runMode == RunMode.OUTDOOR.settingValue &&
                    startLatitude != null && startLongitude != null
                ) {
                    weatherFetchScope.launch {
                        sessionRepository.fetchAndSaveWeather(
                            sessionId = runRowId,
                            latitude = startLatitude,
                            longitude = startLongitude,
                            atEpochMillis = updatedSession.startTime,
                        )
                    }
                }

                val stageId = currentSettings.activeStageId
                if (stageId != null) {
                    if (updatedSession.includeInAiTraining && !currentSettings.testingModeEnabled) {
                        // Whether this Run is one the coach adjusts is its Run Type's answer, given
                        // once inside evaluateAndAdjustPlan (#176) — asking it here too would be the
                        // same rule in two places, free to drift apart.
                        Log.d("AiCoach", "Triggering AI evaluation after session finalization for stage: $stageId")
                        sessionRepository.evaluateAndAdjustPlan(stageId, totals.runType)
                    } else {
                        Log.d(
                            "AiCoach",
                            "Skipping AI evaluation: session opted out or testing mode enabled for stage=$stageId"
                        )
                    }
                }
            }
        }
    }

    /**
     * Everything the Run is given at START and never asks about again (#131).
     *
     * Read once, here, on the thread the tap arrived on. A Run is recorded entirely under the
     * settings that were in force when it began — Max HR most of all, which used to be read live on
     * every second of zone accounting while Settings stayed reachable mid-Run.
     */
    private fun pinRunConfig(runMode: RunMode): RunConfig {
        val settings = currentSettings
        // The plan attaches automatically (#107); skipping today is the only thing that detaches
        // it, and it never edits the plan. Warm-up and cool-down come from the Workout, so an
        // unplanned or skipped Run has neither.
        val workout = if (skipPlanForToday) null else resolveActiveWorkoutTemplate()
        return RunConfig(
            hrProfile = settings.hrProfile,
            // The Workout sets the target when a plan is attached; otherwise the global is the
            // fallback (#107).
            targetZone = workout?.let { HrZone.ofNumberOrDefault(it.targetZone) } ?: settings.targetHrZone,
            runMode = runMode,
            workout = workout,
            includeInAiTraining = settings.aiDataSharingEnabled && !settings.testingModeEnabled,
        )
    }

    /** The settings the runner may still change mid-Run, delivered as events rather than read. */
    private fun controlsFrom(settings: UserSettings) = RunControls(
        coachingEnabled = settings.coachingEnabled,
        autoPauseEnabled = settings.autoPauseEnabled,
        splitAnnouncementsEnabled = settings.splitAnnouncementsEnabled,
        turnaroundCueEnabled = settings.turnaroundCueEnabled,
    )

    /**
     * Begin a Run: the pinned configuration goes to the Run, and the pulse starts.
     *
     * The Run itself decides whether there is one to begin — a START arriving while one is live is
     * ignored by the module, so there is no second guard here to keep in step with it.
     */
    private fun startRun(runMode: RunMode) {
        val now = System.currentTimeMillis()
        postRunEvent(RunEvent.Started(pinRunConfig(runMode), controlsFrom(currentSettings), now))
        startSessionTimerLoop()
    }

    inner class LocalBinder : Binder() {
        fun getService(): HrForegroundService = this@HrForegroundService
    }

    fun isSessionActive(): Boolean =
        _hrState.value.sessionStatus == SessionStatus.RUNNING ||
            _hrState.value.sessionStatus == SessionStatus.PAUSED

    override fun onBind(intent: Intent): IBinder {
        isActivityBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isActivityBound = false
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        isActivityBound = true
        super.onRebind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        val appContainer = runningAppContainer()
        settingsRepository = appContainer.settingsRepository
        
        serviceScope.launch {
            settingsRepository.userSettingsFlow.collect { settings ->
                currentSettings = settings
                _hrState.update { it.copy(userSettings = settings) }
                // Coaching, auto-pause and Split announcements are controls the runner flips
                // mid-Run, so they reach the Run as events rather than being read out of a
                // settings object. Obeyed from the moment they arrive, which is the point (#109).
                postRunEvent(RunEvent.ControlsChanged(controlsFrom(settings), System.currentTimeMillis()))
            }
        }

        serviceScope.launch {
            appContainer.coachPrescriptionRepository.prescriptionsFlow.collect {
                currentPrescriptions = it
            }
        }

        // Promotion, derived. This is the whole of it: no code anywhere else promotes or demotes,
        // so there is no release to forget. What to skip and what to act on is Promotion's own
        // question — deduping here on the published state alone stranded the eager start-command
        // promote (#144), so the subscription lives in follow().
        // See docs/adr/0001-promotion-is-derived-not-claimed.md.
        serviceScope.launch {
            promotion.follow(_hrState.map { it.sessionStatus to it.acquiringStrap })
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        tts?.let {
            audioCueManager = AudioCueManager(
                it,
                audioManager,
                serviceScope,
                TAG,
                // Nothing acts on this; it is here to be read in logcat when a run's cues are
                // being checked on the phone, which is the only way this ticket's back-to-back
                // rule can be verified (#53).
                onCueActivity = { speaking, sequence ->
                    Log.d(TAG, "Cue queue ${if (speaking) "speaking" else "quiet"} (seq=$sequence)")
                },
            )
        }
        
        
        database = appContainer.database
        sessionRepository = appContainer.sessionRepository
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationTracker = LocationTracker(
            context = this,
            fusedLocationClient = fusedLocationClient,
            logTag = TAG,
            announceSplit = { enqueueCue(it, CuePriority.INFORMATION) },
            getSessionStatus = { _hrState.value.sessionStatus },
            isSplitAnnouncementsEnabled = { currentSettings.splitAnnouncementsEnabled },
            onMetricsUpdated = { distanceKm, paceMinPerKm, lastLocation ->
                _hrState.update { it.copy(distanceKm = distanceKm, paceMinPerKm = paceMinPerKm) }
            },
            isAutoPauseEnabled = { currentSettings.autoPauseEnabled },
            onAutoPause = { postRunEvent(RunEvent.AutoPauseRequested(System.currentTimeMillis())) },
            onAutoResume = { postRunEvent(RunEvent.AutoResumeRequested(System.currentTimeMillis())) },
            onRawFix = { location, barometerPressureHpa, startsAfterPause ->
                // The Run's row id off its published state: the tracker's thread has no business
                // reading the Run, and the Run has no business knowing what a fix is.
                val sessionId = _hrState.value.activeDbSessionId
                if (sessionId != null) {
                    // The tracker is reused between runs and ends every one of them with a stop, so
                    // it offers the first fix of a new run as though a pause preceded it. A run's
                    // opening fix breaks nothing — there is no earlier point to be joined to.
                    val resumedHere = startsAfterPause && sessionId == lastTrackedSessionId
                    lastTrackedSessionId = sessionId
                    val trackPoint = TrackPoint(
                        sessionId = sessionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                        horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        verticalAccuracyMeters = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        barometerPressureHpa = barometerPressureHpa,
                        timestampMillis = location.time,
                        source = TrackPointSource.GPS,
                        startsAfterPause = resumedHere
                    )
                    recorderWriteScope.launch {
                        database.trackPointDao().insertTrackPoint(trackPoint)
                    }
                }
            }
        )
        
        // Mission: Dedicated Session Thread
        sessionHandlerThread = HandlerThread("SessionTrackingThread").apply { start() }
        sessionHandler = Handler(sessionHandlerThread!!.looper)
        
        createNotificationChannel()
    }

    /**
     * (Re)start the per-second pulse — the Run's own clock, and the only thing that speaks to it
     * without being asked to.
     *
     * The pulse carries the wall-clock time rather than the fact that it arrived, so a janky screen
     * or a dozing phone costs the Run no seconds: a tick five seconds late advances five seconds.
     */
    private fun startSessionTimerLoop() {
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        sessionHandler?.post(sessionTimerRunnable)
    }

    private fun resolveActiveWorkoutTemplate(): WorkoutTemplate? {
        val baseWorkout = TrainingPlanProvider.resolvePickedWorkout(
            currentSettings.activePlanId,
            currentSettings.activeStageId,
            pickedWorkoutId
        ) ?: return null
        // Shared with the record screen's card, so what it promises is what this runs (#111).
        return baseWorkout.withCoachPrescription(currentPrescriptions, System.currentTimeMillis())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives us roughly five seconds from startForegroundService() to startForeground(),
        // whatever this intent turns out to want — so promote before we know. The reconcile at the
        // tail takes it back if nothing earned it.
        promotion.promoteForStartCommand()
        // Unless the intent asked for a Run. The Run publishes from its own thread, so the tail
        // reconcile would run first, read a state where nothing has started yet, and demote.
        var deferReconcileToRun = false

        if (intent == null) {
            // START_STICKY restart after process death: the run that justified the foreground
            // state died with the process (every session field reset with it). Nothing can be
            // resumed, so don't sit around as an idle notification holding a 10-hour wake lock.
            // The promote above is still required (the system expects startForeground after
            // restarting a foreground service); state says IDLE, so the reconcile drops it.
            Log.d(TAG, "onStartCommand: null intent (sticky restart) - nothing to resume, stopping")
            reconcileForegroundPromotion()
            return START_NOT_STICKY
        }

        // Only session-starting intents carry the skip choice; leave it untouched for pause/resume
        // and other control intents so it survives for the duration of the run.
        if (intent?.hasExtra(EXTRA_SKIP_PLAN) == true) {
            skipPlanForToday = intent.getBooleanExtra(EXTRA_SKIP_PLAN, false)
        }
        if (intent?.hasExtra(EXTRA_WORKOUT_ID) == true) {
            pickedWorkoutId = intent.getStringExtra(EXTRA_WORKOUT_ID)
        }
        Log.d(
            TAG,
            "Service start action=${intent?.action ?: "null"} skipPlanForToday=$skipPlanForToday"
        )

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val makeActive = intent.getBooleanExtra(EXTRA_MAKE_ACTIVE, false)
                if (!isSimulationEnabled) {
                    // Only posts the event; the phase is published on the session thread a moment
                    // later. The tail reconcile follows it through the same inbox — see
                    // [reconcileAfterAcquisition].
                    startHardwareSession(overrideAddress, makeActive)
                } else {
                    Log.d(TAG, "ACTION_START_FOREGROUND received while simulation is active. Skipping hardware startup.")
                }
            }
            ACTION_START_RUN -> {
                // START is the explicit act that begins a run (#110): heart rate is a sensor,
                // not a gate. The clock, distance, and the plan's intervals begin now; the strap
                // is acquired alongside, and zone coaching joins if/when HR arrives.
                //
                // Whether there is a Run to begin is the Run's own question — it ignores a START
                // that arrives while one is live or is still waiting on its row id, so there is no
                // guard here to keep in step with it.
                //
                // The run mode comes from the START intent when present so a just-tapped
                // Treadmill/Outdoor choice wins over a not-yet-persisted setting.
                startRun(
                    RunMode.ofSettingValue(
                        intent.getStringExtra(EXTRA_RUN_MODE) ?: currentSettings.runMode
                    )
                )
                // The Run publishes RUNNING from its own thread, a moment after this returns, so
                // the tail reconcile below would read IDLE and demote — stopSelf and all — the
                // Promotion this Run has just earned. The subscription in onCreate() takes it from
                // here: a Run that starts publishes, and a START the Run ignores was refused
                // because a Run is already live, which earns the Promotion anyway.
                deferReconcileToRun = true
                // Acquire the strap as a sensor unless we already have it, HR is simulated, or a
                // connection is already in flight. Kicking off a fresh acquisition mid-connect
                // would call startHardwareSession(null) -> startScanning() (no saved address yet
                // for a first pairing), tearing down the pending GATT and dropping the strap the
                // user just chose in Manage Devices. Let an in-progress connect finish and join
                // the run instead.
                //
                // Retrying counts as in flight: a retry is already scheduled and a parallel
                // connect here would be torn down by it. A bare scan does NOT — nothing ever
                // auto-connects from scan results, so deferring to one leaves the whole run
                // strapless while the scanner burns battery. Both of those, and the rule that a
                // connected Strap only counts when it is the active one, are
                // [AcquisitionState.coversRunStart] — asked of the phase rather than spelled here
                // for a fourth time.
                //
                // Asked on the Acquisition's own thread rather than of the published snapshot: a
                // strap chosen in Manage Devices a moment before START is still an event in the
                // queue, and main would read the Idle state it has not replaced yet — then start a
                // scan, or a connect to the older active strap, that wins last and closes the GATT
                // the runner just picked.
                if (!isSimulationEnabled) {
                    val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    val activeAddress = currentSettings.activeDeviceAddress
                    sessionHandler?.post {
                        if (!acquisitionState.coversRunStart(activeAddress)) {
                            startHardwareSession(overrideAddress)
                        }
                    }
                }
            }
            ACTION_STOP_FOREGROUND -> {
                stopRun()
            }
            ACTION_PAUSE_SESSION -> {
                pauseFromShade()
            }
            ACTION_RESUME_SESSION -> {
                resumeFromShade()
            }
            ACTION_FORCE_SCAN -> {
                Log.d(TAG, "ACTION_FORCE_SCAN received")
                val status = _hrState.value.sessionStatus
                val runActive = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
                if (runActive) {
                    // Scanning tears down the current strap, and a scan-only disconnect sets STOPPED
                    // without going through stopRun()'s finalization (see disconnect()) — so a
                    // scan mid-run would silently drop the active run and orphan its DB row. Pairing
                    // is a pre-run action; never scan while a run is live.
                    Log.d(TAG, "Ignoring Force Scan - a run is active (status=$status)")
                } else if (!isSimulationEnabled) {
                    logBleDecision("force_scan", "User requested a fresh scan; skipping saved-device reconnect")
                    // Whether a Strap has to be hung up on first is the Acquisition's to answer,
                    // on its own thread: a connect completing right now is a GattConnected still
                    // in the queue, and the snapshot read here would say Connecting.
                    postAcquisitionEvent(AcquisitionEvent.ScanRequested(force = true))
                } else {
                    Log.d(TAG, "Ignoring Force Scan - Simulation Mode is active.")
                }
            }
            ACTION_SET_SIMULATION -> {
                val enabled = intent.getBooleanExtra(EXTRA_SIMULATION_ENABLED, isSimulationEnabled)
                // Simulation starts a Run of its own, so the same deferral applies.
                val simulationStartedRun = setSimulationEnabled(enabled)
                deferReconcileToRun = simulationStartedRun
            }
        }
        // Take back the eager promotion above if the dispatch earned nothing. An intent that
        // changes no state publishes nothing for the subscription in onCreate() to react to, so
        // this call is not redundant with it.
        if (!deferReconcileToRun) reconcileAfterAcquisition()
        return START_STICKY
    }
    
    /** The live screen's pause/resume button — one control, so it asks for whichever it is not. */
    fun togglePause() {
        postRunEvent(RunEvent.PauseToggled(System.currentTimeMillis()))
    }

    /**
     * The notification's Pause and Resume actions, which are two buttons rather than a toggle.
     *
     * They ask for a named direction because the shade lags the Run: a Resume still on screen after
     * the Run resumed itself must do nothing, not pause a Run the runner is watching. The Run
     * refuses them from its own state, so nothing here races the lag it is guarding against.
     */
    private fun pauseFromShade() {
        postRunEvent(RunEvent.PauseRequested(System.currentTimeMillis()))
    }

    private fun resumeFromShade() {
        postRunEvent(RunEvent.ResumeRequested(System.currentTimeMillis()))
    }

    /** The skip button: warm-up hands over to main, main to cool-down, cool-down ends the Run. */
    fun skipCurrentPhase() {
        postRunEvent(RunEvent.PhaseSkipped(System.currentTimeMillis()))
    }

    /**
     * STOP, from the button, the notification's action, or the Run's own cool-down.
     *
     * Two separate acts, which is why they are two lines. Ending the Run is the Run's: a second
     * STOP finalizes nothing, and a STOP that lands before the Run's row id exists is remembered
     * and finalized when it arrives, so nothing here needs to know which of those it is. Letting
     * go of the Strap is the service's, and happens even when there was no Run to end — a pre-run
     * notification Stop still dismisses the service.
     */
    fun stopRun() {
        postRunEvent(RunEvent.Stopped(System.currentTimeMillis()))
        // Kept here for the STOP that ends no live Run — a pre-run notification Stop, or a second
        // STOP after the Run already finished itself — where the Run emits no effect to release
        // by. A STOP that does end a live Run also gets RunEffect.ReleaseStrap; both acts are
        // idempotent, so the overlap is harmless. See the effect's own note.
        releaseStrapAndTimer()
    }

    override fun onInit(status: Int) {
        audioCueManager?.onTtsInit(status)
    }
    
    /**
     * Say something, in its turn among everything else waiting (#53). The one way anything in this
     * app speaks — the split announcements and the UI's target-reached cue come through here too.
     */
    fun enqueueCue(text: String, priority: CuePriority) {
        audioCueManager?.enqueue(text, priority)
    }

    /** The Run's [RunEffect.Speak], keeping the ticket for one it may ask to have back. */
    private fun speakCue(effect: RunEffect.Speak) {
        val ticket = audioCueManager?.enqueue(effect.text, effect.priority) ?: return
        effect.tag?.let { outstandingCues[it] = ticket }
    }

    /**
     * Take back a cue that has not been spoken: whatever it was going to say is no longer true
     * (#208). Asked for by the Run ([RunEffect.WithdrawCue]) and by the end of a Run.
     *
     * Inert when there is nothing to take back, and inert in the queue when the cue has already
     * gone out — so no caller has to know which of those it is.
     */
    private fun withdrawCue(tag: CueTag) {
        val ticket = outstandingCues.remove(tag) ?: return
        audioCueManager?.withdraw(ticket)
    }


    private var lastNotificationTime = 0L
    private val NOTIFICATION_THROTTLE_MS = 10_000L // 10 seconds in background
    
    /**
     * Put the Run's own words on the notification, subject to the background throttle.
     *
     * What it says is the Run's (ADR 0001) and arrives as [RunEffect.Notify]. What is left here is
     * when to post it: every refresh in the foreground, at most one every ten seconds in the
     * background — except that a zone crossing, a Phase change or a change of the Run's status is
     * worth waking the shade for. Status counts because it changes the notification's own buttons.
     */
    private fun updateNotification(text: String) {
        val now = System.currentTimeMillis()
        val isBackground = !isActivityBound

        val currentState = _hrState.value
        val notificationZone = hrZoneOf(currentState.bpm, currentSettings)
        val zoneChanged = notificationZone != lastNotificationZone
        val phaseChanged = currentState.currentPhase != lastNotificationPhase
        val statusChanged = currentState.sessionStatus != lastNotificationStatus

        val isCritical = zoneChanged || phaseChanged || statusChanged ||
            currentState.acquisition.phase is AcquisitionPhase.Blocked

        if (!isCritical && isBackground && (now - lastNotificationTime < NOTIFICATION_THROTTLE_MS)) {
            // Skip non-critical update while in background to save system resources
            return
        }

        lastNotificationTime = now
        lastNotificationZone = notificationZone
        lastNotificationPhase = currentState.currentPhase
        lastNotificationStatus = currentState.sessionStatus

        // Posting belongs to Promotion, which drops the text when there is no notification to put
        // it on — without that, an update landing just after a demotion posts one nothing owns and
        // nothing clears.
        promotion.showNotification(text)
    }

    /**
     * The Android half of Promotion. Every call the platform needs lives here; the decision to
     * make them lives in [ForegroundPromotion], which is why the decision has tests and this
     * doesn't. Nothing else in the app may call these.
     */
    private val promotionHost = object : PromotionHost {
        override fun promote(): Boolean {
            val notification = createNotification("Service is running...")
            try {
                startForegroundInternal(notification)
            } catch (e: Exception) {
                // Promotion is now derived, so it can be requested at moments the old
                // caller-driven code never reached — a pre-run reconnect starting on its own
                // while the app is backgrounded, say. Android 12+ answers that with
                // ForegroundServiceStartNotAllowedException. Losing the notification is a
                // degraded run; crashing the service mid-run is not a trade worth making.
                // Reporting false matters: claiming success would have us posting run updates
                // to a notification the platform never created.
                Log.w(TAG, "Foreground promotion refused: ${e.message}")
                return false
            }
            acquireWakeLock()
            return true
        }

        override fun demote() {
            // Reached with nothing promoted too, when a refused promotion's start has to be
            // handed back: releaseWakeLock and stopForeground are both no-ops in that case, and
            // stopSelf is the whole point of the call.
            Log.d(TAG, "demote - dropping notification/wake lock, BLE untouched")
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Safe mid-finalize: the activity's binding keeps the service alive while the app is
            // on screen, and the finalize coroutine runs on a scope that survives destruction.
            stopSelf()
        }

        override fun showNotification(text: String) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(text))
        }
    }

    private val promotion = ForegroundPromotion(promotionHost)

    private fun startForegroundInternal(notification: Notification) {
        // Mission: Specify foreground service types for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 throws if we claim a foreground-service type whose permission we don't hold,
            // so claim only the types actually granted. When neither location nor Bluetooth is
            // granted (simulate mode on a fresh install) fall back to DATA_SYNC — a type that needs
            // only the normal, auto-granted FOREGROUND_SERVICE_DATA_SYNC permission. The 2-arg
            // startForeground() is NOT a valid fallback: on 14 it defaults back to the manifest's
            // protected types and throws the same SecurityException.
            val granted = grantedForegroundServiceTypes()
            val types = if (granted != 0) granted else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The foreground-service types we may legally claim right now. Android 14 rejects a
     * startForeground whose declared type lacks its runtime permission, so LOCATION and
     * CONNECTED_DEVICE are each included only when granted. Returns 0 when neither is held.
     */
    private fun grantedForegroundServiceTypes(): Int {
        var types = 0
        val hasLocation =
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val hasConnectedDevice =
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (hasConnectedDevice) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return types
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunningApp::SessionWakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
            Log.d(TAG, "WakeLock acquired")
        }
    }
    
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        }
    }

    /**
     * Let go of the Strap: what "Stop Workout" means once the Run itself is already over.
     *
     * This used to also drop the foreground, which made it the second owner of Promotion. It no
     * longer touches it: disconnect() publishes "Disconnected", the Acquisition ends, and — if no
     * Run is live — [reconcileForegroundPromotion] demotes in response. The notification clears a
     * beat later than it did, rather than in the same instant.
     */
    private fun releaseStrapAndTimer() {
        Log.d(TAG, "releaseStrapAndTimer - letting go of the strap")
        // One event: the Acquisition stops whatever it was doing, scan included.
        disconnect()

        // A cue still waiting its turn belongs to the Run that has just ended. Speaking it after
        // the runner has stopped would be worse than losing it — and the queue itself drops
        // nothing, so this is the producer taking it back.
        withdrawCue(CueTag.TURNAROUND)

        // Mission: Stop the zombie timer loop immediately
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
    }

    /**
     * The one place Promotion is re-decided. Subscribed once in [onCreate] and called once more
     * at the tail of [onStartCommand] — an intent that changes no state (an ignored START) emits
     * nothing for the subscription to see, and its eager promotion still has to be taken back.
     */
    private fun reconcileForegroundPromotion() {
        val state = _hrState.value
        promotion.reconcile(state.sessionStatus, state.acquiringStrap)
    }

    /**
     * The tail reconcile, taken in the Acquisition's own order.
     *
     * An intent that starts a chase posts its event to the session thread ([postAcquisitionEvent]),
     * so the phase it asks for is not published by the time onStartCommand reaches its tail.
     * Reconciling there reads a still-idle Acquisition and demotes — stopSelf() and all — the scan
     * or pre-run reconnect the intent just asked for. It used to be true that the status was
     * published inline; the typed phase (ADR 0007) moved it behind the same inbox as the Run's.
     *
     * So take the same queue: a hop through the session thread lands behind whatever this dispatch
     * posted, and a hop back to main does the deciding where every other Promotion call is made.
     * Not [deferReconcileToRun]'s trick of leaving it to the subscription — an intent that changes
     * no state publishes nothing, and its eager promotion would never be handed back (#144).
     */
    private fun reconcileAfterAcquisition() {
        val handler = sessionHandler ?: return reconcileForegroundPromotion()
        handler.post { serviceScope.launch { reconcileForegroundPromotion() } }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, HrForegroundService::class.java).apply {
            action = ACTION_STOP_FOREGROUND
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(activityPendingIntent)

        when (_hrState.value.sessionStatus) {
            SessionStatus.RUNNING -> {
                val pauseIntent = Intent(this, HrForegroundService::class.java).apply {
                    action = ACTION_PAUSE_SESSION
                }
                val pausePendingIntent = PendingIntent.getService(
                    this, 2, pauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            }
            SessionStatus.PAUSED -> {
                val resumeIntent = Intent(this, HrForegroundService::class.java).apply {
                    action = ACTION_RESUME_SESSION
                }
                val resumePendingIntent = PendingIntent.getService(
                    this, 3, resumeIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
            }
            else -> Unit
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Workout", stopPendingIntent)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "HR Monitor Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // ------------------------------------------------------------------------------------------
    // The Acquisition: events in, one published state out, effects performed.
    //
    // Everything below is translation. The rules — when a scan gives up, how long to back off,
    // whether a callback is stale, which Strap becomes active — are in [Acquisition] and are
    // tested there. See docs/adr/0007-acquisition-is-a-rulebook-too.md.
    // ------------------------------------------------------------------------------------------

    /** Post an Acquisition event from any thread. Its inbox is the Run's. */
    private fun postAcquisitionEvent(event: AcquisitionEvent) {
        sessionHandler?.post { dispatchAcquisitionEvent(event) }
    }

    /**
     * The Acquisition's inbox. [sessionHandlerThread] only.
     *
     * Sharing one thread with the Run is what deleted `scanEpoch`, `connectRequestSeq` and
     * `gattConnectLock`: two connects can no longer interleave, so the last one simply wins, and
     * `runIsLive` below is a fact rather than a snapshot read across threads.
     */
    private fun dispatchAcquisitionEvent(event: AcquisitionEvent) {
        val wasInFlight = acquisitionState.inFlight
        val outcome = Acquisition.decide(acquisitionState, event, acquisitionContext())
        acquisitionState = outcome.state
        _hrState.update { it.copy(acquisition = outcome.state) }
        outcome.effects.forEach { performAcquisition(it) }
        // An Acquisition taking off needs the pulse, and pre-run there may be no Run to have
        // started it. Edge-triggered: a chase that is already under way has one already, and
        // starting it again every tick would be a busy loop.
        if (!wasInFlight && outcome.state.inFlight) startSessionTimerLoop()
    }

    /**
     * What is true right now. Read fresh for every decision and never remembered, so a permission
     * revoked or Bluetooth switched off mid-chase is just a different context on the next tick.
     */
    private fun acquisitionContext(): AcquisitionContext {
        val lifecycle = runState.lifecycle
        return AcquisitionContext(
            now = System.currentTimeMillis(),
            runIsLive = lifecycle.isLive,
            canScan = hasPermission(Manifest.permission.BLUETOOTH_SCAN),
            canConnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
            bluetoothOn = bluetoothAdapter?.isEnabled == true,
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Do one thing the Acquisition asked for.
     *
     * No branching on state and no state of its own, deliberately — the same discipline as the
     * Run's [perform]. Anything that looks like a decision here belongs in [Acquisition].
     */
    private fun performAcquisition(effect: AcquisitionEffect) {
        when (effect) {
            AcquisitionEffect.StartScan -> doStartScan()
            AcquisitionEffect.StopScan -> doStopScan()
            is AcquisitionEffect.ConnectGatt -> doConnectGatt(effect.address)
            is AcquisitionEffect.CloseGatt -> doCloseGatt(effect.address, andDisconnect = false)
            is AcquisitionEffect.DisconnectAndCloseGatt ->
                doCloseGatt(effect.address, andDisconnect = true)
            is AcquisitionEffect.DiscoverServices -> doDiscoverServices(effect.address)
            is AcquisitionEffect.SubscribeToHeartRate -> doSubscribe(effect.address)
            is AcquisitionEffect.SaveStrap -> serviceScope.launch {
                settingsRepository.saveDevice(effect.address, effect.name, effect.makeActive)
            }
            is AcquisitionEffect.TellRunStrapLost -> {
                // The Run is told the reading is gone rather than sent a zero, so the outage is
                // banked as no-data instead of being fabricated from the last packet.
                _hrState.update { it.copy(bpm = 0) }
                dispatchRunEvent(
                    RunEvent.HeartRateLost(effect.status, System.currentTimeMillis()),
                )
            }
        }
    }

    private fun doStartScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            scanner.startScan(scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
        }
    }

    private fun doStopScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            // Some devices need a stop before a start or the next scan fails silently, so this is
            // asked for even when nothing is scanning.
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed: ${e.message}")
        }
    }

    private fun doConnectGatt(address: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        logBleDecision("connect_gatt", "Opening GATT to address=$address")
        // One handle per address, or the overwritten one is a GATT nothing can close — and a
        // callback from it would be indistinguishable from the new one's. The rules close before
        // they connect, so this is the map's invariant held here, not a path anyone takes.
        doCloseGatt(address, andDisconnect = true)
        openGatts[address] = device.connectGatt(this, false, gattCallback)
        // connectGatt() is a Binder call and can outlast onDestroy's bounded join, landing a
        // handle after the destruction sweep has already been and gone. The flag is set before
        // that sweep, so reading it after the map write is the whole ordering: false means the
        // sweep has not run yet and will find this, true means it has and will not.
        if (destroyed) doCloseGatt(address, andDisconnect = true)
    }

    private fun doCloseGatt(address: String, andDisconnect: Boolean) {
        val gatt = openGatts.remove(address) ?: return
        // close() needs no permission; disconnect() does. Gating both would drop the GATT from the
        // map and never close it — the leak this map exists to make impossible.
        val mayDisconnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        gattCloseScope.launch {
            if (andDisconnect && mayDisconnect) gatt.disconnect()
            gatt.close()
        }
    }

    private fun doDiscoverServices(address: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val gatt = openGatts[address] ?: return
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        gatt.discoverServices()
    }

    private fun doSubscribe(address: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val gatt = openGatts[address] ?: return
        val characteristic = gatt.getService(HEART_RATE_SERVICE_UUID)
            ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    // --- The ways in ---

    /** Manage Devices, and the record screen's auto-connect. */
    fun connectToDevice(address: String, promoteToActive: Boolean = true) {
        val name = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            bluetoothAdapter?.getRemoteDevice(address)?.name
        } else {
            null
        }
        postAcquisitionEvent(AcquisitionEvent.ConnectRequested(address, name, promoteToActive))
    }

    fun startScanning() {
        postAcquisitionEvent(AcquisitionEvent.ScanRequested())
    }

    /**
     * Manage Devices "Forget" (#110). Narrower than [disconnect]: it touches only the Acquisition,
     * never the Run, so forgetting a Strap mid-Run behaves like a plain dropout.
     */
    fun forgetDevice(address: String) {
        postAcquisitionEvent(AcquisitionEvent.ForgetRequested(address))
    }

    fun disconnect() {
        postAcquisitionEvent(AcquisitionEvent.DisconnectRequested)
    }

    // --- The ways out: Android calling back ---

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            val device = result?.device ?: return
            // The name is read here, inside the permission that covers it, and a plain value
            // travels on. The published state used to carry BluetoothDevice objects all the way to
            // the device list, where reading .name was outside any check.
            val name = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name else null
            postAcquisitionEvent(AcquisitionEvent.StrapSeen(device.address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with errorCode=$errorCode")
            postAcquisitionEvent(AcquisitionEvent.ScanFailed(errorCode))
        }
    }

    /**
     * Post what a GATT reported, unless that GATT is not the handle we hold for its address.
     *
     * [Acquisition] speaks in addresses, and two chases of the same Strap in quick succession share
     * one: a `STATE_DISCONNECTED` arriving late from the superseded handle looks exactly like the
     * live one dropping, and the retry it earns closes the connection that just succeeded.
     *
     * Which handle is current is not a rule — it is a fact about objects Android gave us, the same
     * kind of thing as reading a device's name — so it is answered here rather than by carrying a
     * BluetoothGatt into the module. Asked on the session thread, because [openGatts] is written
     * there and this callback arrives on a Binder thread; posting the question is also what puts it
     * behind the connect that installed the handle.
     */
    private fun postFromGatt(gatt: BluetoothGatt?, event: (String) -> AcquisitionEvent) {
        onSessionThreadIfCurrent(gatt) { dispatchAcquisitionEvent(event(it)) }
    }

    /**
     * Run [action] on the session thread, but only if [gatt] is still the handle we hold for its
     * address. A superseded handle keeps delivering until its close lands, and that close is now
     * asynchronous — so this is the one gate everything a GATT says has to pass, readings included.
     */
    private fun onSessionThreadIfCurrent(gatt: BluetoothGatt?, action: (String) -> Unit) {
        val address = gatt?.device?.address ?: return
        sessionHandler?.post {
            if (openGatts[address] !== gatt) {
                Log.d(TAG, "Ignoring callback from a superseded GATT for address=$address")
                return@post
            }
            action(address)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            // Whether the Strap this handle chases is still the one being chased is
            // [Acquisition]'s to decide. A real GATT can report itself long after we stopped
            // caring, and the module closes it.
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    postFromGatt(gatt) { AcquisitionEvent.GattConnected(it) }
                BluetoothProfile.STATE_DISCONNECTED ->
                    postFromGatt(gatt) { AcquisitionEvent.GattDisconnected(it) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val device = gatt?.device ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val hasHeartRate = gatt.getService(HEART_RATE_SERVICE_UUID)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) != null
            val name = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                device.name ?: "Unknown"
            } else {
                "Unknown"
            }
            postFromGatt(gatt) {
                AcquisitionEvent.ServicesDiscovered(it, name, hasHeartRate)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
            // Copied because the packet is Android's buffer and we are about to leave its thread.
            val packet = value.copyOf()
            onSessionThreadIfCurrent(gatt) { handleHeartRate(packet) }
        }
    }

    private fun handleHeartRate(data: ByteArray) {
        if (isSimulationEnabled) return // Mission 3: Ignore real data during simulation
        if (data.isEmpty()) return
        val flag = data[0].toInt()
        val is16Bit = (flag and 0x01) != 0
        var bpm = 0
        if (is16Bit) {
             if (data.size >= 3) {
                 bpm = ((data[2].toInt() and 0xFF) shl 8) + (data[1].toInt() and 0xFF)
             }
        } else {
             if (data.size >= 2) {
                 bpm = data[1].toInt() and 0xFF
             }
        }
        
        val timestamp = System.currentTimeMillis()
        lastHrTimestamp = timestamp // Track for session engine age

        _hrState.update { it.copy(bpm = bpm) }
        // The reading itself is the Strap's to publish; what it means is the Run's. Every packet
        // goes to the Run, whether or not one is live — the Run's own guards decide whether the
        // coach so much as looks at it.
        postRunEvent(RunEvent.HeartRateSampled(bpm, _hrState.value.connectionStatus, timestamp))
    }

    private fun updateSimulationData() {
        // Simple sawtooth simulation to sweep through zones. Both turning points are derived from
        // the profile rather than fixed, because the zones no longer start at a percentage of Max
        // HR — they start above the stated resting heart rate (#172), and a hard-coded floor of 60
        // would leave the sweep never reading BELOW for a runner whose Zone 1 begins at 121.
        val profile = currentSettings.hrProfile
        simulationBpm += (5 * simulationDirection)
        if (simulationBpm >= effectiveMaxHr(profile.maxHr) + SIMULATION_OVERSHOOT_BPM) simulationDirection = -1
        if (simulationBpm <= zoneLowerBpm(HrZone.ENDURANCE, profile) - SIMULATION_OVERSHOOT_BPM) {
            simulationDirection = 1
        }

        handleHeartRateForSimulation(simulationBpm)
    }

    /**
     * A simulated reading, delivered exactly as a real one is.
     *
     * Simulation stays outside the Run and feeds it ordinary heart-rate events, so the developer
     * mode and a real Strap drive identical code — there is no simulated branch anywhere inside.
     * Called from the pulse, which is already the Run's thread, so it dispatches directly.
     */
    private fun handleHeartRateForSimulation(bpm: Int) {
        val timestamp = System.currentTimeMillis()
        lastHrTimestamp = timestamp

        _hrState.update { it.copy(bpm = bpm) }
        dispatchRunEvent(RunEvent.HeartRateSampled(bpm, _hrState.value.connectionStatus, timestamp))
    }

    /**
     * Turn the simulated Strap on or off.
     *
     * Returns whether a Run was started here, so [onStartCommand] knows to leave the eager
     * Promotion in place for the Run to justify rather than reconciling it away before the Run has
     * had a chance to publish.
     */
    fun setSimulationEnabled(enabled: Boolean): Boolean {
        if (isSimulationEnabled == enabled) {
            _hrState.update { it.copy(isSimulating = isSimulationEnabled) }
            Log.d(TAG, "Simulation unchanged: enabled=$isSimulationEnabled status=${_hrState.value.sessionStatus}")
            return false
        }

        isSimulationEnabled = enabled
        _hrState.update { it.copy(isSimulating = isSimulationEnabled) }

        if (!isSimulationEnabled) {
            Log.d(TAG, "Simulation Mode DISABLED")
            return false
        }

        // No promotion here. Simulation is not a reason to hold one — the Run it starts is, and
        // that Run earns it below. Promoting for simulation itself would strand the notification
        // and wake lock after every simulated run, because isSimulationEnabled is never cleared
        // by STOP.
        val status = _hrState.value.sessionStatus
        if (status == SessionStatus.RUNNING || status == SessionStatus.PAUSED) {
            // A Run is already going; it simply gains a simulated Strap.
            return false
        }
        // Whether there is a Run to begin is the Run's question, exactly as it is at START: it
        // ignores a Started that arrives while one is live, so there is no guard to keep here.
        startRun(RunMode.ofSettingValue(currentSettings.runMode))
        Log.d(TAG, "Simulation Mode ENABLED - started a run")
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called - Clean Exit")
        
        // 0. Anything that opens a GATT from here on closes it itself; the sweep below is the
        // last one there will be. Set before the join, so a connect that outlasts it sees this.
        destroyed = true

        // 1. Stop the Acquisition's thread before touching what it owns. quit() rather than
        // quitSafely(): a due ConnectRequested or retry tick would otherwise still run, and
        // opening a GATT after the sweep below leaks a handle with the service already gone.
        // join() waits out the one message that may be running right now, so the sweep is alone
        // with [openGatts] instead of racing it.
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        sessionHandlerThread?.quit()
        sessionHandlerThread?.join(SESSION_THREAD_JOIN_TIMEOUT_MS)
        sessionHandlerThread = null
        sessionHandler = null

        // 2. Clean up Bluetooth precisely. Destruction can be system-initiated and arrive with no
        // event loop left to run a decision on, so this reaches past [Acquisition] and closes what
        // is open directly — the same exception onDestroy already makes for the wake lock.
        doStopScan()
        val mayDisconnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        openGatts.values.forEach {
            if (mayDisconnect) it.disconnect()
            it.close()
        }
        openGatts.clear()

        // 3. Kill the remaining background loops
        serviceScope.cancel()

        locationTracker?.shutdown()

        // The one wake-lock release outside Promotion, and deliberately so: destruction can be
        // system-initiated, arriving without any demotion having happened. A wake lock must never
        // outlive the service that took it, so this is a last-resort safety net, not a second
        // owner of the decision. acquire/release are idempotent, so a preceding demote is fine.
        releaseWakeLock()
        audioCueManager?.shutdown()
        Log.d(TAG, "Service destroyed")
    }
}
