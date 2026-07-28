package com.example.runningapp.recording

import java.util.LinkedList
import kotlin.math.roundToInt

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    // Null when the platform location has no accuracy estimate (Android's Location.hasAccuracy()
    // is false) - must never be treated as a perfect 0m-accuracy fix (#38 review).
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val timestampMs: Long,
)

fun interface Clock {
    fun nowMillis(): Long
}

data class SessionRecorderMetrics(
    val distanceKm: Double,
    val paceMinPerKm: Double,
    val lastFix: LocationFix?,
)

/**
 * Pure Kotlin session recording logic (distance, pace, split cues) extracted from
 * [com.example.runningapp.LocationTracker]. Fed a stream of [LocationFix]es by an
 * Android host; has no framework dependencies so it can be exercised on the JVM.
 */
class SessionRecorder(
    private val clock: Clock,
    private val playSplitCue: (String) -> Unit,
    private val isSplitAnnouncementsEnabled: () -> Boolean,
    private val onMetricsUpdated: (SessionRecorderMetrics) -> Unit,
    private val logDecision: (reason: String, detail: String) -> Unit = { _, _ -> },
    private val isAutoPauseEnabled: () -> Boolean = { false },
    private val onAutoPause: () -> Unit = {},
    private val onAutoResume: () -> Unit = {},
) {
    private var lastFix: LocationFix? = null
    // The distance-delta baseline. Deliberately only ever an *accepted* fix, so accumulated
    // distance always equals the sum of accepted-to-accepted legs - the same thing a map drawn
    // from SessionRepository.getTrackPointsForMap()'s filtered points would show (#38 review).
    private var lastAcceptedFix: LocationFix? = null
    private var sessionDistanceMeters = 0.0
    private var lastSplitAnnouncedKm = 0
    private val paceHistory = LinkedList<Pair<Long, Double>>()

    // Auto-pause state machine (#39). isAutoPaused freezes distance/pace/split-cue processing
    // below exactly like a host-level manual pause freezes the session clock and interval timers -
    // the two mechanisms are deliberately symmetric so "paused" always means the same thing.
    private var isAutoPaused = false
    private var standstillSinceMs: Long? = null
    private var movingSinceMs: Long? = null

    fun reset() {
        sessionDistanceMeters = 0.0
        lastSplitAnnouncedKm = 0
        synchronized(paceHistory) { paceHistory.clear() }
        lastFix = null
        lastAcceptedFix = null
        isAutoPaused = false
        standstillSinceMs = null
        movingSinceMs = null
        onMetricsUpdated(SessionRecorderMetrics(0.0, 0.0, null))
    }

    /** Clears the fix used as the distance-delta baseline without losing accumulated distance/pace state. */
    fun discardLastFix() {
        lastFix = null
        lastAcceptedFix = null
        // Only reached via a manual pause/stop (LocationTracker.stop()), which - unlike
        // auto-pause - already tears down the GPS stream, so any in-flight standstill tracking
        // is stale and must not carry over into the next resume (#39).
        isAutoPaused = false
        standstillSinceMs = null
        movingSinceMs = null
    }

    /**
     * Forces the auto-pause state machine back to "moving" without firing [onAutoResume] - the
     * host calls this when it resumes the session by some other means (a manual resume while
     * auto-paused), so a stale internal auto-pause flag doesn't keep freezing distance/pace/split
     * cues after the host has already moved on to RUNNING (#39).
     */
    fun clearAutoPauseState() {
        isAutoPaused = false
        standstillSinceMs = null
        movingSinceMs = null
    }

    fun getDistanceKm(): Double = sessionDistanceMeters / 1000.0

    /**
     * The *live* pace: a rolling [PACE_WINDOW_MS] window, for the in-run tile and split cues.
     * Never a finished run's average — reading this at STOP reports the pace of a runner standing
     * still at the finish line (#163). For that, use
     * [com.example.runningapp.data.averagePaceMinPerKm] on the run's own totals.
     */
    fun getPaceMinPerKm(): Double = calculatePace()

    fun isAutoPaused(): Boolean = isAutoPaused

    fun onLocationFix(fix: LocationFix) {
        val now = clock.nowMillis()
        val accepted = isAccuracyAccepted(fix.accuracyMeters)

        var speedMps = 0.0
        if (accepted) {
            val last = lastAcceptedFix
            var distance = 0.0
            if (last != null) {
                distance = distanceBetweenMeters(last, fix)
                val timeDeltaSec = (now - last.timestampMs) / 1000.0
                speedMps = if (fix.speedMps != null && fix.speedMps > 0.1f) {
                    fix.speedMps.toDouble()
                } else if (timeDeltaSec > 0.5) {
                    distance / timeDeltaSec
                } else {
                    0.0
                }
            }
            // Resolve the auto-pause transition before deciding whether this fix's own delta
            // counts, so a fix that crosses the standstill/movement boundary is judged by the
            // state it just entered, not the one it's leaving (#39).
            updateAutoPauseState(speedMps, now)
            if (last != null && !isAutoPaused) {
                sessionDistanceMeters += distance
                logDecision("distance_updated", "+${"%.2f".format(distance)}m, total=${"%.2f".format(sessionDistanceMeters)}m")
            }
            // Kept as the distance baseline even while auto-paused, so it tracks the runner's
            // (near-stationary) position and resuming doesn't count a phantom leg (#39).
            lastAcceptedFix = fix
        } else {
            logDecision("location_rejected", "accuracy=${fix.accuracyMeters}m > threshold=${ACCURACY_THRESHOLD_METERS}m")
        }
        lastFix = fix

        if (!isAutoPaused) {
            synchronized(paceHistory) {
                if (accepted) {
                    paceHistory.add(Pair(now, if (speedMps > 0.2) speedMps else 0.0))
                }
                // Prune on every fix, not just accepted ones - otherwise a run of rejected fixes
                // leaves a stale sample sitting past the pace window instead of aging out (#38 review).
                while (paceHistory.isNotEmpty() && (now - paceHistory.first.first > PACE_WINDOW_MS)) {
                    paceHistory.removeFirst()
                }
            }

            if (accepted) {
                val currentKm = (sessionDistanceMeters / 1000).toInt()
                if (isSplitAnnouncementsEnabled() && currentKm > lastSplitAnnouncedKm) {
                    lastSplitAnnouncedKm = currentKm
                    val pace = calculatePace()
                    if (pace > 0) {
                        val paceMins = pace.toInt()
                        val paceSecs = ((pace - paceMins) * 60).roundToInt()
                        playSplitCue("Split $currentKm kilometer. Pace $paceMins minutes $paceSecs seconds per kilometer.")
                    } else {
                        playSplitCue("Split $currentKm kilometer.")
                    }
                }
            }
        }

        val currentDistanceKm = sessionDistanceMeters / 1000.0
        val currentPace = calculatePace()
        logDecision("state_update", "distanceKm=${"%.3f".format(currentDistanceKm)} paceMinPerKm=${"%.2f".format(currentPace)}")
        onMetricsUpdated(SessionRecorderMetrics(currentDistanceKm, currentPace, lastFix))
    }

    /**
     * Standstill/movement detection (#39): sustained speed below [STANDSTILL_SPEED_THRESHOLD_MPS]
     * for [AUTO_PAUSE_STANDSTILL_MS] auto-pauses; speed at or above it sustained for
     * [AUTO_RESUME_SUSTAINED_MS] resumes. Resume needs its own (much shorter) sustain window too -
     * a single noisy fix at a standstill (position jitter, a derived speed spike from a brief
     * accuracy blip) must not be enough to unfreeze the session on its own.
     */
    private fun updateAutoPauseState(speedMps: Double, now: Long) {
        if (!isAutoPauseEnabled()) {
            standstillSinceMs = null
            movingSinceMs = null
            // Handing control back to manual pause/resume must not leave the session stuck
            // auto-paused forever if the toggle is switched off mid-pause.
            if (isAutoPaused) {
                isAutoPaused = false
                onAutoResume()
            }
            return
        }

        if (speedMps < STANDSTILL_SPEED_THRESHOLD_MPS) {
            movingSinceMs = null
            if (!isAutoPaused) {
                val since = standstillSinceMs ?: now
                standstillSinceMs = since
                if (now - since >= AUTO_PAUSE_STANDSTILL_MS) {
                    isAutoPaused = true
                    standstillSinceMs = null
                    logDecision("auto_paused", "speed=${"%.2f".format(speedMps)}mps sustained below ${STANDSTILL_SPEED_THRESHOLD_MPS}mps")
                    onAutoPause()
                }
            }
        } else {
            standstillSinceMs = null
            if (isAutoPaused) {
                val since = movingSinceMs ?: now
                movingSinceMs = since
                if (now - since >= AUTO_RESUME_SUSTAINED_MS) {
                    isAutoPaused = false
                    movingSinceMs = null
                    logDecision("auto_resumed", "speed=${"%.2f".format(speedMps)}mps")
                    onAutoResume()
                }
            }
        }
    }

    private fun calculatePace(): Double {
        synchronized(paceHistory) {
            if (paceHistory.isEmpty()) return 0.0
            val avgSpeedMps = paceHistory.map { it.second }.average()
            if (avgSpeedMps <= 0.1) return 0.0
            return 1000.0 / (avgSpeedMps * 60.0)
        }
    }

    companion object {
        /**
         * GPS fixes coarser than this are excluded from distance, pace, and split cues (#38), and
         * the same bar gates which stored [com.example.runningapp.data.TrackPoint]s a map query
         * returns, so what the runner hears mid-run matches what they see afterward.
         */
        const val ACCURACY_THRESHOLD_METERS = 30.0

        /** Whether a fix this accurate counts toward distance/pace/split cues or a map read (#38). */
        fun isAccuracyAccepted(accuracyMeters: Float?): Boolean =
            accuracyMeters != null && accuracyMeters <= ACCURACY_THRESHOLD_METERS

        /**
         * Speed below which the runner is considered stationary (#39) - comfortably under even a
         * slow walking pace (~0.8 m/s+) so a walk, a run/walk WALK interval, or a Zone 2 walk
         * never reads as a standstill.
         */
        const val STANDSTILL_SPEED_THRESHOLD_MPS = 0.3

        /** How long speed must stay below [STANDSTILL_SPEED_THRESHOLD_MPS] before auto-pause fires (#39). */
        const val AUTO_PAUSE_STANDSTILL_MS = 10_000L

        /**
         * How long speed must stay at or above [STANDSTILL_SPEED_THRESHOLD_MPS] before auto-resume
         * fires (#39) - short enough to feel instant to the runner, long enough that one noisy fix
         * can't unfreeze the session on its own.
         */
        const val AUTO_RESUME_SUSTAINED_MS = 2_000L

        private const val PACE_WINDOW_MS = 15_000L

        private fun distanceBetweenMeters(from: LocationFix, to: LocationFix): Double =
            geodesicDistanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)
    }
}
