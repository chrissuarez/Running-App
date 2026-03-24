package com.example.runningapp.data

import kotlin.math.roundToInt

// Provisional Slice 5 tuning values for deterministic, pace-led locomotion inference.
private object EasyFixedDurationInferenceTuning {
    const val JOG_PACE_THRESHOLD_MIN_PER_KM = 10.25
    const val WALK_PACE_THRESHOLD_MIN_PER_KM = 11.25
    const val MIN_STABLE_SECONDS_FOR_STATE_SWITCH = 5
    const val MIN_KNOWN_COVERAGE_PERCENT = 40
    const val MIN_KNOWN_SECONDS_FOR_SUMMARY = 60
}

data class EasyFixedDurationSummary(
    val plannedDurationSeconds: Int,
    val actualDurationSeconds: Int,
    val totalJogSeconds: Int?,
    val totalWalkSeconds: Int?,
    val jogPercent: Int?,
    val longestJogBoutSeconds: Int?,
    val walkInterruptions: Int?,
    val hrSummary: String,
    val timeAboveEasyCapSeconds: Int,
    val dataQualitySummary: String?
)

private enum class EasyLocomotionState {
    JOG,
    WALK,
    UNKNOWN
}

private enum class EasyLocomotionCandidate {
    JOG,
    WALK,
    HYSTERESIS,
    UNKNOWN
}

fun computeEasyFixedDurationSummary(
    plannedDurationSeconds: Int,
    actualDurationSeconds: Int,
    avgBpm: Int,
    maxBpm: Int,
    timeAboveEasyCapSeconds: Int,
    noDataSeconds: Long,
    samples: List<HrSample>
): EasyFixedDurationSummary {
    val sortedSamples = samples.sortedBy { it.elapsedSeconds }
    val locomotionStates = inferLocomotionStates(sortedSamples)
    val knownStates = locomotionStates.filter { it != EasyLocomotionState.UNKNOWN }
    val knownCoveragePercent = ((knownStates.size.toDouble() / actualDurationSeconds.coerceAtLeast(1).toDouble()) * 100.0)
        .roundToInt()
    val confidentLocomotion =
        knownStates.size >= EasyFixedDurationInferenceTuning.MIN_KNOWN_SECONDS_FOR_SUMMARY &&
            knownCoveragePercent >= EasyFixedDurationInferenceTuning.MIN_KNOWN_COVERAGE_PERCENT

    val locomotionMetrics = if (confidentLocomotion) {
        computeLocomotionMetrics(locomotionStates)
    } else {
        null
    }

    val hrSummary = "avg=${avgBpm} max=${maxBpm} aboveCap=${timeAboveEasyCapSeconds}s"
    val dataQualitySummary = when {
        sortedSamples.isEmpty() -> "no_hr_samples"
        knownStates.isEmpty() -> "no_usable_pace noData=${noDataSeconds}s"
        confidentLocomotion -> "pace_inferred coverage=${knownCoveragePercent}% noData=${noDataSeconds}s"
        else -> "pace_low_coverage coverage=${knownCoveragePercent}% known=${knownStates.size}s noData=${noDataSeconds}s"
    }

    return EasyFixedDurationSummary(
        plannedDurationSeconds = plannedDurationSeconds,
        actualDurationSeconds = actualDurationSeconds,
        totalJogSeconds = locomotionMetrics?.totalJogSeconds,
        totalWalkSeconds = locomotionMetrics?.totalWalkSeconds,
        jogPercent = locomotionMetrics?.jogPercent,
        longestJogBoutSeconds = locomotionMetrics?.longestJogBoutSeconds,
        walkInterruptions = locomotionMetrics?.walkInterruptions,
        hrSummary = hrSummary,
        timeAboveEasyCapSeconds = timeAboveEasyCapSeconds,
        dataQualitySummary = dataQualitySummary
    )
}

