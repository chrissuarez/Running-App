package com.example.runningapp.data

import kotlin.math.roundToInt

private const val EASY_FIXED_DURATION_JOG_PACE_THRESHOLD_MIN_PER_KM = 10.5

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
    val locomotionStates = sortedSamples.map { sample ->
        when {
            sample.paceMinPerKm == null || sample.paceMinPerKm <= 0.0 -> EasyLocomotionState.UNKNOWN
            sample.paceMinPerKm <= EASY_FIXED_DURATION_JOG_PACE_THRESHOLD_MIN_PER_KM -> EasyLocomotionState.JOG
            else -> EasyLocomotionState.WALK
        }
    }

    val knownStates = locomotionStates.filter { it != EasyLocomotionState.UNKNOWN }
    val confidentLocomotion = knownStates.size >= 30 && knownStates.size >= (actualDurationSeconds / 2)

    val locomotionMetrics = if (confidentLocomotion) {
        computeLocomotionMetrics(locomotionStates)
    } else {
        null
    }

    val hrSummary = "avg=${avgBpm} max=${maxBpm} aboveCap=${timeAboveEasyCapSeconds}s"
    val dataQualitySummary = when {
        sortedSamples.isEmpty() -> "no_hr_samples"
        confidentLocomotion -> {
            val paceCoveragePercent = ((knownStates.size.toDouble() / actualDurationSeconds.coerceAtLeast(1).toDouble()) * 100.0)
                .roundToInt()
            "pace_based coverage=${paceCoveragePercent}% noData=${noDataSeconds}s"
        }
        else -> "hr_only_or_low_pace_confidence noData=${noDataSeconds}s"
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
