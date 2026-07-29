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
import kotlinx.coroutines.delay
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
import com.example.runningapp.foreground.isAcquiringStrap

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
    val connectionStatus: String = "Disconnected",
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val bpm: Int = 0,
    val scannedDevices: List<BluetoothDevice> = emptyList(),

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
    val triggerInCurrentInterval: Boolean = false,
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
     * Derived, so it cannot go stale, and named once so the three things that need it (the START
     * guard, the record screen's spinner, and Promotion) cannot drift apart. They already had:
     * the service's copy of this test omitted "Scanning".
     */
    val acquiringStrap: Boolean get() = isAcquiringStrap(connectionStatus)
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
    
    // Exposed state for UI
    private val _hrState = MutableStateFlow(HrState())
    val hrState: StateFlow<HrState> = _hrState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    // UUIDs for Heart Rate Service and Measurement Characteristic
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Reconnection & Rate Limiting State
    // Volatile: written on the main thread (connect/forget/scan paths) and read by GATT
    // callbacks on binder threads to reject stale discoveries.
    @Volatile private var targetDeviceAddress: String? = null
    private var reconnectDelay = 3000L
    private var isReconnecting = false
    private var isActivityBound = false
    
    // TTS & Audio Focus
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var audioCueManager: AudioCueManager? = null

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
            if (isSimulationEnabled) updateSimulationData()
            dispatchRunEvent(RunEvent.Tick(System.currentTimeMillis()))
            val lifecycle = runState.lifecycle
            // A Run that is over stops the pulse; STOP itself also drops the pending one, so this
            // is the belt to that braces — a Run cannot end and leave a tick still arriving.
            if (lifecycle != RunLifecycle.IDLE && lifecycle != RunLifecycle.STOPPED) {
                sessionHandler?.postDelayed(this, 1000)
            } else {
                Log.d(TAG, "Timer loop exiting - lifecycle is $lifecycle")
            }
        }
    }

    private lateinit var settingsRepository: SettingsRepository
    // The coach's standing prescription for today's workout, if any. Read once at START, like the
    // workout it adapts — a prescription arriving mid-run must not reshape a run in progress.
    @Volatile private var currentPrescription: CoachPrescription? = null
    private lateinit var sessionRepository: SessionRepository
    private var currentSettings = UserSettings()
    // Skip today's plan (#107): a per-run, today-only choice from the record screen. When set, the
    // run attaches no workout — an open-ended run with no warm-up/cool-down/intervals. It never
    // edits the plan, so tomorrow the plan is queued again.
    @Volatile private var skipPlanForToday: Boolean = false

    private lateinit var database: AppDatabase

    // Both written on main (or a Binder thread) and read on the session thread, which is the Run's
    // inbox: the pulse asks whether to feed the Run a simulated reading, and the published state
    // reports how stale the Strap's last packet is.
    @Volatile private var isSimulationEnabled = false

    private var simulationBpm = 70
    private var simulationDirection = 1

    private var reconnectAttemptCount = 0
    @Volatile private var lastHrTimestamp = 0L
    // With no run active, stop chasing an unreachable strap after this many attempts and land on
    // the terminal "Strap not found" state (each attempt already costs a ~30s connectGatt timeout
    // plus backoff). Mid-run reconnects are uncapped by design (#110).
    private val PRE_RUN_RECONNECT_MAX_ATTEMPTS = 3
    // A discovery scan with no user selection stops itself after this long (nothing ever
    // auto-connects from a scan, so an abandoned one would otherwise run forever).
    private val SCAN_TIMEOUT_MS = 60_000L
    // Bumped on every startScanning()/stopScanning(); lets the scan-timeout coroutine tell
    // whether ITS scan is still the live one.
    @Volatile private var scanEpoch = 0

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
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        // Set only by explicit Connect taps: marks the connect as a user choice whose strap may
        // be promoted to active on verification. Background auto-connects omit it.
        const val EXTRA_MAKE_ACTIVE = "EXTRA_MAKE_ACTIVE"
        const val EXTRA_SKIP_PLAN = "SKIP_PLAN"
        // START carries the mode the user has selected right now, so a Treadmill/Outdoor switch made
        // just before tapping START is honoured even if its async settings write hasn't landed yet.
        const val EXTRA_RUN_MODE = "EXTRA_RUN_MODE"
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
                triggerInCurrentInterval = run.trigger.occurred,
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
            is RunEffect.Speak -> playCue(effect.text)
            is RunEffect.Notify -> updateNotification(effect.text)
            RunEffect.StartGps -> startGps()
            RunEffect.StopGps -> locationTracker?.stop()
            RunEffect.ReleaseStrap -> {
                audioCueManager?.releaseForSessionStop()
                releaseStrapAndTimer()
            }
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
                if (stageId != null && totals.isRunWalkMode) {
                    if (updatedSession.includeInAiTraining && !currentSettings.testingModeEnabled) {
                        Log.d("AiCoach", "Triggering AI evaluation after session finalization for stage: $stageId")
                        sessionRepository.evaluateAndAdjustPlan(stageId)
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
            appContainer.coachPrescriptionRepository.prescriptionFlow.collect {
                currentPrescription = it
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
        tts?.let { audioCueManager = AudioCueManager(it, audioManager, serviceScope, TAG) }
        
        
        database = appContainer.database
        sessionRepository = appContainer.sessionRepository
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationTracker = LocationTracker(
            context = this,
            fusedLocationClient = fusedLocationClient,
            logTag = TAG,
            playCue = { playCue(it) },
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
        val baseWorkout = TrainingPlanProvider.resolveBaseWorkout(
            currentSettings.activePlanId,
            currentSettings.activeStageId
        ) ?: return null
        // Shared with the record screen's card, so what it promises is what this runs (#111).
        return baseWorkout.withCoachPrescription(currentPrescription, System.currentTimeMillis())
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
        Log.d(
            TAG,
            "Service start action=${intent?.action ?: "null"} skipPlanForToday=$skipPlanForToday"
        )

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val makeActive = intent.getBooleanExtra(EXTRA_MAKE_ACTIVE, false)
                if (!isSimulationEnabled) {
                    // Called inline, not launched: serviceScope is Dispatchers.Main, so a launch
                    // here would run after onStartCommand returned — and the reconcile at the tail
                    // would see an idle connection status and demote the acquisition it just
                    // started. startHardwareSession publishes "Scanning"/"Connecting" synchronously
                    // and only the GATT connect itself goes to IO, so inline is both safe and true.
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
                // "Retrying" ("Disconnected (Retrying)") counts as in flight: a reconnect
                // coroutine is already scheduled and a parallel connect here would be torn down
                // by it. A bare scan does NOT: nothing ever auto-connects from scan results
                // (the callback only fills the Discovered list), so deferring to one leaves the
                // whole run strapless while the scanner burns battery — let startHardwareSession
                // take over (connectToDevice stops the scan first; with no saved strap it just
                // rescans, which is where we already were).
                val connStatus = _hrState.value.connectionStatus
                // "Connected" completes acquisition only when the connected strap IS the
                // active one: Set Active in Manage Devices writes only the settings and
                // leaves the old GATT up, so a START after switching straps must re-acquire
                // the newly chosen device instead of recording HR from the old one (Codex P2
                // #123). With no saved active strap, whatever is connected is the sensor.
                val activeAddress = currentSettings.activeDeviceAddress
                val connectedActiveStrap = connStatus == "Connected" &&
                    (activeAddress == null || targetDeviceAddress == activeAddress)
                // A bare scan deliberately does NOT count (see above), so this is the shared
                // Acquisition test minus scanning — not a fourth hand-rolled copy of it.
                val acquisitionInFlight = connectedActiveStrap ||
                    (_hrState.value.acquiringStrap &&
                        !connStatus.contains("Scanning", ignoreCase = true))
                if (!isSimulationEnabled && !acquisitionInFlight) {
                    val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    // Inline for the same reason as ACTION_START_FOREGROUND above: the
                    // connection status must be true before the tail reconcile reads it.
                    startHardwareSession(overrideAddress)
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
                    if (_hrState.value.connectionStatus == "Connected") {
                        disconnect()
                    }
                    startScanning()
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
        if (!deferReconcileToRun) reconcileForegroundPromotion()
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
        audioCueManager?.releaseForSessionStop()
        releaseStrapAndTimer()
    }

    override fun onInit(status: Int) {
        audioCueManager?.onTtsInit(status)
    }
    
    fun playCue(text: String) {
        audioCueManager?.playCue(text)
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
            currentState.connectionStatus.contains("Failed")

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
        stopScanning()
        disconnect()

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

    // --- BLE Logic ---

    fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // Publishing the terminal status IS the release: the Acquisition is over, so
            // Promotion is no longer earned. Nothing to demote by hand (0beef0f).
            _hrState.update { it.copy(connectionStatus = "Permission Missing") }
            return
        }
        
        // Allow scanning even if connecting/reconnecting, but NOT if already connected
        if (_hrState.value.connectionStatus == "Connected") {
            Log.d(TAG, "startScanning() - Already connected, ignoring")
            return
        }

        Log.d(TAG, "startScanning() - Resetting connection state and starting fresh scan")
        logBleDecision("scan_reset", "Clearing reconnect target and scanned device list before scan")

        // ABORT any current connection attempts or reconnect loops. Supersede any queued
        // connect and close under the connect lock (see disconnect()).
        isReconnecting = false
        targetDeviceAddress = null
        synchronized(gattConnectLock) {
            ++connectRequestSeq
            bluetoothGatt?.close()
            bluetoothGatt = null
        }

        _hrState.update { it.copy(connectionStatus = "Scanning...", scannedDevices = emptyList()) }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "startScanning() - Bluetooth scanner unavailable!")
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
        }

        try {
            // Some devices need a stop before a start or it fails silently
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan failed during reset: ${e.message}")
        }

        scanner.startScan(scanCallback)
        Log.d(TAG, "startScanning() - BLE scan started")

        // A scan has no natural end: nothing auto-connects from it, so an abandoned one would
        // burn the scanner — and the Promotion it earns as an Acquisition — indefinitely.
        // Time-box it; the epoch guard cancels the timeout when a tap/connect stops this scan
        // and possibly starts another.
        val myEpoch = ++scanEpoch
        serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (myEpoch == scanEpoch &&
                _hrState.value.connectionStatus.contains("Scanning", ignoreCase = true)
            ) {
                Log.d(TAG, "Scan timed out after ${SCAN_TIMEOUT_MS / 1000}s with no selection")
                stopScanning()
                _hrState.update { it.copy(connectionStatus = "Disconnected") }
            }
        }
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Scan result: name=${device.name ?: "<unnamed>"} address=${device.address}")
                }
                _hrState.update { currentState ->
                    val currentList = currentState.scannedDevices
                    if (currentList.none { it.address == device.address }) {
                        if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            if (device.name != null) {
                                currentState.copy(scannedDevices = currentList + device)
                            } else {
                                currentState
                            }
                        } else {
                            currentState
                        }
                    } else {
                        currentState
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with errorCode=$errorCode")
            // Only report the failure if we're still in the scanning state: a late callback
            // delivered after the user already tapped a discovered strap must not overwrite
            // "Connecting to X..." and mask a genuinely in-flight GATT connect.
            _hrState.update {
                if (it.connectionStatus.contains("Scanning", ignoreCase = true)) {
                    it.copy(connectionStatus = "Scan Failed: $errorCode")
                } else {
                    it
                }
            }
        }
    }
    
    fun connectToDevice(address: String, promoteToActive: Boolean = true) {
        logBleDecision("connect_by_address", "Preparing direct connection to address=$address promote=$promoteToActive")
        stopScanning()

        // Step 3: Deep Cleanup / State Sanitization
        reconnectAttemptCount = 0
        reconnectDelay = 3000L

        // Only an explicit user tap (EXTRA_MAKE_ACTIVE on the connect intent) may promote the
        // strap to active on verification — and only THIS strap: the pending promotion is
        // address-typed, so a stale verify of some other strap can never consume it. Background
        // paths (record-screen auto-connect, saved-strap reconnect, retries via the device
        // overload) clear it instead, so auto-connecting strap A while the user makes strap B
        // active can no longer steal the active slot back (Codex P2 #123).
        promoteOnVerifyAddress = if (promoteToActive) address else null
        targetDeviceAddress = address
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            // No adapter (Bluetooth unavailable): a dead-end, so say so. Ending the Acquisition
            // is what releases the Promotion (0beef0f).
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
        }
        connectToDevice(device)
    }

    /**
     * Manage Devices "Forget" (#110): if the forgotten strap is the one we're connected to or
     * chasing, release it — otherwise the retry loop keeps reconnecting it and the verify path
     * re-saves (and re-activates) a device the user just removed. Deliberately narrower than
     * [disconnect]: touches only connection state, never the run or workout state, so forgetting
     * a strap mid-run behaves like a plain dropout (#110: a sensor going away never ends a run).
     */
    fun forgetDevice(address: String) {
        // Before the target check: a pending promotion must never outlive the user forgetting
        // the strap — a late onServicesDiscovered would otherwise re-save it as active right
        // after removeDevice (Codex P2 #123).
        if (promoteOnVerifyAddress == address) promoteOnVerifyAddress = null
        if (targetDeviceAddress != address) return
        logBleDecision("forget_device", "Releasing forgotten device address=$address")
        targetDeviceAddress = null
        isReconnecting = false
        // Supersede any queued connect and close under the same lock, so an in-flight
        // connect coroutine can't re-establish the strap right after this teardown.
        synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                synchronized(gattConnectLock) {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }
        _hrState.update { it.copy(connectionStatus = "Disconnected", bpm = 0) }
        // The smoothed reading is the Run's, so it is told the Strap is gone rather than having
        // the number taken out from under it.
        postRunEvent(RunEvent.HeartRateLost("Disconnected", System.currentTimeMillis()))
    }

    private fun stopScanning() {
         ++scanEpoch // cancels any pending scan-timeout for the scan being stopped
         if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
             Log.d(TAG, "stopScanning() - stopScan invoked")
         }
    }

    // Serializes the close-old/connect-new handoff and makes the LAST requested connect win.
    // Two connect requests in quick succession (auto-connect effect vs a Manage Devices tap,
    // or a double-tap in the device list) each launch an IO coroutine; without this, their
    // close()/connectGatt() steps can interleave — leaking a live GATT whose callbacks keep
    // firing for a strap nobody asked for, unreachable by disconnect().
    private val gattConnectLock = Any()
    @Volatile private var connectRequestSeq = 0

    // Address of the strap an explicit user tap chose, pending promotion to active when its HR
    // service verifies; consumed by onServicesDiscovered only on an exact address match.
    // Background connects (auto-connect, reconnects, retries) null it rather than set it.
    @Volatile private var promoteOnVerifyAddress: String? = null

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // A silent return here would dead-end the Acquisition without ending it: no retry, no
            // scan timeout, and a status still reading "Connecting" — so Promotion would stay
            // earned forever (0beef0f). Say why; that publish is the release.
            _hrState.update { it.copy(connectionStatus = "Permission Missing") }
            return
        }

        isReconnecting = false
        val deviceName = if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name ?: device.address
        } else {
            device.address
        }
        _hrState.update { it.copy(connectionStatus = "Connecting to $deviceName...") }

        // Mission: Robust Bluetooth - Close old connection and connect on IO
        val mySeq = synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            synchronized(gattConnectLock) {
                if (mySeq != connectRequestSeq) {
                    Log.d(TAG, "Skipping superseded connect request #$mySeq (latest=$connectRequestSeq)")
                    return@launch
                }
                bluetoothGatt?.close()
                bluetoothGatt = null

                Log.d(TAG, "Connecting to GATT on IO thread...")
                // connectGatt() is non-blocking (results arrive on the callback), so holding the
                // lock across it is safe.
                bluetoothGatt = device.connectGatt(this@HrForegroundService, false, gattCallback)
            }
        }
    }
    
    private fun attemptReconnect() {
         if (targetDeviceAddress == null) return

         // A live run retries forever — a dropout never ends or freezes a run (#110). With no run,
         // the endless loop served no one: a strap left in a drawer kept the record screen on
         // "Looking for your strap…" indefinitely (the terminal "Strap not found" state existed in
         // the footer but was keyed to a status string that only lived for milliseconds). Give up
         // after a few attempts and land on the stable, actionable state instead; the footer's
         // Retry button and START itself both re-acquire from there.
         val status = _hrState.value.sessionStatus
         val runActive = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
         if (!runActive && reconnectAttemptCount >= PRE_RUN_RECONNECT_MAX_ATTEMPTS) {
             Log.d(TAG, "Giving up pre-run reconnect after $reconnectAttemptCount attempts")
             targetDeviceAddress = null
             isReconnecting = false
             // The chase is over. Publishing the terminal status ends the Acquisition, which is
             // what releases the Promotion — no thread hop needed, unlike the hand-rolled demote
             // this replaces (4fe74cd).
             _hrState.update { it.copy(connectionStatus = "Strap not found") }
             return
         }

         isReconnecting = true
         val delayMs = reconnectDelay

         reconnectAttemptCount++
         _hrState.update { it.copy(connectionStatus = "Reconnecting in ${delayMs/1000}s...") }

         serviceScope.launch {
             delay(delayMs)
             reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
             val addr = targetDeviceAddress
             if (addr != null) {
                 // Retry via the device overload directly: the String overload is the fresh-connect
                 // entry point and zeroes reconnectAttemptCount/reconnectDelay, which made the
                 // attempt counter reset every cycle — the give-up cap above could never trip.
                 val device = bluetoothAdapter?.getRemoteDevice(addr)
                 if (device != null) connectToDevice(device)
             }
         }
    }

    fun disconnect() {
        targetDeviceAddress = null
        // Supersede any queued connect and close under the same lock (see forgetDevice).
        synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                synchronized(gattConnectLock) {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }
        // No sessionStatus write here: disconnecting is no longer stopping (#110). Every current
        // caller runs when no run is live (STOP ends the Run itself; FORCE_SCAN is blocked
        // mid-run), and the old unconditional STOPPED write was a landmine — any future mid-run
        // caller would have silently killed the run and orphaned its DB row.
        //
        // Nor any of the Run's fields, which this used to blank as well. They belong to the Run and
        // reach the published state through one write; a Run that is over publishes the blanks
        // itself, and a Run that is not over must not have them taken from underneath it.
        _hrState.update { it.copy(connectionStatus = "Disconnected", bpm = 0) }
        // The Strap is gone, so the coach must not keep reasoning about its last reading.
        postRunEvent(RunEvent.HeartRateLost("Disconnected", System.currentTimeMillis()))

        reconnectAttemptCount = 0
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val deviceAddress = gatt?.device?.address ?: ""
                // A delayed connect from a GATT that forgetDevice()/startScanning()/a superseding
                // connect already abandoned must not publish "Connected", discover services, or
                // subscribe — it would keep feeding HR from a strap the user just forgot or
                // replaced (Codex P2 #123). Those paths clear or repoint targetDeviceAddress
                // before closing the old GATT, so a mismatch here is always stale: close it and
                // bail before touching any state. Every legitimate connect sets the target first.
                if (deviceAddress != targetDeviceAddress) {
                    Log.d(TAG, "Ignoring stale STATE_CONNECTED for $deviceAddress (target=$targetDeviceAddress)")
                    serviceScope.launch(Dispatchers.IO) {
                        synchronized(gattConnectLock) {
                            gatt?.close()
                            if (bluetoothGatt == gatt) bluetoothGatt = null
                        }
                    }
                    return
                }

                reconnectDelay = 3000L
                isReconnecting = false
                reconnectAttemptCount = 0

                // The strap is a sensor, not the run's gate (#110): connecting only reports the
                // sensor, it never starts a run or opens a DB record. START owns that now. A run
                // that is already going (including reconnecting after a dropout) simply keeps its
                // status; a bare connect with no run leaves the session IDLE.
                _hrState.update { it.copy(connectionStatus = "Connected") }

                // GPS is not started here any more. It is the Run's to ask for, and the Run asks
                // once its row id has landed — which is exactly the ordering this had to spell out
                // by hand (mode pinned at START, and the id committed, or a fast connect would
                // start GPS early and clip the route's beginning off the map, Codex P2 #123).
                // A reconnect mid-Run finds location already running; LocationTracker.start() is a
                // no-op when it is.

                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                gatt?.discoverServices()

                // A bare sensor connect (pre-run pairing) doesn't need — or deserve — a
                // Promotion; only a Run does. Publishing "Connected" above ends the Acquisition
                // and says so (4fe74cd).
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (targetDeviceAddress != null) {
                    // Heart rate can't gate the middle of a run any more than it gates the start
                    // (#110): a strap dropout leaves the run RUNNING and merely stops zone cues.
                    // The elapsed clock, distance, pace and the plan's intervals keep advancing;
                    // only the (HR-driven) coaching goes quiet until the strap reconnects. We keep
                    // retrying in the background, but a lost strap never freezes or ends the run.
                    //
                    // The Run is told the reading is gone rather than sent a zero, so the outage is
                    // banked as no-data instead of being fabricated from the last packet — and so a
                    // dropout can never be mistaken for something the coach should reason about.
                    _hrState.update { it.copy(
                        connectionStatus = "Disconnected (Retrying)",
                        bpm = 0,
                    ) }
                    postRunEvent(
                        RunEvent.HeartRateLost("Disconnected (Retrying)", System.currentTimeMillis())
                    )

                    serviceScope.launch(Dispatchers.IO) {
                        gatt?.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        attemptReconnect()
                    }
                } else {
                     // Intentional disconnect (no target to chase). Session status is untouched:
                     // a sensor going away is never a run ending (#110).
                     _hrState.update { it.copy(connectionStatus = "Disconnected") }
                     serviceScope.launch(Dispatchers.IO) {
                        gatt?.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                     }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(HEART_RATE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                
                if (characteristic != null) {
                    // Mission: Post-Connection Persistence - Save as active ONLY when HR service is verified
                    gatt?.device?.let { device ->
                        val deviceName = if (ActivityCompat.checkSelfPermission(this@HrForegroundService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            device.name ?: "Unknown"
                        } else "Unknown"
                        val deviceAddress = device.address
                        // Persist only the strap still being chased. forgetDevice() and every
                        // superseding path (a new connect, startScanning) clear or repoint
                        // targetDeviceAddress before closing the old GATT, but its callbacks can
                        // still land afterwards — and even a makeActive=false save would re-add a
                        // just-forgotten strap to the saved list (Codex P2 #123). A closed GATT's
                        // discovery has no business persisting anything.
                        if (deviceAddress != targetDeviceAddress) {
                            Log.d(TAG, "Ignoring stale onServicesDiscovered for $deviceAddress (target=$targetDeviceAddress)")
                            return@let
                        }
                        // Promote to active ONLY the strap an explicit Connect tap chose. No
                        // fallback terms: currentSettings is an async DataStore snapshot that can
                        // lag the user's latest selection, so "already active" / "nothing active"
                        // read from it could re-promote a strap the user just replaced or forgot
                        // (Codex P2 #123). Neither fallback is needed — saveDevice(makeActive =
                        // false) leaves the active preference untouched for an already-active
                        // strap, and every first-pairing path is an explicit tap that sets
                        // promoteOnVerifyAddress.
                        val makeActive = deviceAddress == promoteOnVerifyAddress
                        if (makeActive) promoteOnVerifyAddress = null
                        serviceScope.launch {
                            settingsRepository.saveDevice(deviceAddress, deviceName, makeActive)
                        }
                    }

                    if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                handleHeartRate(value)
            }
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
        
        // 1. Clean up Bluetooth precisely
        stopScanning()
        targetDeviceAddress = null
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        
        // 2. Kill all background loops and threads
        serviceScope.cancel() 
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        sessionHandlerThread?.quitSafely()
        sessionHandlerThread = null
        
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
