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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.routes.OffCourseWatch
import com.example.runningapp.routes.courseToWatchFlow
import com.example.runningapp.run.Acquisition
import com.example.runningapp.run.AcquisitionContext
import com.example.runningapp.run.AcquisitionEffect
import com.example.runningapp.run.AcquisitionEvent
import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.AcquisitionState
import com.example.runningapp.run.CueTag
import com.example.runningapp.run.SCAN_UNAVAILABLE
import com.example.runningapp.run.ScannedStrap
import com.example.runningapp.run.RowSettlement
import com.example.runningapp.run.RunAtLastDispatch
import com.example.runningapp.run.settlementOfRowAwaited
import com.example.runningapp.run.RunLostToTeardown
import com.example.runningapp.run.RunRescueClaim
import com.example.runningapp.run.beginARun
import com.example.runningapp.run.runLostToTeardown
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
import com.example.runningapp.run.StartRunRequest
import com.example.runningapp.run.runRouteSetOutOn
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.AfterRunWorker
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunPause
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.data.averagePaceMinPerKm
import com.example.runningapp.data.settleRunRow
import com.example.runningapp.diagnostics.RunHeldFor
import com.example.runningapp.diagnostics.RunJournal
import com.example.runningapp.diagnostics.RunJournalEvent
import com.example.runningapp.diagnostics.RunJournalWatch
import com.example.runningapp.diagnostics.JournaledState
import com.example.runningapp.foreground.ForegroundPromotion
import com.example.runningapp.foreground.PromotionHost
import com.example.runningapp.foreground.SCOPE_DRAIN_PASSES
import com.example.runningapp.foreground.TeardownGate
import com.example.runningapp.foreground.drainChildren
import com.example.runningapp.foreground.runMayBeGivenWork
import java.time.ZoneId

// Exactly the Run's lifecycle, under the screen's older names — [RunLifecycle.asSessionStatus] is
// the only thing that writes it, so there is no value here a Run cannot be in.
enum class SessionStatus { IDLE, RUNNING, PAUSED, STOPPING, STOPPED }

/**
 * Whether the Run is still taking down seconds.
 *
 * The one reading of "recording", kept beside the states it reads, because two places draw
 * conclusions from it and they must not be allowed to differ: the Run Journal, which calls the
 * crossing out of it a stop, and [com.example.runningapp.run.runLostToTeardown], which calls a
 * teardown that arrives while it is still true a Run lost. A second copy of this rule is how the
 * journal would come to say a Run was stopped that the teardown then rescued as unstopped.
 */
