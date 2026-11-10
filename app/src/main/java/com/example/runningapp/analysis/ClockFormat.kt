package com.example.runningapp.analysis

/**
 * The app's one minutes:seconds clock, "05:09" or "125:00" — minutes never roll into hours here.
 *
 * A negative duration reads "00:00": a clock has no way to say "before the start".
 */
fun formatMinutesSeconds(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
