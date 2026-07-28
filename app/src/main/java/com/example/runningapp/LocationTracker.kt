package com.example.runningapp

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.runningapp.recording.Clock
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.recording.SessionRecorder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority

class LocationTracker(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val logTag: String,
    playCue: (String) -> Unit,
    private val getSessionStatus: () -> SessionStatus,
    isSplitAnnouncementsEnabled: () -> Boolean,
    onMetricsUpdated: (distanceKm: Double, paceMinPerKm: Double, lastLocation: Location?) -> Unit,
    private val onRawFix: (location: Location, barometerPressureHpa: Float?) -> Unit = { _, _ -> },
    isAutoPauseEnabled: () -> Boolean = { false },
    onAutoPause: () -> Unit = {},
    onAutoResume: () -> Unit = {},
) {
    private var locationCallback: LocationCallback? = null
    private var locationHandlerThread: HandlerThread? = null
    private var locationHandler: Handler? = null
    private var lastLocation: Location? = null
    private var firstLocation: Location? = null
    private val barometerReader = BarometerReader(context)

    private val sessionRecorder = SessionRecorder(
        clock = Clock { System.currentTimeMillis() },
        playSplitCue = playCue,
        isSplitAnnouncementsEnabled = isSplitAnnouncementsEnabled,
        onMetricsUpdated = { metrics -> onMetricsUpdated(metrics.distanceKm, metrics.paceMinPerKm, lastLocation) },
        logDecision = ::logDecision,
        isAutoPauseEnabled = isAutoPauseEnabled,
        onAutoPause = onAutoPause,
        onAutoResume = onAutoResume,
    )

    // Decide from the passed runMode/isSimulationEnabled, not a captured settings snapshot: START
    // supplies the just-tapped mode (effectiveRunMode), which can lead the async settings write, so
    // reading currentSettings here would skip GPS on an outdoor run started right after the switch.
    fun restartIfNeeded(trigger: String, runMode: String, isSimulationEnabled: Boolean) {
        if (runMode == "outdoor" && !isSimulationEnabled) {
            logDecision("start", "trigger=$trigger runMode=$runMode simulation=$isSimulationEnabled")
            start()
        } else {
            logDecision("skip_start", "trigger=$trigger runMode=$runMode simulation=$isSimulationEnabled")
        }
    }

    @Synchronized
    fun resetSessionState() {
        lastLocation = null
        firstLocation = null
        sessionRecorder.reset()
    }

    /**
     * Resyncs the recorder's internal auto-pause flag when the host resumes the session by some
     * other means (a manual resume while auto-paused) - GPS was never stopped in that case, so
     * there's no discardLastFix()/reset() call to clear it otherwise (#39).
     */
    fun clearAutoPauseState() {
        sessionRecorder.clearAutoPauseState()
    }

    /** The first GPS fix accepted this session — used as the run's start position (#79). */
    @Synchronized
    fun getFirstLocation(): Location? = firstLocation

    fun getDistanceKm(): Double = sessionRecorder.getDistanceKm()

    fun getPaceMinPerKm(): Double = sessionRecorder.getPaceMinPerKm()

    @Synchronized
    fun start() {
        if (locationCallback != null) {
            logDecision("already_started", "Location updates already active; ignoring duplicate start")
            return
        }
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logDecision("permission_missing", "ACCESS_FINE_LOCATION not granted; cannot start updates")
            Log.w(logTag, "Location permission missing, cannot start updates")
            return
        }

        if (locationHandlerThread == null || !locationHandlerThread!!.isAlive) {
            locationHandlerThread = HandlerThread("LocationThread").apply { start() }
            locationHandler = Handler(locationHandlerThread!!.looper)
        }

        logDecision("start_request", "Preparing high-accuracy location updates")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        barometerReader.start()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val status = getSessionStatus()
                // Auto-pause (#39) keeps GPS registered through a standstill so movement can be
                // detected - unlike a manual pause, which fully stops updates via stop() below -
                // so fixes must keep flowing to SessionRecorder while auto-paused too.
                val autoPaused = status == SessionStatus.PAUSED && sessionRecorder.isAutoPaused()
                val shouldProcess = status == SessionStatus.RUNNING || autoPaused
                if (!shouldProcess) {
                    Log.d(logTag, "Ignoring location update - session not running")
                    return
                }
                for (location in locationResult.locations) {
                    handleNewLocation(location, autoPaused)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, locationHandler?.looper ?: Looper.getMainLooper())
        Log.d(logTag, "Location updates started on custom looper")
        logDecision("started", "Location callback registered on background looper")
    }

    @Synchronized
    fun stop() {
        Log.d(logTag, "stopLocationUpdates() - Killing location engine")
        logDecision("stop", "Stopping location updates and clearing last location")
        val callback = locationCallback
        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null
        }
        barometerReader.stop()
        lastLocation = null
        sessionRecorder.discardLastFix()
        Log.d(logTag, "Location updates stopped")
    }

    fun shutdown() {
        stop()
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        locationHandler = null
    }

    @Synchronized
    private fun handleNewLocation(location: Location, autoPaused: Boolean = false) {
        Log.d(logTag, "New location: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
        lastLocation = location
        if (firstLocation == null) {
            firstLocation = location
        }
        // Every received fix is recorded as a track point, unfiltered — SessionRecorder below
        // applies its own accuracy gate separately, only for live distance/pace accumulation.
        //
        // Except while auto-paused (#39). A manual pause tears the GPS stream down, so a pause is
        // simply absent from the recorded route; auto-pause deliberately keeps it up so movement can
        // restart the run, and those fixes would land a second apart like any others — a standstill
        // written into the route as if it were run. The recorder already refuses to count them
        // towards distance, and the stored track is what the map draws and the export writes, so
        // neither should disagree with it. The fix still reaches the recorder below, which is what
        // notices the runner moving again.
        if (!autoPaused) {
            onRawFix(location, barometerReader.getLastPressureHpa())
        }
        sessionRecorder.onLocationFix(
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                timestampMs = location.time,
            )
        )
    }

    private fun logDecision(reason: String, detail: String) {
        Log.d(logTag, "Location decision: $reason | $detail")
    }
}