private fun inferLocomotionStates(samples: List<HrSample>): List<EasyLocomotionState> {
    if (samples.isEmpty()) return emptyList()

    var stableState = EasyLocomotionState.UNKNOWN
    var pendingState = EasyLocomotionState.UNKNOWN
    var pendingSeconds = 0

    return samples.map { sample ->
        val candidate = toLocomotionCandidate(sample.paceMinPerKm)

        when (candidate) {
            EasyLocomotionCandidate.UNKNOWN -> {
                pendingState = EasyLocomotionState.UNKNOWN
                pendingSeconds = 0
                EasyLocomotionState.UNKNOWN
            }

            // Keep the current stable state through the hysteresis band instead of switching on noise.
            EasyLocomotionCandidate.HYSTERESIS -> {
                pendingState = EasyLocomotionState.UNKNOWN
                pendingSeconds = 0
                stableState
            }

            EasyLocomotionCandidate.JOG,
            EasyLocomotionCandidate.WALK -> {
                val candidateState = if (candidate == EasyLocomotionCandidate.JOG) {
                    EasyLocomotionState.JOG
                } else {
                    EasyLocomotionState.WALK
                }

                if (candidateState == stableState) {
                    pendingState = EasyLocomotionState.UNKNOWN
                    pendingSeconds = 0
                    stableState
                } else {
                    if (candidateState == pendingState) {
                        pendingSeconds += 1
                    } else {
                        pendingState = candidateState
                        pendingSeconds = 1
                    }

                    // Require a short stable run of evidence before switching states.
                    if (pendingSeconds >= EasyFixedDurationInferenceTuning.MIN_STABLE_SECONDS_FOR_STATE_SWITCH) {
                        stableState = candidateState
                        pendingState = EasyLocomotionState.UNKNOWN
                        pendingSeconds = 0
                    }

                    stableState
                }
            }
        }
    }
}

private fun toLocomotionCandidate(paceMinPerKm: Double?): EasyLocomotionCandidate {
    return when {
        paceMinPerKm == null || paceMinPerKm <= 0.0 -> EasyLocomotionCandidate.UNKNOWN
        paceMinPerKm <= EasyFixedDurationInferenceTuning.JOG_PACE_THRESHOLD_MIN_PER_KM -> EasyLocomotionCandidate.JOG
        paceMinPerKm >= EasyFixedDurationInferenceTuning.WALK_PACE_THRESHOLD_MIN_PER_KM -> EasyLocomotionCandidate.WALK
        else -> EasyLocomotionCandidate.HYSTERESIS
    }
}

private data class LocomotionMetrics(
    val totalJogSeconds: Int,
    val totalWalkSeconds: Int,
    val jogPercent: Int,
    val longestJogBoutSeconds: Int,
    val walkInterruptions: Int
)

private fun computeLocomotionMetrics(states: List<EasyLocomotionState>): LocomotionMetrics {
    var totalJogSeconds = 0
    var totalWalkSeconds = 0
    var longestJogBoutSeconds = 0
    var currentJogBoutSeconds = 0
    var walkInterruptions = 0
    var hasSeenJog = false
    var inWalkBout = false

    states.forEach { state ->
        when (state) {
            EasyLocomotionState.JOG -> {
                totalJogSeconds += 1
                currentJogBoutSeconds += 1
                longestJogBoutSeconds = maxOf(longestJogBoutSeconds, currentJogBoutSeconds)
                hasSeenJog = true
                inWalkBout = false
            }

            EasyLocomotionState.WALK -> {
                totalWalkSeconds += 1
                currentJogBoutSeconds = 0
                if (hasSeenJog && !inWalkBout) {
                    walkInterruptions += 1
                    inWalkBout = true
                }
            }

            EasyLocomotionState.UNKNOWN -> {
                currentJogBoutSeconds = 0
                inWalkBout = false
            }
        }
    }

    val locomotionTotalSeconds = (totalJogSeconds + totalWalkSeconds).coerceAtLeast(1)
    val jogPercent = ((totalJogSeconds.toDouble() / locomotionTotalSeconds.toDouble()) * 100.0).roundToInt()

    return LocomotionMetrics(
        totalJogSeconds = totalJogSeconds,
        totalWalkSeconds = totalWalkSeconds,
        jogPercent = jogPercent,
        longestJogBoutSeconds = longestJogBoutSeconds,
        walkInterruptions = walkInterruptions
    )
}
