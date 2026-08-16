package com.example.runningapp.data

import kotlin.math.roundToInt

/**
 * The words the app has for a WMO weather code (#79).
 *
 * https://open-meteo.com/en/docs — WMO Weather interpretation codes. Codes with no entry are ones
 * nobody has a word for here; they are left unsaid rather than printed as a number, because a bare
 * "code 42" is a fact about a standard and not about anybody's run.
 *
 * One map, read by the run detail page and by the coach's context alike, so the runner and the coach
 * can never be told the same code in two different words.
 */
private val WMO_CONDITION_LABELS = mapOf(
    0 to "Clear sky",
    1 to "Mainly clear",
    2 to "Partly cloudy",
    3 to "Overcast",
    45 to "Fog",
    48 to "Fog",
    51 to "Light drizzle",
    53 to "Drizzle",
    55 to "Heavy drizzle",
    56 to "Freezing drizzle",
    57 to "Freezing drizzle",
    61 to "Light rain",
    63 to "Rain",
    65 to "Heavy rain",
    66 to "Freezing rain",
    67 to "Freezing rain",
    71 to "Light snow",
    73 to "Snow",
    75 to "Heavy snow",
    77 to "Snow grains",
    80 to "Light showers",
    81 to "Showers",
    82 to "Heavy showers",
    85 to "Snow showers",
    86 to "Snow showers",
    95 to "Thunderstorm",
    96 to "Thunderstorm with hail",
    99 to "Thunderstorm with hail"
)

/** What [code] is called, or null where it is a code this app has no word for. */
fun wmoConditionLabel(code: Int?): String? = code?.let { WMO_CONDITION_LABELS[it] }

/**
 * The weather a Run was run in, as one line — what the coach is told about it (#83).
 *
 * Null rather than an empty string when nothing was recorded, which is every treadmill Run and every
 * outdoor one the fetch never reached. An empty string is still a field, and a field holding nothing
 * reads as a measurement of nothing: a coach handed one could fairly say the runner ran in no wind
 * on a still day. Absent, it can only say the weather is unknown.
 *
 * Only the parts that were recorded, in the order a runner would say them — what it was doing, then
 * how warm, then how it felt, then the rest. Rounded to whole degrees and whole km/h exactly as the
 * run detail page rounds them: a tenth of a degree is a precision the fetch does not have and nobody
 * would train differently for.
 */
fun weatherSummaryOf(
    tempC: Double?,
    feelsLikeC: Double?,
    humidityPercent: Int?,
    windSpeedKmh: Double?,
    conditionCode: Int?,
): String? {
    // Rounded rather than formatted to no decimal places, which is not the same thing just below
    // freezing: "%.0f" writes a feels-like of -0.4°C as "-0°C", and a coach reading a minus sign
    // against a zero is being told something about the cold that is not true.
    val parts = listOfNotNull(
        wmoConditionLabel(conditionCode),
        tempC?.let { "${it.roundToInt()}°C" },
        feelsLikeC?.let { "feels like ${it.roundToInt()}°C" },
        humidityPercent?.let { "$it% humidity" },
        windSpeedKmh?.let { "${it.roundToInt()} km/h wind" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
