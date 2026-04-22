package com.example.runningapp

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.util.LinkedList
import kotlin.math.roundToInt

class LocationTracker(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val logTag: String,
    private val playCue: (String) -> Unit,
    private val getSessionStatus: () -> SessionStatus,
    private val getShouldTrack: () -> Boolean,
    private val isSplitAnnouncementsEnabled: () -> Boolean,
    private val onMetricsUpdated: (distanceKm: Double, paceMinPerKm: Double, lastLocation: Location?) -> Unit,
) {
    private var locationCallback: LocationCallback? = null
    private var locationHandlerThread: HandlerThread? = null
    private var locationHandler: Handler? = null
    private var lastValidLocationTime = 0L
    private var lastLocation: Location? = null
    private var sessionDistanceMeters = 0.0
    private var lastSplitAnnouncedKm = 0
    private val paceWindowMs = 15_000L
    private val paceHistory = LinkedList<Pair<Long, Double>>()

    fun restartIfNeeded(trigger: String, runMode: String, isSimulationEnabled: Boolean) {
        if (getShouldTrack()) {
            logDecision("start", "trigger=$trigger runMode=$runMode simulation=$isSimulationEnabled")
            start()
        } else {
            logDecision("skip_start", "trigger=$trigger runMode=$runMode simulation=$isSimulationEnabled")
        }
    }

    fun resetSessionState() {
        sessionDistanceMeters = 0.0
        lastSplitAnnouncedKm = 0
        synchronized(paceHistory) { paceHistory.clear() }
        lastLocation = null
        lastValidLocationTime = 0L
        onMetricsUpdated(0.0, 0.0, null)
    }

    fun getLastLocation(): Location? = lastLocation

    fun getDistanceKm(): Double = sessionDistanceMeters / 1000.0

    fun getPaceMinPerKm(): Double = calculatePace()

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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (getSessionStatus() != SessionStatus.RUNNING) {
                    Log.d(logTag, "Ignoring location update - session not running")
                    return
                }
                for (location in locationResult.locations) {
                    handleNewLocation(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, locationHandler?.looper ?: Looper.getMainLooper())
        Log.d(logTag, "Location updates started on custom looper")
        logDecision("started", "Location callback registered on background looper")
    }

    fun stop() {
        Log.d(logTag, "stopLocationUpdates() - Killing location engine")
        logDecision("stop", "Stopping location updates and clearing last location")
        val callback = locationCallback
        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null
        }
        lastLocation = null
        Log.d(logTag, "Location updates stopped")
    }

    fun shutdown() {
        stop()
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        locationHandler = null
    }

    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        Log.d(logTag, "New location: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")

        var speedMps = 0.0
        lastLocation?.let { last ->
            val distance = last.distanceTo(location).toDouble()
            val timeDeltaSec = (now - last.time) / 1000.0
            val timeSinceLastValid = (now - lastValidLocationTime) / 1000
            val accuracyThreshold = if (timeSinceLastValid > 30) 250.0 else 100.0
            logDecision("accuracy_threshold", "timeSinceLastValid=${timeSinceLastValid}s threshold=${accuracyThreshold}m")

            if (location.accuracy <= accuracyThreshold) {
                sessionDistanceMeters += distance
                lastValidLocationTime = now
                Log.d(logTag, "Distance updated: +${"%.2f".format(distance)}m, total=${"%.2f".format(sessionDistanceMeters)}m (Threshold: ${accuracyThreshold}m)")
            } else {
                Log.w(logTag, "Location rejected: accuracy=${location.accuracy}m > threshold=${accuracyThreshold}m")
            }

            speedMps = if (location.hasSpeed() && location.speed > 0.1f) {
                location.speed.toDouble()
            } else if (timeDeltaSec > 0.5) {
                distance / timeDeltaSec
            } else {
                0.0
            }
        }
        lastLocation = location

        synchronized(paceHistory) {
            paceHistory.add(Pair(now, if (speedMps > 0.2) speedMps else 0.0))
            while (paceHistory.isNotEmpty() && (now - paceHistory.first.first > paceWindowMs)) {
                paceHistory.removeFirst()
            }
        }

        val currentKm = (sessionDistanceMeters / 1000).toInt()
        if (isSplitAnnouncementsEnabled() && currentKm > lastSplitAnnouncedKm) {
            lastSplitAnnouncedKm = currentKm
            val pace = calculatePace()
            if (pace > 0) {
                val paceMins = pace.toInt()
                val paceSecs = ((pace - paceMins) * 60).roundToInt()
                playCue("Split $currentKm kilometer. Pace $paceMins minutes $paceSecs seconds per kilometer.")
            } else {
                playCue("Split $currentKm kilometer.")
            }
        }

        val currentDistanceKm = sessionDistanceMeters / 1000.0
        val currentPace = calculatePace()
        logDecision("state_update", "distanceKm=${"%.3f".format(currentDistanceKm)} paceMinPerKm=${"%.2f".format(currentPace)}")
        onMetricsUpdated(currentDistanceKm, currentPace, lastLocation)
    }

    private fun calculatePace(): Double {
        synchronized(paceHistory) {
            if (paceHistory.isEmpty()) return 0.0
            val avgSpeedMps = paceHistory.map { it.second }.average()
            if (avgSpeedMps <= 0.1) return 0.0
            return 1000.0 / (avgSpeedMps * 60.0)
        }
    }

    private fun logDecision(reason: String, detail: String) {
        Log.d(logTag, "Location decision: $reason | $detail")
    }
}