val SessionStatus.isRecording: Boolean
    get() = this == SessionStatus.RUNNING || this == SessionStatus.PAUSED
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

    // Detached from serviceScope on purpose: finalizing a Run must survive onDestroy() cancelling
    // serviceScope when a run is stopped from the background. It carries the Run's own last writes
    // — the totals, the moving time, the record book, the coach's evaluation — and nothing that
    // outlives the process, which is WorkManager's (see AfterRunWorker).
    private val finalizationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The run's per-sample writes (HR samples, GPS track points) live on their own scope so
    // stopRun() can wait for exactly these inserts to land before snapshotting the DB to
    // Downloads — otherwise the backup can race the tail writes and capture a run missing its
    // final seconds. Like finalizationScope, it survives onDestroy() so a background stop still
    // flushes the tail before the snapshot.
    private val recorderWriteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Wait for every second the Run has recorded to be in the database.
     *
     * Both ways a Run's totals can be written take this wait, and take the same one: the finalize,
     * which must not stamp an end time onto a row whose last seconds are still queued (#84), and the
     * rescue of a Run a teardown left recording, which rebuilds those totals by reading the very
     * rows in question (#309). Written once, because two copies of it would be two answers to what
     * "the Run's last second" means.
     *
     * A drain rather than a join of the children as they stand ([drainChildren]): the wait is not
     * over until the scope is empty. Both callers run after the Run they are finishing is over, but
     * "over" is decided by producers that stop on a bounded promise — a session thread joined with
     * a timeout, a location looper asked to quit safely and never joined — so a fix or an event
     * that was already queued can still land a write behind a snapshot taken here. The scope is
     * shared with the Run's own finalize and that is not a conflict but the point: whichever of the
     * two is waiting waits for every write the Run made, not only for the ones it started.
     */
    private suspend fun awaitRecorderWrites(): Boolean {
        val drained = drainChildren(recorderWriteScope.coroutineContext.job)
        if (!drained) {
            Log.w(TAG, "Recorder writes were still arriving after $SCOPE_DRAIN_PASSES passes; not waiting further")
        }
        // Carried back rather than only logged: a teardown about to take a row away has to know
        // whether anything can still be writing to it (#314, [settlementOfRowAwaited]).
        return drained
    }

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

    /**
     * Whether this service has begun going down, and with it whether the Run may still be given new
     * work (#315).
     *
     * **A gate, not a wait, and that is the whole of the point.** The teardown finishes a Run it
     * took, and before it does so it drains the scopes the Run's writers run on
     * ([awaitRecorderWrites], [settleAfterTeardown]). A drain closes the gap where a late arrival
     * lands *while the drain is waiting*: it looks again, and again, until a pass finds the scope
     * empty. It cannot close the gap where the scope goes empty and a producer that is still alive
     * launches something afterwards — an empty pass ends the drain, and no number of passes can
     * prove a producer will never produce again.
     *
     * Neither producer is stopped definitively. The session inbox is joined with a timeout
     * ([SESSION_THREAD_JOIN_TIMEOUT_MS]), so a long message can outlive the join and dispatch again;
     * [LocationTracker.shutdown] calls `quitSafely` and never joins, so a fix already on that queue
     * can still run and launch a track-point write. What this flag adds is the thing a wait cannot
     * be: from the moment it is set, a producer that is still alive is *refused*, so an empty scope
     * is proof rather than an observation.
     *
     * Set on main at the very top of `onDestroy`, before anything is stopped, so nothing can slip
     * between the decision to go down and the gate closing. It is read on every other thread this
     * service owns — the session inbox, the GPS callback's thread, and the coroutines of the
     * teardown itself.
     *
     * **Reading the flag is not the same as being refused, which is why the flag lives in
     * [TeardownGate] and not here.** A producer that reads `false` here and is then descheduled can
     * resume after the flag has flipped, after the drains have had their empty pass, and after the
     * rescued row has been settled — and its launch lands behind all of it. So the gate hands out
     * the decision and the registration of the work as one step under one monitor
     * ([TeardownGate.registerWorkForTheRun]), and flips the flag under that same monitor
     * ([TeardownGate.beginTeardown]): afterwards a producer has either already registered, where
     * the drains see it, or is refused. This property is the plain read, kept for the one refusal
     * that only wants to turn a piece of work away before it is begun — [dispatchRunEvent] — where a
     * stale answer costs at most work a later refusal discards.
     *
     * **What it must not refuse is the finish already under way.** A background STOP finalizes and
     * *then* takes the service down, so this teardown is running while the Run's own last writes are
     * legitimately being made — and the teardown's own delivery of a held buffer
     * ([endRunAwaitingItsRow]) is a whole Run's seconds arriving after this is set. A gate that
     * dropped those would cost a real Run its recording to fix a rescue's rounding. So the writes
     * that belong to a finish already under way say so where they are launched
     * (`deliveringHeldWork`, `finishingTheRun`), rather than the gate trying to work out who is
     * calling it.
     *
     * The Run's own finalize is in that category and was for a while refused anyway, on the
     * reasoning that the teardown's rescue would write the row in its place — which is true only of
     * a Run the teardown finds still recording, and a background STOP is not one (#382). The row
     * then had no writer at all. Keeping one writer per row is the settling write's own business —
     * it writes only where the row is still unsettled ([SETTLE_RUN_ROW_IF_UNSETTLED]) — and not
     * something this flag decides.
     *
     * Separate from [destroyed], which is a narrower fact about one resource: that one says the GATT
     * sweep has been made, and is read by a connect deciding whether to close its own handle.
     */
    private val teardownGate = TeardownGate()
    private val teardownBegun: Boolean get() = teardownGate.teardownBegun
    private var bluetoothAdapter: BluetoothAdapter? = null

    /**
     * The adapter switching on or off, told to the Acquisition as an event (#221).
     *
     * Everything else it needs to know about Bluetooth it asks for on a tick; this is the one thing
     * that has to arrive, and ADR 0007 says why. What travels is the broadcast's own `EXTRA_STATE`
     * rather than a fresh read of the adapter, which is behind BLUETOOTH_CONNECT and answers "on"
     * when it is refused — this needs no permission.
     *
     * `TURNING_OFF` counts as off so the GATT is hung up while the adapter can still do it. The
     * `OFF` that follows is the repeat the rule takes as a no-op.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val on = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
                BluetoothAdapter.STATE_ON -> true
                // TURNING_ON is not on yet, and anything unrecognised is not news. Either way the
                // state that follows says so plainly.
                else -> return
            }
            Log.d(TAG, "Bluetooth adapter reported ${if (on) "on" else "off"}")
            postAcquisitionEvent(AcquisitionEvent.BluetoothStateChanged(on))
        }
    }

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
     * The queue tickets for the cues of the Run that is on, which the end of the Run hands back
     * (#53, #220). Every cue the app speaks is enqueued here, so this is the one place that has to
     * keep them — no producer keeps bookkeeping of its own.
     */
    private val outstandingCues = OutstandingCues()

    // Mission 4: Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationTracker: LocationTracker? = null
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

    /**
     * The row id the Run's insert produced, as the insert itself saw it (#314).
     *
     * The Run learns its id through [RunEvent.RunRowCreated] on the session inbox, and that is the
     * only place the id belongs while there is a service to run it. This is for the one moment
     * there is not: a teardown quits the inbox, so an insert that completes after it has nowhere to
     * post to, and the row it made would be a row nobody in the process knows the number of.
     *
     * `@Volatile` rather than the thread discipline above, because there is no one thread to give
     * it to: the insert coroutine writes it from IO, the session thread clears it as each Run's
     * insert is dispatched, and the teardown reads it from main.
     *
     * Cleared as each Run is started and before that Run is published, so it can only ever name
     * the Run being recorded now ([beginARun]). [RunEffect.CreateRunRow] is emitted once per Run,
     * which is what makes that true — and clearing it there rather than when the insert is
     * dispatched is what makes it true for a teardown, which reads this only after reading a
     * published state.
     */
    @Volatile
    private var insertedRunRowId: Long? = null

    /**
     * The claim on the Run's held work, taken by whichever side is about to deliver it (#360).
     *
     * The rule the two sides obey is stated once, on the event the loser is told by
     * ([RunEvent.HeldWorkTakenOver]). This is the mechanism: a compare-and-set, won by one.
     *
     * A compare-and-set rather than either side inferring from the other's liveness. #314
     * inferred — the teardown delivered only when its bounded join of the session thread said the
     * thread had stopped — and a join can run out for any reason at all, so a teardown that stood
     * down for a thread doing something else entirely left nobody to deliver the buffer (#360).
     *
     * The insert was the other candidate for owning this, and it cannot: it knows the id first but
     * it cannot reach the buffer. The buffer is in [runState], which belongs to the session thread
     * by the thread discipline this whole file rests on, and the insert runs on IO. An owner has
     * to be a side that can deliver, and the two sides that can are the two that hold the claim.
     * What the insert can do it already does — name the id it produced in [insertedRunRowId], so
     * that a teardown has one to deliver against.
     *
     * Reset as each Run is started, in the same place and for the same reason as
     * [insertedRunRowId]: the claim is about the Run being recorded now, and a claim left standing
     * from the last Run would have this one's buffer refused delivery by both sides.
     */
    private val heldWorkClaim = AtomicBoolean(false)

    /**
     * The claim on rescuing this Run, taken by whichever of its two settlers gets there first
     * (#382).
     *
     * What it decides — who pays for a rebuild and who tells the runner their Run stopped recording
     * — and what it deliberately no longer decides, which is who writes the row, are stated once on
     * [RunRescueClaim]. This is where the two settlers meet it: [finalizeRun] takes it as the Run's
     * own finalize is performed, and the teardown takes it in each of the two places it would
     * otherwise rebuild the Run itself ([endRunLostToTeardown], [endRunAwaitingItsRow]).
     *
     * It is not taken in [runTakenByThisTeardown] alongside the held-work claim, though that is where
     * it would sit most tidily. A teardown that took this claim while reading the Run would take it
     * even when the reading turns out to be *no Run to settle* — which is exactly what an ordinary
     * background STOP looks like from here, the Run already published as STOPPED — and would then be
     * telling a runner who stopped their own Run that it stopped recording. It is taken where a
     * rebuild is about to be paid for and nowhere else.
     *
     * Reset as each Run is started, in the same place and for the same reason as [insertedRunRowId]
     * and [heldWorkClaim].
     */
    private val rescueClaim = RunRescueClaim()

    /**
     * What this teardown found of the Run, and whether the Run's held work is now its to deliver
     * (#309, #314, #360).
     *
     * One method because the three steps in it only answer the question in this order, and two of
     * the orderings are wrong in ways a comment at a call site would not stop the next editor
     * making.
     *
     * **Wait for the session thread first.** After [Looper.quit] no further run event will ever be
     * dispatched — the queue is dropped and [sessionHandler] is gone — so at most one dispatch can
     * still be running, the one that was in flight. A dispatch computes its outcome before it
     * publishes it, so a teardown reading during one reads a Run as it was a moment ago and would
     * miss the second that dispatch is adding. Waiting for the thread to stop is what makes the
     * reading below the Run's last word rather than its second-to-last. The wait is bounded, and
     * in practice already over: the whole of the Bluetooth sweep has happened since the first
     * join. A wait that runs out anyway still reads — most of a Run beats none of it — and what it
     * can then miss is one dispatch's worth at the tail.
     *
     * **Read before claiming.** The side that loses the claim lets its buffer go, and the session
     * thread's letting go is published like everything else it does. A teardown that claimed first
     * and read second could find the buffer it had just won already published as empty and deliver
     * nothing at all.
     *
     * @param sessionThread the thread the Run's inbox ran on, already asked to quit.
     */
    private fun runTakenByThisTeardown(sessionThread: HandlerThread?): RunLostToTeardown? {
        sessionThread?.join(SESSION_THREAD_JOIN_TIMEOUT_MS)
        val runAtTeardown = runAtLastDispatch
        return runLostToTeardown(
            runAtTeardown,
            heldWorkTakenHere = heldWorkClaim.compareAndSet(false, true),
        )
    }

    /**
     * The Run as its last dispatch left it, for the teardown to read (#314).
     *
     * Published out of [publishRun] the way the Run's state is, and for the same reason: the
     * teardown is not on the session thread, so it cannot read [runState] itself — that field is
     * guarded by the thread that owns it, and the teardown's join of that thread is bounded
     * ([SESSION_THREAD_JOIN_TIMEOUT_MS]), so a join that times out is a read with nothing ordering
     * it. `@Volatile` is that ordering.
     *
     * All three of the teardown's inputs together in one value, because a teardown that read them
     * separately would be reading two different moments: see [RunAtLastDispatch]. One write, one
     * read, and nothing between them to interleave with.
     */
    @Volatile
    private var runAtLastDispatch: RunAtLastDispatch = RunAtLastDispatch.NONE

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

    // The course the runner picked on the record screen, and which way round (#56). Held for the
    // life of the process and never written to settings, exactly as the Workout pick above is: a
    // course is chosen for one Run, and one that outlived the tap would send the runner out on
    // yesterday's route without their asking. [NO_ROUTE_ID] is "no course picked".
    @Volatile private var pickedRouteId: Long = NO_ROUTE_ID
    @Volatile private var pickedRouteReversed: Boolean = false

    /**
     * The Run's course, watched (#58) — null for a Run following none, and for a routed Run in the
     * moment between START and the course being read out of the library.
     *
     * Volatile because the fixes arrive on the tracker's own thread and this is replaced from a
     * coroutine. Nothing here decides anything: [OffCourseWatch] does, and this file speaks what it
     * says.
     */
    @Volatile private var offCourseWatch: OffCourseWatch? = null

    /** Keeps [offCourseWatch] up with the library while the Run goes on — see [courseToWatchFlow]. */
    private var courseWatchJob: Job? = null

    private lateinit var database: AppDatabase

    /**
     * What this service will still be able to say about a lost Run tomorrow (#310).
     *
     * Taken from the container rather than built here: the file outlives any one service, and the
     * writes go to a thread that outlives this one — so a line recording a teardown is not cancelled
     * by the teardown it records.
     */
    private lateinit var runJournal: RunJournal

    /**
     * The half of the journal that is derived rather than called: the Run's lifecycle and the Strap
     * are read off what has just been published, so no future call site can forget to write a line.
     *
     * Touched only from [sessionHandlerThread] — see [journalPublishedState].
     */
    private lateinit var runJournalWatch: RunJournalWatch

    /** One journal line, against whichever Run is live. */
    private fun journal(event: RunJournalEvent, detail: String? = null) {
        runJournal.write(event, _hrState.value.activeDbSessionId, detail)
    }

    /**
     * Which Run the Promotion is being held for, so the hand-back can be named after it (#310).
     *
     * Nothing but the `demoted` line reads this — see [RunHeldFor] for why the live Run alone
     * cannot answer it, and for why a Promotion, unlike a Strap, never has to be told it has begun.
     */
    private val promotionRun = RunHeldFor()

    /**
     * Journal whatever the publish that just happened changed (#310).
     *
     * Called from the two inboxes, on [sessionHandlerThread], immediately after each has published
     * — deliberately not by collecting [_hrState]. A collector would fail this ticket twice over.
     * It would run on `serviceScope`, which `onDestroy` cancels, so the pause or stop published as
     * the service goes down — the shape of #309 exactly — could lose its line to the very teardown
     * it was recording. And [_hrState] is a StateFlow, so it conflates: a pause and the resume
     * after it, landing between two turns of the collector, would arrive as one emission saying
     * nothing had changed, and neither line would ever be written.
     *
     * Reading it here has neither problem. Every publish is seen, in the order it was published,
     * on the thread that published it, and the line is handed to a writer that outlives this
     * service ([RunJournal]).
     */
    private fun journalPublishedState() {
        val state = _hrState.value
        promotionRun.observe(state.activeDbSessionId)
        runJournalWatch.observe(
            JournaledState(state.sessionStatus, state.activeDbSessionId, state.acquisition.phase)
        )
    }

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

        /**
         * Where the app says a Run stopped recording without being stopped (#309).
         *
         * Its own channel rather than the Promotion's, for both halves of what a channel is: the
         * runner can silence a live Run's ongoing notification without silencing the one thing the
         * app has to interrupt them for, and this one is at high importance so it makes a sound —
         * the Promotion's, which is posted and reposted every few seconds, must not.
         */
        const val LOST_RUN_CHANNEL_ID = "RunStoppedRecordingChannel"

        /** Not [NOTIFICATION_ID]: that one goes away with the service this is announcing. */
        const val LOST_RUN_NOTIFICATION_ID = 2
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
        // The Route the runner picked for this Run, and which way round (#56). Carried on the same
        // intents as the Workout pick, and for the same reason: both are chosen on the record screen
        // and both have to be in force before the Run's configuration is pinned.
        const val EXTRA_ROUTE_ID = "EXTRA_ROUTE_ID"
        const val EXTRA_ROUTE_REVERSED = "EXTRA_ROUTE_REVERSED"
        // "No course picked", as it travels on an intent. An intent extra cannot carry a null Long,
        // and a Route's id is autogenerated from 1, so nought is a value no Route can ever have.
        const val NO_ROUTE_ID = 0L
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
        // Refused outright once the teardown has begun (#315), and before the claim below is
        // touched. A dispatch already inside this method when the gate closed runs to its end — it
        // is work in flight, which the drains are for; this refuses a dispatch that *begins*
        // afterwards, which no drain can wait for. The one that can still arrive is a message the
        // session thread was already running when its bounded join ran out
        // ([SESSION_THREAD_JOIN_TIMEOUT_MS]), and what it would do is exactly what the teardown is
        // about to do from the other side: a STOP dispatching here would launch a second finalize
        // for the row the rescue is rebuilding. Which of those two settles the row is no longer left
        // to whichever lands last — the settling write only writes an unsettled row (#382,
        // [SETTLE_RUN_ROW_IF_UNSETTLED]) — but a dispatch begun after the gate shut would still
        // republish and re-journal a Run the teardown has already accounted for. Refused here, that
        // question never arises.
        //
        // Before the held-work claim, because that claim is a compare-and-set and losing it is a
        // decision. A
        // refusal that spent the claim first would take the buffer from the teardown and then not
        // deliver it, and the Run's held seconds would be nobody's (#360).
        if (!runMayBeGivenWork(teardownBegun)) {
            Log.w(TAG, "The service is being torn down; not dispatching $event to the run")
            return
        }
        // The one thing decided before the Run sees the event, because it is not the Run's to
        // decide: whether this inbox is the side that hands the Run's held work over (#360). The
        // id is the event that empties the buffer, so this is the moment to take the claim — and a
        // teardown that took it first turns the arrival into the one the Run drops its buffer on
        // instead of emitting it.
        //
        // An id addressed to a Run that is not the one being recorded now reaches neither (#365).
        // It is dropped rather than handed on, because the claim is what it would touch first and
        // the claim belongs to this Run's buffer: spending it on the last Run's late answer would
        // leave this Run's own id to find it gone, and the delivery refused by both sides. The Run
        // refuses such an id on its own reading of the same address ([RunState.isTheRunStartedAt]),
        // so dropping it here changes nothing about the Run — it only keeps a foreign arrival from
        // republishing and re-journalling this Run under a clock that is not its own.
        if (event is RunEvent.RunRowCreated && !runState.isTheRunStartedAt(event.forRunStartedAtMillis)) {
            Log.w(
                TAG,
                "Row ${event.runRowId} belongs to an earlier run; " +
                    "the run being recorded now is not taking it"
            )
            return
        }
        val toDispatch =
            if (event is RunEvent.RunRowCreated && !heldWorkClaim.compareAndSet(false, true)) {
                Log.w(TAG, "A teardown took run ${event.runRowId}'s held work; not delivering it here")
                RunEvent.HeldWorkTakenOver(event.runRowId, event.forRunStartedAtMillis, event.nowMillis)
            } else {
                event
            }
        val outcome = Run.onEvent(runState, toDispatch)
        runState = outcome.state
        // Retired here, with the state and before the publish, rather than when the insert is
        // dispatched (#314). A Run becomes observable to a teardown at [publishRun], and its
        // effects run after it, so an id cleared inside [createRunRow] would still be the last
        // Run's for the whole of the window in between — long enough for a teardown whose join of
        // this thread timed out ([SESSION_THREAD_JOIN_TIMEOUT_MS]) to read the new Run as awaiting
        // its row and settle the old Run's row in its name.
        if (outcome.effects.beginARun()) {
            insertedRunRowId = null
            // The new Run's buffer is nobody's yet. Reset with the id and for the same reason: a
            // claim left standing from the last Run would leave this one's held work refused by
            // both sides (#360).
            heldWorkClaim.set(false)
            // And nobody has this Run to rescue yet, for the same reason again: a claim left
            // standing from the last Run would tell this one's teardown that a finalize was on its
            // way when none is, and a Run genuinely taken from its runner would be neither rebuilt
            // nor spoken about (#382).
            rescueClaim.releaseForANewRun()
        }
        publishRun(runState, toDispatch.nowMillis)
        // Between the publish and the effects: the journal describes what is now true, and it must
        // say so before an effect can act on it — a stop's own effects end in the demote, and a
        // journal that read the state afterwards would file the two in the wrong order.
        journalPublishedState()
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
        // The teardown's copy of the same reading, taken here and from this same [RunState] so the
        // two can never disagree (#314). The trio is what a teardown asks of the Run, and it is
        // published as one value because a teardown that read the parts separately would be asking
        // about two different moments — see [runAtLastDispatch].
        runAtLastDispatch = RunAtLastDispatch(
            status = run.lifecycle.asSessionStatus(),
            liveRunRowId = if (live) run.runRowId else null,
            heldWork = run.pendingRowEffects,
        )
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
     *
     * Coaching being off is asked first, because it is the settled answer: the window empties
     * whenever the Strap goes away (ADR 0011), and a dropout is no reason to stop saying "Off".
     */
    private fun RunState.coachWaitingLine(nowMillis: Long): String = when {
        !controls.coachingEnabled -> "Off"
        heartRate.recent.isEmpty() -> "Ready"
        coaching.band == ZoneBand.IN || coaching.band == ZoneBand.UNKNOWN -> "Ready"
        else -> "Next: ${coaching.ladder.secondsUntilNextCue(nowMillis)}s"
    }

    /**
     * Everything the Run asked for, each mapped to one call.
     *
     * No branching and no state of its own, deliberately: if this ever needs a decision, the
     * decision belongs in [Run], where it can be tested. That is why this has no seam of its own
     * and is verified on the phone instead.
     *
     * [deliveringHeldWork] says this effect belongs to a finish already under way rather than being
     * new work for the Run — the teardown handing over a buffer whose seconds were recorded before
     * the service began going down ([endRunAwaitingItsRow]). It is the one thing the teardown gate
     * lets past, and it is said here rather than worked out from a flag, because the difference is
     * about *which Run's work this is* and not about which thread happens to be calling (#315).
     * Carried rather than decided, so this still branches on nothing.
     */
    private fun perform(effect: RunEffect, deliveringHeldWork: Boolean = false) {
        when (effect) {
            is RunEffect.CreateRunRow -> createRunRow(effect)
            is RunEffect.FinalizeRun -> finalizeRun(effect)
            is RunEffect.SaveHrSample -> saveHrSample(effect, deliveringHeldWork)
            is RunEffect.SaveIntervalStat -> saveIntervalStat(effect, deliveringHeldWork)
            is RunEffect.SavePause -> savePause(effect, deliveringHeldWork)
            is RunEffect.Speak -> speakCue(effect)
            is RunEffect.WithdrawCue -> withdrawCue(effect.tag)
            is RunEffect.Notify -> updateNotification(effect.text)
            RunEffect.StartGps -> startGps()
            RunEffect.StopGps -> stopGps()
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
        locationTracker?.beginRun()
        // And the one place the course this Run is watched against is taken up, for the same reason
        // (#58). Ended and cleared first and unconditionally: an unrouted Run following a routed one
        // must not still be measured against yesterday's course.
        watchTheCourse(effect.ranAlongRouteId, effect.ranAlongRouteReversed)
        // Clear the HR-freshness clock so age is measured within this Run. A strapless Run started
        // after one that had HR would otherwise inherit a stale timestamp, read as a huge
        // lastHrAgeSeconds, and trip the screen's sensor-lost warning on a Run deliberately started
        // without a Strap (#110). The live reading goes with it, in lock-step: with the clock reset
        // a stale packet would look fresh. A real packet repopulates both within a second.
        lastHrTimestamp = 0L
        _hrState.update { it.copy(bpm = 0, distanceKm = 0.0, paceMinPerKm = 0.0) }
        // Where the runner's clock was when they pressed START (#304), read here on the way in and
        // not inside the write below. The write is dispatched to IO and runs at some later moment,
        // so a zone read there is a reading of whenever the insert got a thread — a phone that
        // crossed a border, or landed, between START and the insert would stamp the Run with a zone
        // the runner was never in when they set off. An observation of the device has to be taken
        // when the thing being observed happened.
        val ranAtUtcOffsetSeconds = utcOffsetSecondsAt(
            effect.startedAtMillis,
            ZoneId.systemDefault(),
        )
        // Not through [recordForTheRun], and so not refused by the teardown gate (#315). Every other
        // write adds to a Run that exists; this one is the Run existing, and refusing it would cost
        // a Run its whole recording rather than its last second. It cannot in fact be reached after
        // the gate closes — it is launched from a dispatch, and dispatches are refused — but it is
        // left outside on the strength of what a refusal here would mean, not of that.
        recorderWriteScope.launch {
            val runRowId = database.sessionDao().insertSession(
                RunnerSession(
                    startTime = effect.startedAtMillis,
                    targetZone = effect.targetZoneNumber,
                    runMode = effect.runModeSettingValue,
                    includeInAiTraining = effect.includeInAiTraining,
                    // The Reserve this Run is being recorded under, written down with the Run
                    // rather than left to be guessed at afterwards (#228).
                    bandedOnMaxHr = effect.hrProfile.maxHr,
                    bandedOnRestingHr = effect.hrProfile.restingHr,
                    // The Stage this Run counts towards, written down at the start of it rather
                    // than worked out at the finish, by which time it can have moved (#234).
                    ranUnderStageId = effect.ranUnderStageId,
                    // And which of the Stage's Workouts it is, which is how history is later
                    // asked when the runner last ran their Test (#292).
                    ranUnderWorkoutId = effect.ranUnderWorkoutId,
                    // And the course it set out to follow, which is what makes it a routed Run
                    // (#56).
                    ranAlongRouteId = effect.ranAlongRouteId,
                    ranAlongRouteReversed = effect.ranAlongRouteReversed,
                    // Read from the phone rather than carried on the effect, because it is an
                    // observation of the device and not a decision the rulebook made — taken above,
                    // before this write was dispatched.
                    ranAtUtcOffsetSeconds = ranAtUtcOffsetSeconds,
                )
            )
            Log.d(TAG, "Started DB Session: $runRowId (Mode: ${effect.runModeSettingValue})")
            // Before the post, not after it: a teardown racing this must find the id whether or not
            // there is still an inbox for the event to reach (#314).
            insertedRunRowId = runRowId
            postRunEvent(
                RunEvent.RunRowCreated(
                    runRowId = runRowId,
                    // Which Run's id this is, taken from the request rather than from whatever Run
                    // is live by the time it lands (#365).
                    forRunStartedAtMillis = effect.startedAtMillis,
                    nowMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun saveHrSample(effect: RunEffect.SaveHrSample, deliveringHeldWork: Boolean) {
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
        recordForTheRun("a heart-rate sample", deliveringHeldWork) {
            database.sampleDao().insertSample(sample)
        }
    }

    /**
     * Launches one of the Run's own writes onto [recorderWriteScope], unless the teardown has closed
     * the gate on new work for the Run (#315).
     *
     * The one door for the writes that *add to* a Run — its samples, its Pauses, its interval stats,
     * its track points — which is what makes an empty [recorderWriteScope] proof that the Run is
     * finished with rather than an observation that it was, for that instant, quiet. A drain can
     * wait out a write that has started; only a refusal can answer a producer that is still alive
     * and has not started one yet.
     *
     * [deliveringHeldWork] is the exception the gate is built around, and the only one: the teardown
     * hands over a buffer of seconds the Run recorded *before* the service began going down, and
     * refusing those would cost a real Run its whole recording to fix a rescue's rounding. The
     * teardown holds the claim on that buffer when it delivers ([runTakenByThisTeardown]), so
     * nothing else can be writing the same seconds.
     *
     * The Run's insert does not come through here, deliberately. It is the one write whose refusal
     * would cost a Run its existence rather than its last second, and the teardown already has an
     * answer for a Run whose row is still on its way ([endRunAwaitingItsRow]). The finalize does not
     * either: it is the finish, not work added to the Run, and it runs on [finalizationScope]. It
     * still registers with the same gate ([TeardownGate.registerWorkForTheRun], `finishingTheRun`) so
     * that the drains can see it — it is simply never refused, which is a distinction #382 was filed
     * to restore.
     */
    private fun recordForTheRun(
        what: String,
        deliveringHeldWork: Boolean = false,
        write: suspend () -> Unit,
    ) {
        // Asked and answered in the same breath as the launch, under the gate's monitor (#315).
        // The two cannot be separate statements here: a producer that read the gate open and was
        // then descheduled would resume after the teardown had flipped the flag and after
        // [awaitRecorderWrites] had seen this scope empty, and its insert would be a second the
        // rescue rebuilt the Run's totals without. Registered under the monitor, the write is
        // either a child the drains wait for or it never exists.
        val registered = teardownGate.registerWorkForTheRun(deliveringHeldWork) {
            recorderWriteScope.launch { write() }
        }
        if (!registered) {
            Log.w(TAG, "The service is being torn down; not writing $what for the run")
        }
    }

    /** Write down one Pause of this Run, so an Export can state where its clock stopped (#328). */
    private fun savePause(effect: RunEffect.SavePause, deliveringHeldWork: Boolean) {
        val row = RunPause(
            sessionId = effect.runRowId,
            startTimeMillis = effect.pause.startedAtMillis,
            endTimeMillis = effect.pause.endedAtMillis,
        )
        recordForTheRun("a pause", deliveringHeldWork) { database.runPauseDao().insertPause(row) }
    }

    private fun saveIntervalStat(
        effect: RunEffect.SaveIntervalStat,
        deliveringHeldWork: Boolean,
    ) {
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
        recordForTheRun("an interval stat", deliveringHeldWork) {
            database.runWalkIntervalStatDao().insertIntervalStats(listOf(row))
        }
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
        // Taken here, and it no longer decides who writes the row (#315, #382).
        //
        // The history matters, because two answers have already been wrong here. The dispatch that
        // emits this can straddle the teardown gate — a session-thread message already inside
        // [dispatchRunEvent] when the gate closed runs to its end, and it can outlive the bounded
        // join that follows — so this finalize can start after the drains in [settleAfterTeardown]
        // have had their empty pass. #315's named harm was both settlers writing the row, with the
        // totals going to whichever landed last.
        //
        // The first answer was to refuse this finalize outright once the teardown had begun, and it
        // lost Runs: an ordinary background STOP publishes STOPPED before it performs its effects,
        // the promotion follower reads that publish on main and demotes, `stopSelf()` and all, so
        // `onDestroy` can shut the gate while this dispatch is still walking its effects — and the
        // teardown that follows reads a Run that is no longer recording, which is not a Run it
        // settles anything for ([runLostToTeardown] answers null for STOPPED). Refused here, the Run
        // had *no* writer at all.
        //
        // The second answer was this claim: whichever settler took it wrote the row, and the other
        // stood down. It lost Runs too, one level further in. A teardown takes the claim before it
        // knows whether it can rebuild anything, and for a Run with no reconstructable seconds — a
        // short strapless treadmill Run — the rescue writes nothing; this finalize, having found the
        // claim gone, had already stood down for good. An `endTime = 0` row with nobody left to
        // finish it, which is the very Run #382 exists to stop losing.
        //
        // So the exclusion is not here any more. **The Run's row is settled by the write that finds
        // it unsettled** ([SETTLE_RUN_ROW_IF_UNSETTLED]), and this finalize goes and writes whatever
        // the claim says. What the claim is still good for is stated on it: it keeps a teardown from
        // paying for a rebuild and telling the runner their Run stopped recording when the Run's own
        // finish is already on its way. Taking it and not using it now costs a message; it can no
        // longer cost a Run.
        rescueClaim.takenHere()
        // The Run is over, so there is no longer a runner to be off anything (#58). Ended here as
        // well as at the next Run's start, so a phone sitting idle after a Run is not still holding
        // a query open on the library.
        watchTheCourse(routeId = null, reversed = false)
        val runRowId = effect.runRowId
        val totals = effect.totals
        val distanceKm = locationTracker?.getDistanceKm() ?: 0.0
        // The run's totals, not LocationTracker's live pace - that is a rolling 15-second window,
        // so reading it here stored the pace of someone standing still at the finish (#163). The UI
        // derives pace this same way on read, which is what fixes runs already in history; the
        // column is written from the same function so the two can never drift apart.
        val avgPace = averagePaceMinPerKm(totals.durationSeconds, distanceKm)
        val startLocation = locationTracker?.getFirstLocation()

        // finalizationScope, not serviceScope: a background STOP (a notification action with the
        // activity unbound) reaches stopSelf() -> onDestroy -> serviceScope.cancel() on the next
        // main-loop message, and a launch not yet dequeued dies before its body — NonCancellable
        // cannot protect a coroutine that never starts.
        // Registered through the gate, and never refused by it (#315, #382). The two halves of that
        // are separate on purpose and neither is incidental.
        //
        // *Never refused*, because the finalize is the finish already under way — the category the
        // gate is documented never to turn away — and refusing it is what lost the Run above. Which
        // of the two settlers may write the row is answered by the claim at the top of this method,
        // not here; the gate is about new work being added to a Run, and a finish adds nothing.
        //
        // *Registered all the same*, because permission is not what this registration buys: the
        // monitor is what makes this launch a child of [finalizationScope] before
        // [TeardownGate.beginTeardown] can return. [settleAfterTeardown] drains that scope before it
        // reads anything back, so a finalize registered here is one the teardown waits out; a
        // finalize launched outside the gate could appear on the scope after the drains' empty pass,
        // and the teardown would then read a record still being written — which is the very thing
        // #315's atomic registration exists to stop.
        val finalizing = teardownGate.registerWorkForTheRun(finishingTheRun = true) {
            finalizationScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Let the Run's still-queued sample and track-point inserts land before the row is
                    // stamped as finished. An end time is what everything downstream reads as "this Run
                    // is complete" — the history snapshot below, and the GPX export, which offers Share
                    // the moment it sees one (#84). Stamping first would let a runner who shares
                    // straight after stopping export the Run minus its final seconds.
                    awaitRecorderWrites()

                    // Read after that wait and not before it, and it stays there (#317). The wait can
                    // last seconds with the feel sheet on screen throughout, and the Walk mark, the
                    // effort, the note and a stated distance are all the runner's to write in that
                    // window. Read beforehand, a Walk ticked into the sheet during the wait was
                    // silently undone by this write, and the settlement below then judged the Run off
                    // the `isWalk = false` it had just restored — a Stage graduated on a walk, which
                    // cannot be taken back.
                    //
                    // The settling write no longer carries the whole row, which is what made the
                    // timing of this read load-bearing: it names the columns a settler measured and
                    // leaves every other one alone ([SETTLE_RUN_ROW_IF_UNSETTLED]), so the runner's
                    // edits are safe from it whenever they land. The read stays all the same. It is
                    // what says there is a row here to finish at all, and it is what the copy below
                    // is built from — a copy that is the *settler's* answer for this Run and is
                    // deliberately taken as late as the settling itself.
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
                        effortScore = totals.effortScore,
                        walkBreaksCount = totals.walkBreaks,
                        isRunWalkMode = totals.isRunWalkMode,
                    )
                    // The settling write, and the whole of the mutual exclusion between this and the
                    // teardown's rescue of the same row (#382). It writes if the row is still
                    // unsettled and says whether it did; nothing was decided about that beforehand,
                    // because nothing but the row can say it.
                    //
                    // In the ordinary case this is the write that lands, and the Run keeps the
                    // totals it banked as it ran: a teardown's rescue runs behind the drains in
                    // [settleAfterTeardown], which wait this finalize out. In the race that is left
                    // — a teardown whose bounded joins gave up, whose rescue rebuilt the Run from
                    // its record and wrote first — the row keeps the rescue's rebuilt totals and
                    // this returns false. That is the acceptable outcome of the two: rebuilt totals
                    // are a true account of the seconds that reached the database, they are already
                    // on disk with the Run's after-run work done off them, and the alternative is
                    // this write going over the top of them, which is the two-writer harm #315 was
                    // filed about.
                    if (!database.sessionDao().settleRunRow(updatedSession)) {
                        // Everything below belongs to the settler that won the row — the journal
                        // line, the after-run measurements — and it is doing them, or has.
                        //
                        // The Plan's settlement is the exception, because this branch holds the one
                        // fact the winner could not have (#383). The winner is a rescue, and it
                        // marks every Run it puts back as owing the Plan nothing
                        // ([finishedFromRecord]) — right for a Run nobody closed, wrong for this
                        // one, whose runner closed it, which is why there is a finalize here at
                        // all. The question goes back rather than being answered here — and the
                        // rescue pays it as soon as its measurements are in, in this process rather
                        // than at the next cold start (#386). The rule and every reason for it are
                        // on [HAND_THE_STAGE_QUESTION_BACK].
                        Log.w(TAG, "Run $runRowId was settled by a teardown's rescue; leaving it that way")
                        try {
                            sessionRepository.handTheStageQuestionBack(runRowId)
                        } catch (e: Exception) {
                            // The same guard the settlement below carries, and for the same reason:
                            // this runs as a root child of [finalizationScope], whose SupervisorJob
                            // keeps a failure from the siblings but does not handle it. Logged and
                            // left — the Run keeps the rescue's mark and its Stage is lost, which is
                            // no worse than it was before this branch existed.
                            Log.w(TAG, "Could not hand run $runRowId's Stage question back; it keeps the rescue's mark", e)
                        }
                        return@withContext
                    }

                    // Against the Run's own id rather than the live one, which this stop has already
                    // cleared. A Run journaled as stopped but never as finalized is a Run whose totals
                    // never reached its row — which is what an interrupted Run looks like from here.
                    // Read that way, the line has to be on disk before this coroutine can be lost with
                    // the process, and run-finalized is one the journal waits out for itself (#310).
                    runJournal.write(
                        RunJournalEvent.RUN_FINALIZED,
                        runRowId,
                        "duration=${updatedSession.durationSeconds}s"
                    )

                    // The Downloads snapshot of the history the Run now belongs to, and the Run's
                    // weather, handed to WorkManager rather than launched here (#122). A STOP from the
                    // notification ends with this service taking itself down, and Android is free to
                    // reclaim the process straight after — before a coroutine launched here had a turn.
                    // Room never lost by that, but the Downloads copy could stay a Run behind until the
                    // next run finished, and a runner who cleared their storage in between would get
                    // yesterday's history back.
                    //
                    // Booked the instant the Run is written down, ahead of the measuring and scoring
                    // below rather than after them, because until WorkManager has the request the
                    // window this closes is still open — and those take seconds of GPS arithmetic and
                    // a network call. What the snapshot may then miss is a moving time or a medal,
                    // both of which are re-derived from the Run itself by the passes at launch; what
                    // it can no longer miss is the Run.
                    try {
                        AfterRunWorker.enqueue(applicationContext, runRowId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not book the after-run work for $runRowId", e)
                    }

                    // Measured, scored, put to the Segments and shaped — only now the track-point
                    // inserts above have landed, so every one of them sees the whole Run. One call, and
                    // the order is inside it: the rescue of a Run a teardown left recording owes the
                    // same four, and two copies of an order are two things free to drift apart
                    // ([AfterRunMeasurements]).
                    //
                    // Nothing here may throw: the Run is already saved by this point, and
                    // finalizationScope carries no exception handler, so a failure allowed out would
                    // take the process down and strand the backup, weather fetch and plan evaluation
                    // below it. Each pass is guarded inside, and each failure is left in the state a
                    // launch pass already looks for.
                    val movingTime = sessionRepository.afterRun(runRowId)

                    Log.d(
                        TAG,
                        "Finalized DB Session: $runRowId. Evidence: duration=${updatedSession.durationSeconds} moving=$movingTime"
                    )

                    // Everything the Plan has to say about this Run — the app's graduation rule, and
                    // then the coach — asked by name rather than by handing the row over (#297).
                    //
                    // The row is deliberately not passed. The feel sheet has been on screen since STOP
                    // and it carries the Walk mark: the runner's own word, and the one fact that
                    // withdraws a Run from the judgement entirely. A Run judged off this copy is judged
                    // before that word can arrive, which is a Stage graduated on a walk. So this call
                    // finds the sheet still open and leaves the settlement to it, and settles here and
                    // now only for a Run no sheet was shown for — a STOP from the notification. Either
                    // way the Run keeps the debt until a settlement returns, so a process reclaimed in
                    // between leaves it to the launch pass rather than losing the graduation for good.
                    //
                    // Nothing about the Stage, the Run Type, testing mode or AI sharing is decided here:
                    // each is asked once, inside the rule or inside the coach's own path, and asking
                    // again here would be the same rule in two places free to drift apart.
                    // Guarded like the moving-time and record-book calls above it, and for the same
                    // reason: this runs as a root child of [finalizationScope], whose SupervisorJob
                    // keeps a failure from the siblings but does not handle it, so a database error
                    // here would reach the default handler and take the app down. Logged and left —
                    // the Run keeps its debt, and the launch pass is what pays it.
                    Log.d("AiCoach", "Settling the stage after session finalization for run: $runRowId")
                    try {
                        sessionRepository.settleStageForRun(runRowId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not settle the Stage for run $runRowId; it keeps the debt", e)
                    }
                }
            }
        }
        // Never false, and checked because of what it would mean if it ever were. The finish is the
        // one thing the gate does not refuse (#382), and by this line the settlement claim has been
        // spent — so a registration that *did* refuse would leave a Run whose own totals never
        // reach its row, and a teardown that had asked for the claim first would already have stood
        // its rescue down. The row would not be lost for good: it is unsettled, so the launch pass
        // has it. But the Run would lose the totals it banked as it ran and be rebuilt from its
        // record instead, silently. An editor who puts the finalize back behind the rule finds this
        // line rather than a Run quietly demoted to its own wreckage.
        if (!finalizing) {
            Log.e(
                TAG,
                "Run ${effect.runRowId}'s finalize was refused registration; its own totals will " +
                    "never reach its row. Only the launch pass can finish it now."
            )
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
            // The Stage in force as START was pressed, which is the Stage this Run is evidence for
            // whatever happens to the plan while it runs (#234). Not conditioned on the Workout:
            // a Run that skipped today's plan was still run under the Stage the runner is in, and
            // that is already what the setting reads as ([UserSettings.activeStageId]) — so the
            // Stage written down is the one the card named and the Workout came from, and a Run
            // with no plan attached still records none.
            ranUnderStageId = settings.activeStageId,
            // The course picked on the record screen, put to the rule that says whether a Run in
            // this mode may follow one at all (#56).
            route = runRouteSetOutOn(
                runMode = runMode,
                routeId = pickedRouteId.takeIf { it != NO_ROUTE_ID },
                reversed = pickedRouteReversed,
            ),
        )
    }

    /**
     * The mode the tap that sent [intent] was aiming at.
     *
     * Read from the intent where it says, and from settings only where it does not, because the
     * settings write behind a Treadmill/Outdoor tap is asynchronous and the tap that follows it can
     * beat it here. Asked in one place because both actions that begin a Run have to answer it the
     * same way: a Run is a Run whether the Strap is real or invented, and the two disagreeing meant
     * the Simulate button quietly recorded a different Run from the one START would have.
     */
    private fun runModeAskedFor(intent: Intent): RunMode =
        RunMode.ofSettingValue(intent.getStringExtra(EXTRA_RUN_MODE) ?: currentSettings.runMode)

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

        // First thing, so a service that then falls over on its way up has said it existed (#310).
        runJournal = appContainer.runJournal
        runJournalWatch = RunJournalWatch(runJournal)
        journal(RunJournalEvent.SERVICE_CREATED)

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
            onFixForCourse = ::onFixForCourse,
            onRawFix = { location, barometerPressureHpa, startsAfterPause ->
                // The Run's row id off its published state: the tracker's thread has no business
                // reading the Run, and the Run has no business knowing what a fix is.
                val sessionId = _hrState.value.activeDbSessionId
                if (sessionId != null) {
                    // Written down as offered. The tracker is reused between runs, but it is told
                    // where a run begins and clears the mark there, so a run's opening fix carries
                    // one only when a pause really did come before it (#195).
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
                        startsAfterPause = startsAfterPause
                    )
                    // Gated: LocationTracker.shutdown() quits its thread safely and never joins
                    // it, so a fix already queued can reach here after the teardown's drain has
                    // ended — the very arrival no wait can answer (#315).
                    recordForTheRun("a track point") {
                        database.trackPointDao().insertTrackPoint(trackPoint)
                    }
                }
            }
        )
        
        // Mission: Dedicated Session Thread
        sessionHandlerThread = HandlerThread("SessionTrackingThread").apply { start() }
        sessionHandler = Handler(sessionHandlerThread!!.looper)

        // After the inbox exists, because the receiver posts to it: a broadcast landing between the
        // two would be the one piece of news the Acquisition never hears (#221). Not exported —
        // ACTION_STATE_CHANGED is the system's to send, and nothing else may fake one.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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
        // The two travel together and are read together, so a pick and its direction can never be
        // taken from different taps.
        if (intent?.hasExtra(EXTRA_ROUTE_ID) == true) {
            pickedRouteId = intent.getLongExtra(EXTRA_ROUTE_ID, NO_ROUTE_ID)
            pickedRouteReversed = intent.getBooleanExtra(EXTRA_ROUTE_REVERSED, false)
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
                startRun(runModeAskedFor(intent))
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
                // Simulation starts a Run of its own, so the same deferral applies — and the same
                // reading of the mode. A Simulate tap made straight after an Outdoor tap used to
                // start from whatever the settings still said, so a Run the runner had just aimed
                // outdoors was recorded on a treadmill and dropped the course they had picked for
                // it, and the inverse carried a course onto a Run they had aimed indoors.
                val simulationStartedRun = setSimulationEnabled(enabled, runModeAskedFor(intent))
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
    fun enqueueCue(text: String, priority: CuePriority, tag: CueTag? = null) {
        val manager = audioCueManager ?: return
        // Enqueued and recorded as one act, so the end of a Run cannot land between the two and
        // leave the cue outstanding with nothing left to take it back (#220).
        outstandingCues.record(tag) { manager.enqueue(text, priority) }
    }

    /** The Run's [RunEffect.Speak], under the name the Run gave the cue, if it gave one. */
    private fun speakCue(effect: RunEffect.Speak) =
        enqueueCue(effect.text, effect.priority, effect.tag)

    /**
     * Begin watching this Run against the course it set out on, or stop watching entirely when it
     * set out on none (#58).
     *
     * Not the [Run]'s business, deliberately. The rulebook has no idea what a GPS fix is — it starts
     * and stops location updates and never reads one (ADR 0002) — and a course is a line the fixes
     * are measured against. What the Run does own is the fact that this Run was following a Route at
     * all, which is why the id arrives here on the Run's own effect rather than off
     * [pickedRouteId]: by the time the row is being made, a second tap could already have moved the
     * pick, and the course watched has to be the course written on the row.
     *
     * There is a window between START and the first course arriving, and nothing is done about it:
     * the alerts do not arm until the runner has been on the course anyway, and a Run that begins
     * standing on the start line has the whole of the walk to it in hand.
     */
    private fun watchTheCourse(routeId: Long?, reversed: Boolean) {
        courseWatchJob?.cancel()
        courseWatchJob = null
        offCourseWatch = null
        if (routeId == null) return
        courseWatchJob = serviceScope.launch {
            courseToWatchFlow(database.routeDao(), routeId, reversed).collect { offCourseWatch = it }
        }
    }

    /**
     * Stop location updates, and tell the course watch that the fixes have stopped keeping up with
     * the runner (#58).
     *
     * The two go together because a manual Pause comes through here: it tears the GPS stream down
     * entirely, so the next fix the watch sees can be minutes of standing still later. A ten-second
     * wait begun before the Pause would be long over by then, and the runner would be told they were
     * off course for something they did before they stopped. Symmetric with the same moment in
     * [LocationTracker.stop], which drops the distance baseline for the same reason.
     */
    private fun stopGps() {
        locationTracker?.stop()
        offCourseWatch?.recordingBroke()
    }

    /**
     * A fix has landed on a routed Run: say whatever the course has to say about it, if anything
     * (#58).
     *
     * Untagged, unlike the turnaround (#208): there is nothing to take one back for. A cue is
     * withdrawn when the Run moves on underneath it, and both of these are true the moment they are
     * made — the runner *is* off the line, or *is* back on it — so the worst a queue can do to one is
     * speak it a sentence late. The end of a Run sweeps out whatever is still waiting anyway (#220).
     *
     * [CuePriority.NAVIGATION], which is the top of the queue: a runner going the wrong way is going
     * further the wrong way for as long as a split announcement takes to finish. It still never cuts
     * one off mid-sentence — nothing in this app does (#53) — it goes to the front of what is
     * waiting.
     */
    private fun onFixForCourse(fix: LocationFix, autoPaused: Boolean) {
        val alert = offCourseWatch?.onFix(fix, System.currentTimeMillis(), autoPaused) ?: return
        Log.d(TAG, "Course alert: $alert")
        enqueueCue(alert.spoken, CuePriority.NAVIGATION)
    }

    /**
     * Take back a cue that has not been spoken: whatever it was going to say is no longer true
     * (#208). Asked for by the Run ([RunEffect.WithdrawCue]).
     *
     * Inert when there is nothing to take back, and inert in the queue when the cue has already
     * gone out — so no caller has to know which of those it is.
     */
    private fun withdrawCue(tag: CueTag) {
        val ticket = outstandingCues.takeBack(tag) ?: return
        audioCueManager?.withdraw(ticket)
    }

    /**
     * Take back every cue of the Run that has just ended (#220).
     *
     * A cue still waiting its turn belongs to a Run that is over: "start running, interval 3 of 6"
     * after the runner has stopped is an instruction with nothing left to instruct. The queue drops
     * nothing (#53), so this is the producer taking its own cues back — and the service is that
     * producer, because every cue in the app is enqueued through it.
     *
     * The sentence being said is untouched and finishes in full: a withdrawn ticket for a cue
     * already gone out is inert.
     */
    private fun withdrawRunCues() {
        // All of them in one act, and the bookkeeping held across it: taken back one at a time the
        // engine can finish its sentence between two of them and hand the next one out before its
        // withdrawal lands, and a cue recorded between the two steps would be left behind entirely.
        outstandingCues.takeBackAll { tickets -> audioCueManager?.withdrawAll(tickets) }
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
                journal(RunJournalEvent.PROMOTION_REFUSED, "${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            acquireWakeLock()
            journal(RunJournalEvent.PROMOTED)
            return true
        }

        override fun demote() {
            // Reached with nothing promoted too, when a refused promotion's start has to be
            // handed back: releaseWakeLock and stopForeground are both no-ops in that case, and
            // stopSelf is the whole point of the call.
            Log.d(TAG, "demote - dropping notification/wake lock, BLE untouched")
            // The line #310 exists for: a demote while a Run is still live is the whole of what
            // happened in #309, and nothing else on the phone would have recorded it an hour later.
            // Named after the Run the Promotion was held for, not merely the live one — on a normal
            // stop the live Run has already been cleared by the time the hand-back runs, and a
            // `demoted` naming no Run cannot be tied to the stop that caused it. The same rule the
            // Strap's release is named by, and kept in the one place both read it from: a line
            // names the Run it belongs to, falling back to the one it was held for (RunHeldFor).
            val state = _hrState.value
            runJournal.write(
                RunJournalEvent.DEMOTED,
                promotionRun.ends(state.activeDbSessionId),
                "status=${state.sessionStatus}",
            )
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Safe mid-finalize, and it takes three things to be so. The activity's binding keeps
            // the service alive while the app is on screen; the finalize coroutine runs on a scope
            // that survives destruction; and — the one this call actually races — the teardown that
            // follows does not refuse the finalize and does not settle the row behind it.
            //
            // That last one is not free, and it is the whole of #382. A background STOP reaches here
            // from the promotion follower, which runs on main off the *publish*, while the session
            // thread is still walking the same STOP's effects. So `onDestroy` can begin before the
            // finalize does. While the teardown refused a finalize it found in that window, this
            // line took the Run with it: nobody wrote the row. What makes it safe again is that the
            // finalize is never refused ([TeardownGate]) and that the two possible writers of the
            // row race for a claim rather than one being turned away ([RunRescueClaim]).
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

        // Every cue still waiting its turn belongs to the Run that has just ended, so all of them
        // come back — not just the halfway turnaround, which was the only one this used to take
        // (#220). Reached before the service is destroyed on a backgrounded STOP, which is what
        // leaves the engine with nothing to cut short as it goes.
        //
        // Called twice for a STOP that ends a live Run — once inline from [stopRun] and again on
        // the Run's own [RunEffect.ReleaseStrap] — and that is load-bearing rather than wasteful:
        // the Run's stop is performed on its own thread afterwards, so a cue emitted by the finish
        // is taken back by the second pass.
        withdrawRunCues()

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

    /**
     * What tapping a notification does: bring the app up.
     *
     * Both notifications this service posts do it — the Promotion's, which is where a runner
     * returns to a Run in progress, and the one that says a Run stopped recording, which is where
     * they go to check what was saved (#309). Distinct [requestCode]s so the two are separate
     * pending intents rather than one rewritten by whichever was posted last.
     */
    private fun openTheApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, HrForegroundService::class.java).apply {
            action = ACTION_STOP_FOREGROUND
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openTheApp(requestCode = 0))

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
            // Made here, at onCreate, rather than where it is used: the one place it is used is a
            // teardown, on a process that may be reclaimed in the next instant, and a notification
            // posted to a channel that does not exist yet is a notification nobody sees (#309).
            manager.createNotificationChannel(
                NotificationChannel(
                    LOST_RUN_CHANNEL_ID,
                    "Run stopped recording",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
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
     * Report that an effect could not be carried out — but only if nothing has been decided since.
     *
     * An effect that fails has to say so, or the phase it belongs to stands with nothing behind it.
     * It cannot say so where it stands, though, because it is running inside a decision, so the
     * word goes on the queue — where a request already waiting will be served first and make the
     * failure a lie about the wrong attempt. Two taps on the same Strap and a `GattDisconnected`
     * meant for the first would close the second's good connection.
     *
     * The attempt at the moment of the effect is the whole identity needed. Phases are immutable
     * and a fresh attempt is always a fresh one, so still holding the same instance means the
     * failure is still about the attempt that suffered it. The phase rather than the whole state,
     * because the state changes for reasons that supersede nothing: a `StrapSeen` left over from
     * the last scan only lengthens the results list, and dropping a startup failure for that would
     * leave the phase — and the wake lock with it — standing until the 60s deadline.
     *
     * Session thread only, both when called and when it runs.
     */
    private fun reportEffectFailed(event: AcquisitionEvent) {
        val asked = acquisitionState.phase
        sessionHandler?.post {
            if (acquisitionState.phase !== asked) {
                Log.d(TAG, "Dropping $event - the Acquisition has already moved past it")
                return@post
            }
            dispatchAcquisitionEvent(event)
        }
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
        journalPublishedState()
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
            bluetoothOn = bluetoothIsOn(),
        )
    }

    /**
     * Whether the adapter is on — asked in a way that cannot throw.
     *
     * `isEnabled` is behind BLUETOOTH_CONNECT from Android 12, and this is read for every event on
     * the thread the Run shares. A SecurityException here would take the Run's event loop with it,
     * on the exact tick whose job was to notice the permission had gone and stop cleanly.
     *
     * A refused read is answered true, because the honest answer is "unknown" and false is the one
     * thing we would be making up. Reporting it off puts "Bluetooth Off/Unavailable" on screen
     * over a permission the runner took away, and sends them to a switch that is already on. Every
     * rule that needs the adapter asks about the permission that guards it first, so an unknown
     * that is really off costs at most one attempt, which fails and ends in a block of its own.
     *
     * A scan, notably, needs no BLUETOOTH_CONNECT at all — so saying "off" here would have stopped
     * a scan that would have worked.
     */
    private fun bluetoothIsOn(): Boolean = try {
        bluetoothAdapter?.isEnabled == true
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not read the adapter state, assuming on: ${e.message}")
        true
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
        // Every way this can fail is told to the Acquisition rather than logged and dropped. The
        // phase is already Scanning by the time the effect runs, and Scanning is what the UI, the
        // 60s deadline and the Promotion all believe — so a scan that never started has to end
        // that phase, not leave it standing until the deadline.
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || !hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            reportEffectFailed(AcquisitionEvent.ScanFailed(SCAN_UNAVAILABLE))
            return
        }
        try {
            scanner.startScan(scanCallback)
            Log.d(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
            reportEffectFailed(AcquisitionEvent.ScanFailed(SCAN_UNAVAILABLE))
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
        // A connect that does not happen is reported as one that dropped, the same as doStartScan
        // reporting a scan that never started. The rules already know what to do with a drop —
        // back off, retry, eventually give up — and the alternative is a Connecting phase with
        // nothing behind it.
        fun didNotConnect(why: String) {
            Log.w(TAG, "connect_gatt did not open for address=$address: $why")
            reportEffectFailed(AcquisitionEvent.GattDisconnected(address))
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return didNotConnect("no BLUETOOTH_CONNECT")
        }
        val device = bluetoothAdapter?.getRemoteDevice(address)
            ?: return didNotConnect("no adapter, or not an address it knows")
        logBleDecision("connect_gatt", "Opening GATT to address=$address")
        // One handle per address, or the overwritten one is a GATT nothing can close — and a
        // callback from it would be indistinguishable from the new one's. The rules close before
        // they connect, so this is the map's invariant held here, not a path anyone takes.
        doCloseGatt(address, andDisconnect = true)
        // Platform type: Android returns null when it cannot get a GATT client, and the map would
        // throw on it — on this thread, which is the Run's too.
        val gatt = device.connectGatt(this, false, gattCallback)
            ?: return didNotConnect("no GATT client available")
        openGatts[address] = gatt
        // connectGatt() is a Binder call and can outlast onDestroy's bounded join, landing a
        // handle after the destruction sweep has already been and gone. The flag is set before
        // that sweep, so reading it after the map write is the whole ordering: false means the
        // sweep has not run yet and will find this, true means it has and will not.
        //
        // Closed by the handle rather than by looking the address up again: the sweep's clear()
        // can take this entry out from under us between the write above and the read here, and
        // then neither of us would close what we are both holding.
        if (destroyed) {
            openGatts.remove(address, gatt)
            releaseHandle(address, gatt, andDisconnect = true)
        }
    }

    private fun doCloseGatt(address: String, andDisconnect: Boolean) {
        val gatt = openGatts.remove(address) ?: return
        releaseHandle(address, gatt, andDisconnect)
    }

    /**
     * Let a handle go, having already taken it out of [openGatts].
     *
     * Takes the handle rather than the address because whoever removed it is the one who owns
     * closing it — looking it up again would be a second chance for something else to have taken
     * it, and then nobody closes it.
     */
    private fun releaseHandle(address: String, gatt: BluetoothGatt, andDisconnect: Boolean) {
        // close() needs no permission; disconnect() does. Gating both would drop the GATT from the
        // map and never close it — the leak this map exists to make impossible.
        val mayDisconnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        gattCloseScope.launch {
            // The handle is already out of the map, so this is its only chance to be closed —
            // hence finally. disconnect() can throw if the permission goes between the check above
            // and this coroutine running, and an exception escaping here would take the process
            // with it as well as leaking the handle.
            try {
                if (andDisconnect && mayDisconnect) gatt.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed for address=$address: ${e.message}")
            } finally {
                try {
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close failed for address=$address: ${e.message}")
                }
            }
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

    /**
     * A packet from the Strap, on the Run's own thread — [onSessionThreadIfCurrent] put it there,
     * and it is the only way in. So both answers below are dispatched directly rather than posted,
     * exactly as the simulated Strap does: a post would go to the back of this thread's queue,
     * behind a pulse already waiting there, and that pulse would bank its second against a reading
     * this packet has already overtaken.
     */
    private fun handleHeartRate(data: ByteArray) {
        if (isSimulationEnabled) return // Mission 3: Ignore real data during simulation
        // Whether this packet holds a heart rate at all is [bpmFromHeartRateMeasurement]'s to say,
        // once, here, so nothing downstream — the live number, the Run's tally, the Export — has to
        // know what a heart rate can be (#326).
        //
        // A packet holding none leaves the Run with no reading rather than merely unpublished. The
        // Strap is still connected and still talking, so nothing else would ever say the last beat
        // is stale, and every following second would bank that beat again as if it were current.
        // "Lost" is the app's existing word for exactly this — the reading is gone, the seconds
        // bank as no-data, and the window does not average across the gap. The moment is not aged
        // either: nothing was read, so the last reading is as old as it was.
        val bpm = bpmFromHeartRateMeasurement(data)
        if (bpm == null) {
            _hrState.update { it.copy(bpm = 0) }
            dispatchRunEvent(
                RunEvent.HeartRateLost(_hrState.value.connectionStatus, System.currentTimeMillis()),
            )
            return
        }

        val timestamp = System.currentTimeMillis()
        lastHrTimestamp = timestamp // Track for session engine age

        _hrState.update { it.copy(bpm = bpm) }
        // The reading itself is the Strap's to publish; what it means is the Run's. Every packet
        // goes to the Run, whether or not one is live — the Run's own guards decide whether the
        // coach so much as looks at it.
        dispatchRunEvent(RunEvent.HeartRateSampled(bpm, _hrState.value.connectionStatus, timestamp))
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
    fun setSimulationEnabled(enabled: Boolean, runMode: RunMode = RunMode.ofSettingValue(currentSettings.runMode)): Boolean {
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
        startRun(runMode)
        Log.d(TAG, "Simulation Mode ENABLED - started a run")
        return true
    }

    /**
     * What is done about a Run this service is being torn down out from under (#309).
     *
     * Two things, in the order they can be lost. Both a bounded moment into the teardown rather
     * than at the top of it, for the reason given at the call site. The runner is told first,
     * because that is a call on main that lands now, where the finish below is a coroutine on a
     * process Android may be about to reclaim — and of the two, being told is the one nothing else
     * will do later. The Run is then finished from the seconds it already wrote, which is the same
     * rescue the launch pass makes ([SessionRepository.rescueRunLostToTeardown]), taken now rather
     * than at some cold start that may be days away: this teardown can happen inside a process that
     * goes on living, and the pass will not look at a Run younger than the process it is running
     * in. Row 9133 sat unfinished for two hours with the app used throughout, which is the whole of
     * the ticket's second complaint.
     *
     * The waits the rescue needs before it can read a settled Run, and the scope it takes them on,
     * are [settleAfterTeardown]'s — shared with the Run that had no row yet, because the two are
     * waiting for the same thing.
     */
    private fun endRunLostToTeardown(runRowId: Long) {
        // The claim, taken before the runner is told anything and before a rebuild is paid for
        // (#382).
        //
        // The Run read here is one this teardown found still recording, and that reading can be a
        // beat old: a STOP dispatch publishes its state before it performs its effects, so the Run
        // whose snapshot says RUNNING may already have a finalize of its own walking down the
        // session thread with the totals it banked as it ran. Losing the claim says exactly that,
        // and there is then nothing here worth doing: rebuilding the Run from its record would cost
        // a dying process a walk of two tables to produce a worse answer, and telling the runner
        // their Run stopped recording would be false — they stopped it themselves.
        //
        // What this does *not* decide is who writes the row. It used to, and that is what lost the
        // Run this ticket is about: taken here and then handed to a rescue that turned out to have
        // nothing to rebuild, it left a row nobody settled, because the Run's own finalize had
        // already stood down on finding it gone. The write is its own guard now
        // ([SETTLE_RUN_ROW_IF_UNSETTLED]) and the finalize never stands down, so the worst this
        // claim can now get wrong is a message.
        if (!rescueClaim.takenHere()) {
            Log.w(TAG, "Run $runRowId's own finalize is settling its row; not rescuing it here")
            return
        }
        Log.w(TAG, "Service destroyed with run $runRowId still recording; finishing it from its record")
        notifyRunLostToTeardown(recordedSomething = true)
        settleAfterTeardown("finish run $runRowId") {
            if (sessionRepository.rescueRunLostToTeardown(runRowId)) {
                // The answer to the `service-destroyed` line above, which named this Run as still
                // recording: its totals reached its row after all. A reader who finds no such line
                // knows the Run is still owed one, and that the launch pass is who owes it (#310).
                runJournal.write(
                    RunJournalEvent.RUN_FINALIZED,
                    runRowId,
                    "rescued after the service was destroyed"
                )
            }
        }
    }

    /**
     * What is done about a Run this service is torn down out from under before its row landed
     * (#314, #361).
     *
     * Two Runs arrive here and they are the same shape: one the teardown took while it was still
     * recording, and one the runner had already stopped whose held finalize was still waiting for
     * an id (#361). Only the first is a loss; both are held work that no session inbox is left to
     * be given an id for, which is what this settles.
     *
     * The first is the same loss as [endRunLostToTeardown] caught a moment earlier, and it needs a
     * different answer because the Run's seconds are somewhere else. A Run with a row writes each
     * second to the database as it happens; a Run still waiting on its id holds them instead,
     * addressed to a row number that does not exist yet ([RunLostToTeardown.AwaitingItsRow]).
     * Nothing on disk can be read back for such a Run, so the held work is handed over here and
     * the ordinary rescue then reads what it wrote.
     *
     * The insert is waited out rather than cancelled. It runs on [recorderWriteScope], which the
     * teardown does not cancel and must not — that scope carries the tail writes of the very Run
     * being settled — and by the time this could reach for it the row is usually committed already,
     * so a cancel would be a race with two outcomes rather than a decision. Waiting has one: after
     * the wait in [settleAfterTeardown] the insert has either landed and named itself in
     * [insertedRunRowId], or it never will.
     *
     * What is then true of the Run decides the rest, and the outcomes are the ticket's complaints:
     *
     *  - The runner had stopped it. The held work includes the Run's own finalize
     *    ([RunLostToTeardown.AwaitingItsRow.runnerStopped]); performing it is the whole of the job,
     *    and nothing here rescues or discards behind it — those exist for a Run with no finish of
     *    its own, and rebuilding a Run whose own totals are already on their way is work for a worse
     *    answer (the row itself refuses the second write, #382, but the work is still wasted). It is
     *    the one answer for both readings of a stop: the state that says so may have been published
     *    already (STOPPING) or not yet, and either way the finalize is in the buffer and this is
     *    what delivers it.
     *  - The Run banked something. Its held seconds go to the row and the Run is put back from them
     *    ([SessionRepository.rescueRunLostToTeardown]), exactly as a Run with a row of its own is.
     *  - The Run banked nothing. The row is an empty `endTime = 0` row that no launch pass can ever
     *    rebuild, created after the service that asked for it was gone, so it is taken away again
     *    ([SessionRepository.discardRunThatRecordedNothing]).
     *
     * One window is left, and it is the one nothing in a dying process can close: if the drains
     * give up ([SCOPE_DRAIN_PASSES]) or the process is reclaimed before the insert lands, the row
     * appears with nobody left to settle it. A drain that gives up is at least known about, and
     * what is done about it is to leave the row rather than take it away ([settlementOfRowAwaited]):
     * a writer still going is a record about to exist, and deleting its row would be the tidying
     * up destroying the thing it was tidying around. That is the ticket's own empty row, surviving in the
     * case where nothing was going to survive — and it is why the launch pass leaves such a row
     * alone rather than finishing it as a Run of no seconds ([finishedFromRecord]).
     *
     * The held work is performed off the session thread, which nothing else in this file does. It
     * is safe only because this teardown *holds the claim* on that buffer, so the session thread
     * cannot be delivering it too — both delivering it would write every second the Run recorded
     * down twice ([RunLostToTeardown.AwaitingItsRow.mayBeSettledHere], [runTakenByThisTeardown]) — and
     * because these particular effects are builders: each maps
     * one held piece to one row and launches it onto [recorderWriteScope] or [finalizationScope],
     * both of which outlive the service by design. Nothing here touches [runState].
     */
    private fun endRunAwaitingItsRow(lost: RunLostToTeardown.AwaitingItsRow) {
        Log.w(
            TAG,
            "Service destroyed with a run whose row had not landed; " +
                "${lost.heldWork.size} held writes, runnerStopped=${lost.runnerStopped}"
        )
        if (!lost.mayBeSettledHere) {
            // The session inbox took the claim on this buffer first, so it is the side delivering
            // it and this is not (#360). Nothing will finalize the Run behind that delivery, so
            // the row stays unfinished and the launch pass has it — the same residue as a drain
            // that gave up. The runner is told the #309 way, which is the honest answer here: what
            // the buffer held is being written down, and what it amounts to is not this teardown's
            // to say.
            Log.w(TAG, "The session inbox took the run's held work first; leaving it to that thread")
            if (!lost.runnerStopped) notifyRunLostToTeardown(recordedSomething = true)
            return
        }
        // Said now, from what is held rather than from what the settling below makes of it: this
        // call lands here and now, and a coroutine on a process about to be reclaimed may not. A
        // Run the runner stopped is told nothing at all — it was not taken from them.
        if (!lost.runnerStopped) notifyRunLostToTeardown(lost.hasSomethingToSave)
        settleAfterTeardown("settle the run whose row had not landed") {
            val runRowId = insertedRunRowId
            if (runRowId == null) {
                // The insert never came back. Nothing was written and there is nothing to take
                // away; the absence of `run-row-created` is the whole of the story and the journal
                // says that already.
                Log.w(TAG, "The run's row never landed; nothing was recorded and nothing is left behind")
                return@settleAfterTeardown
            }
            // The line the session inbox would have written had it still been there to hear the
            // event. Written here so a reader is never left reasoning from an absence that is not
            // true: the row does exist, it simply arrived after the service.
            runJournal.write(
                RunJournalEvent.RUN_ROW_CREATED,
                runRowId,
                "landed after the service was destroyed"
            )
            // Past the teardown gate, and the only writes that go past it: these seconds were
            // recorded before the service began going down, and this teardown holds the claim on
            // the buffer, so nothing else can be writing them (#315).
            lost.heldWork.forEach { perform(it.toEffect(runRowId), deliveringHeldWork = true) }
            if (lost.runnerStopped) {
                // The Run's own finalize is on its way to the row with the totals it banked as it
                // ran. Nothing rebuilt from the record would be an improvement on those, and a
                // rescue racing them would decide the row by whichever landed last.
                Log.w(TAG, "Run $runRowId was stopped by the runner before its row landed; its own finalize has it")
                return@settleAfterTeardown
            }
            // The claim, taken here rather than up with the held-work claim (#382). Here, because
            // this is the first line past which this teardown would rebuild the Run itself. Above
            // it there was nothing to ask about: the branch that just returned is a Run whose own
            // finalize is in the buffer, and that finalize takes the claim when it is performed
            // ([finalizeRun]).
            //
            // Losing it here would mean a finalize reached this Run first — a STOP that straddled
            // the gate and emitted one before the branch above could read the buffer. Whether that
            // is reachable or merely conceivable, the answer is the answer everywhere else: the
            // Run's own totals beat anything rebuilt here, so there is nothing to rebuild and
            // nothing to discard. Not because the claim forbids the write — the row's own condition
            // is what decides that now ([SETTLE_RUN_ROW_IF_UNSETTLED]) — but because a rebuild
            // behind a live finalize is work for a worse answer.
            if (!rescueClaim.takenHere()) {
                Log.w(TAG, "Run $runRowId's own finalize is settling its row; leaving it alone")
                return@settleAfterTeardown
            }
            // The held work has just been queued, and both answers below read the record it makes.
            // Whether that wait ended because the writers were done decides what may be done with
            // an empty row: see [settlementOfRowAwaited].
            val drained = awaitRecorderWrites()
            when (
                settlementOfRowAwaited(
                    rescued = sessionRepository.rescueRunLostToTeardown(runRowId),
                    recorderWritesDrained = drained,
                )
            ) {
                RowSettlement.PUT_BACK -> runJournal.write(
                    RunJournalEvent.RUN_FINALIZED,
                    runRowId,
                    "rescued after the service was destroyed with its row still on its way"
                )
                RowSettlement.TAKEN_AWAY -> if (sessionRepository.discardRunThatRecordedNothing(runRowId)) {
                    runJournal.write(
                        RunJournalEvent.RUN_ROW_DISCARDED,
                        runRowId,
                        "the row landed after the service was destroyed and the Run had recorded nothing"
                    )
                }
                // Nothing was found to put back and somebody may still be writing, so the row is
                // left exactly as the teardown found it. The absence of a `run-row-discarded` line
                // is what says so, and the launch pass has the row.
                RowSettlement.LEFT_ALONE -> Log.w(
                    TAG,
                    "Run $runRowId recorded nothing that could be found, but its writers had not " +
                        "finished; its row stays for the launch pass"
                )
            }
        }
    }

    /**
     * The one way a Run the teardown took is settled, whatever settling it turns out to need.
     *
     * Written once because both settlings ([endRunLostToTeardown], [endRunAwaitingItsRow]) wait for
     * exactly the same thing, and two copies of that wait would be two answers to when a Run is
     * over. The waits and the scope are the whole of it; what to do once the Run is settled is the
     * caller's.
     *
     * On [finalizationScope] and under [NonCancellable] because `serviceScope` is already cancelled
     * by the time onDestroy gets here, and even were it not, a launch onto it that is not yet
     * dequeued dies before its body ever runs.
     *
     * Two waits, and both are about reading a settled Run.
     *
     * A finalize already in flight is waited out first. What the teardown acts on is what the
     * service knew when the destroy began, and a STOP is published from the session thread a moment
     * after the Run itself has emitted its finalize — so a teardown landing in that window reads a
     * Run that is still RUNNING and whose totals are on their way to the row. Those totals are the
     * ones the Run banked as it ran and are better than any rebuilt from the record, so this waits
     * for them and the rescue then declines the row it finds already finished
     * ([SessionRepository.rescueRunLostToTeardown]). Waiting is what makes that check decisive
     * rather than a read that a concurrent write can overtake. The scope is drained rather than
     * snapshotted and joined ([drainChildren]) — the wait is over when the scope is empty, not when
     * the finalizes that happened to be running at the first look are done — and this leaves its
     * own job out, because a coroutine cannot wait for a set that includes itself.
     *
     * Then the Run's own tail writes ([awaitRecorderWrites]). A rescue reads the samples and fixes
     * back out of the database to rebuild the totals, so anything still queued would be a second
     * the Run recorded and the rescue did not count. It is the same wait the Run's row insert is
     * caught by, which is what makes [insertedRunRowId] readable by the time a caller looks (#314).
     *
     * Where onDestroy calls this from is what makes both waits short: after the session inbox has
     * been quit and joined and after the location thread and `serviceScope` have gone, almost
     * nothing is left that could start another finalize or queue another sample. Called any
     * earlier, the publish-then-perform order of [dispatchRunEvent] means the very STOP whose
     * RUNNING snapshot sent us here would routinely launch its finalize behind the settling, and
     * the row would end up holding whichever of the two wrote last. Called from where it is, that
     * STOP is in [finalizationScope]'s children, is waited out, and the decline is the answer.
     *
     * What the drains add is that neither wait depends on that quiescence being perfect, which it
     * is not: the session thread is joined with a timeout ([SESSION_THREAD_JOIN_TIMEOUT_MS]) and
     * the location looper is asked to quit safely and never joined, so a dispatch or a fix that was
     * already queued can still run and launch its write after the first look. A snapshot would miss
     * it; a drain looks again.
     *
     * @param what the job, named for the one log line that says it could not be done.
     */
    private fun settleAfterTeardown(what: String, settle: suspend () -> Unit) {
        finalizationScope.launch {
            // This coroutine's own job, taken here and not inside the NonCancellable below, where
            // `coroutineContext.job` is NonCancellable's and not a child of the scope at all. It is
            // what the drain leaves out: this settling is one of the finalize scope's children, and
            // a drain that joined itself would never return.
            val settling = coroutineContext.job
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    if (!drainChildren(finalizationScope.coroutineContext.job, except = settling)) {
                        Log.w(TAG, "Finalizes were still arriving after $SCOPE_DRAIN_PASSES passes; not waiting further")
                    }
                    awaitRecorderWrites()
                    settle()
                } catch (e: Exception) {
                    // Whatever is on disk stays as it is: an unfinished row is the launch pass's,
                    // and an empty one is no worse than the teardown found it. Nothing here is
                    // worth taking a dying process down for.
                    Log.w(TAG, "Could not $what after the teardown", e)
                }
            }
        }
    }

    /**
     * Tell the runner their Run stopped recording, out loud enough to be noticed (#309).
     *
     * The Run in #309 died mid-stride and the phone said nothing: no notification, no sound, and
     * the Promotion's own notification going away with the service is a thing disappearing rather
     * than a thing being said. It was caught by a glance at the screen 80 seconds later, and the
     * hour of running that followed was only saved because the runner happened to start a fresh Run.
     *
     * Its own channel at high importance, so it makes a sound and shows itself, and its own id so
     * it survives the Promotion's notification being taken down alongside the service. Dismissable
     * — it is news, not a control.
     *
     * It sends the runner to look, rather than telling them the Run is safe. Posted before the
     * rescue and not after it, because this call lands now and a coroutine on a process about to be
     * reclaimed may not — but that means it cannot promise an outcome it has not got yet. A Run
     * that recorded nothing at all is not put back ([finishedFromRecord]), and a runner told their
     * Run was saved would go looking through their history for something that was never there.
     */
    private fun notifyRunLostToTeardown(recordedSomething: Boolean) {
        val text = if (recordedSomething) {
            "The run wasn't stopped from the app. Whatever it recorded up to that moment is " +
                "being saved — tap to check your history."
        } else {
            // The #314 case with an empty buffer: the service went down within the first moments of
            // START, before the Run had banked a single second. There is nothing to save and
            // nothing to look for, and the runner is better told to start again than sent hunting
            // through their history for a Run that was never written down.
            "The run stopped before it had recorded anything, so there is nothing to save. " +
                "Start it again."
        }
        val notification = NotificationCompat.Builder(this, LOST_RUN_CHANNEL_ID)
            .setContentTitle("Your run stopped recording")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openTheApp(requestCode = 4))
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(LOST_RUN_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Nothing left to tell them with, and a teardown is not the place to make a fuss about
            // it. The Run is still being put back either way.
            Log.w(TAG, "Could not tell the runner their run stopped recording", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called - Clean Exit")

        // Before anything at all is stopped: from here on the Run is given no new work, so the
        // drains further down settle a set nothing can add to rather than a set nothing happened to
        // add to while they looked (#315). See [teardownBegun] for why a wait could not do this.
        //
        // This returns once the gate is shut AND every producer that had already passed it has
        // registered its work on a scope, because the two happen under one monitor — so from this
        // line on, there is no producer holding a stale `may give work` answer that a drain below
        // will not see. It waits for a `launch` at most, never for a write: what the monitor may
        // cover is stated on [TeardownGate], and blocking main for anything more here would be an
        // ANR rather than a fix.
        teardownGate.beginTeardown()

        // Android does not say why a service is being destroyed, so what goes down is what this
        // service knew at the moment the destroy began — whether a Run was still live, and whether
        // it still held the Promotion — and not a settled account of how it ended: the teardown
        // below has not run yet, and the session inbox may still be mid-message. A destroy with a
        // live Run and no demote above it is a Run the system took (#310).
        val stateAtTeardown = _hrState.value
        journal(
            RunJournalEvent.SERVICE_DESTROYED,
            "status=${stateAtTeardown.sessionStatus} promoted=${promotion.isPromoted} " +
                "bound=$isActivityBound"
        )
        // Written before any of the teardown below, and on disk by the time that write returns:
        // service-destroyed is an event the journal waits out for itself, because a destroy is
        // often followed straight away by the process being reclaimed (#310).

        // What is done about a Run this teardown took is decided below, once nothing can still be
        // working on that Run (#309), and from the last reading the Run itself published rather
        // than from this line's snapshot (#314): during the join below the session thread can
        // still finish a stop or take delivery of a row, and acting on a status that has since
        // been overtaken would tell the runner their Run was lost when it was not.

        // 0. Anything that opens a GATT from here on closes it itself; the sweep below is the
        // last one there will be. Set before the join, so a connect that outlasts it sees this.
        destroyed = true

        // 0b. Stop listening to the adapter before the inbox this posts to goes away. A broadcast
        // arriving after the thread has quit would be dropped anyway, but an unregister left undone
        // is a leaked receiver the framework complains about by name. Registration is unconditional
        // in onCreate, so this can only throw if destruction arrives without one — caught rather
        // than guarded by a flag that would say the same thing twice.
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bluetooth state receiver was never registered: ${e.message}")
        }

        // 1. Stop the Acquisition's thread before touching what it owns. quit() rather than
        // quitSafely(): a due ConnectRequested or retry tick would otherwise still run, and
        // opening a GATT after the sweep below leaks a handle with the service already gone.
        // join() waits out the one message that may be running right now, so the sweep is alone
        // with [openGatts] instead of racing it.
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        sessionHandlerThread?.quit()
        sessionHandlerThread?.join(SESSION_THREAD_JOIN_TIMEOUT_MS)
        // What this join is for is the sweep below — one message finishing before this thread is
        // alone with [openGatts]. It is not what decides the Run's held work: that is a claim
        // taken further down, because whether the thread is still going says nothing about
        // whether it is the side delivering the buffer (#360). The handle is kept for that
        // decision, which waits for this thread once more before it reads the Run
        // ([runTakenByThisTeardown]).
        val sessionThread = sessionHandlerThread
        sessionHandlerThread = null
        sessionHandler = null

        // 2. Clean up Bluetooth precisely. Destruction can be system-initiated and arrive with no
        // event loop left to run a decision on, so this reaches past [Acquisition] and closes what
        // is open directly — the same exception onDestroy already makes for the wake lock.
        doStopScan()
        val mayDisconnect = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        openGatts.values.forEach {
            // Same shape as doCloseGatt: this is the last chance any of these get, and a throw on
            // one must not cost the rest of them their close.
            try {
                if (mayDisconnect) it.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed during destroy: ${e.message}")
            } finally {
                try {
                    it.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close failed during destroy: ${e.message}")
                }
            }
        }
        openGatts.clear()

        // 3. Kill the remaining background loops
        serviceScope.cancel()

        locationTracker?.shutdown()

        // The Run the teardown took, read from the snapshot at the top and finished here (#309).
        // Here, and not up beside that snapshot, because this is the first point at which nothing
        // can start work on that Run any more: the session inbox is quit and joined, so no further
        // run event will be dispatched and no STOP will emit a finalize, and GPS and serviceScope
        // are down, so no further sample or track point will be queued. Neither of those stops is
        // definitive on its own — the join is bounded and the GPS thread is never joined at all —
        // which is what [teardownBegun] is for: both producers are refused from the top of this
        // method, so "nothing can still be working on this Run" is enforced rather than waited for
        // (#315). Everything the rescue does
        // is waiting for work in flight and then reading what that work left behind
        // ([endRunLostToTeardown]), and waiting only settles a set nothing can add to.
        //
        // One thing is deliberately not refused, and so is not covered by any of that: the Run's own
        // finalize, which a STOP dispatch already in flight can still launch after this gate shut
        // (#382). It is left through because refusing it left the row with no writer at all, and the
        // exclusion it needs is not a gate but a claim — the finalize and the rescue below race for
        // the right to settle the row, and the loser stands down ([RunRescueClaim]).
        //
        // A Run whose insert had not come back is the same loss arriving a moment earlier, and it
        // is settled here for the same reason (#314). All three of the things read of the Run come
        // from [runAtLastDispatch] rather than from [runState], which belongs to a thread this one
        // has only bounded its wait for — see that field — and they come from it in one read, so
        // they describe one moment. Read here rather than up beside the journal's snapshot for the
        // same reason everything else is done here: taken then, a dispatch landing at that instant
        // would still be changing what it says. The journal line above records the status the
        // destroy began in, which is what it claims to be; what is done about the Run is decided
        // from the last thing the Run actually said.
        //
        // The waiting, the reading and the claim on the buffer are one step and in one order
        // ([runTakenByThisTeardown]). The claim is taken here rather than up at the first join
        // because a teardown that took it earlier would be claiming a buffer the session thread
        // might still have been about to deliver from — standing that thread down for the whole
        // of the teardown in between (#360).
        when (val lost = runTakenByThisTeardown(sessionThread)) {
            is RunLostToTeardown.HasRow -> endRunLostToTeardown(lost.runRowId)
            is RunLostToTeardown.AwaitingItsRow -> endRunAwaitingItsRow(lost)
            null -> Unit
        }

        // The one wake-lock release outside Promotion, and deliberately so: destruction can be
        // system-initiated, arriving without any demotion having happened. A wake lock must never
        // outlive the service that took it, so this is a last-resort safety net, not a second
        // owner of the decision. acquire/release are idempotent, so a preceding demote is fine.
        releaseWakeLock()
        audioCueManager?.shutdown()

        // The one drain this teardown takes for itself, and it is not the destroyed line's: that
        // one waits for itself above. This is for the session inbox, which was still running then
        // — a lifecycle or Acquisition line it wrote on its way out is ordinary, so nothing waited
        // for it, and there is nothing left running that would. The drain is taken here
        // rather than the quit and join above being lifted over the snapshot: that order is what
        // lets a late connect see [destroyed] and what leaves step 2 alone with [openGatts], and a
        // diagnostic is never worth risking the Bluetooth teardown those steps are protecting. Same
        // bounded wait, so this cannot be what ends the app either (#309, #310).
        runJournal.flushBlocking()
        Log.d(TAG, "Service destroyed")
    }
}

/**
 * Puts every choice the record screen offered onto an intent that begins a Run.
 *
 * One place, so START and the Simulate button beside it cannot carry different subsets of the same
 * tap — which is what they did while each spelled the extras out (#56). Here rather than beside its
 * callers because this is the writing half of a pair: the reading half is in
 * [HrForegroundService.onStartCommand], and a sentinel packed in one file and unpacked in another is
 * two statements of one convention.
 *
 * The mode travels on the intent so a just-tapped Treadmill/Outdoor choice is honoured even before
 * its settings write lands; the Workout (#174) and the Route (#56) travel for the same reason.
 *
 * Every choice goes on whichever of the two Run-starting actions this is, and both actions read the
 * same choices back: a Run is a Run whether the Strap is real or invented, so the Simulate button
 * must not quietly record a different Run from the one START would have.
 */
fun Intent.putRunChoices(request: StartRunRequest) {
    putExtra(HrForegroundService.EXTRA_SKIP_PLAN, request.skipPlan)
    putExtra(HrForegroundService.EXTRA_RUN_MODE, request.runMode.settingValue)
    putExtra(HrForegroundService.EXTRA_WORKOUT_ID, request.pickedWorkoutId)
    // Nought is "no course": an intent extra cannot carry a null Long, and no Route has that id.
    putExtra(
        HrForegroundService.EXTRA_ROUTE_ID,
        request.route?.routeId ?: HrForegroundService.NO_ROUTE_ID
    )
    putExtra(HrForegroundService.EXTRA_ROUTE_REVERSED, request.route?.reversed == true)
}
