package com.example.runningapp.export

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.awaitsStatedDistance
import com.example.runningapp.data.isFinished
import java.net.URI

/**
 * Whether a Run's page should ask "Post this run to Garmin?" (#544).
 *
 * Asked once per Run, when its page first opens after the Run. "Once" is kept as a watermark rather
 * than a list: the highest Run id the question has already been settled for. Run ids only grow, so
 * a Run at or below it has had its chance — answered, or passed over for a newer Run — and is never
 * asked about again, which is also what keeps years of history from asking the day this arrives.
 */
object GarminPostOffer {

    /**
     * How long after a Run ends its page still asks. A day, so the runner who opens the Run that
     * evening is still asked, and a Run looked up from History next week is not.
     */
    const val FRESH_FOR_MILLIS = 24L * 60 * 60 * 1000

    fun shouldAsk(run: RunnerSession, askedThroughRunId: Long, nowMillis: Long): Boolean =
        run.isFinished() &&
            run.id > askedThroughRunId &&
            // The file states the distance, and a treadmill Run's is typed in after the page opens.
            !run.awaitsStatedDistance() &&
            nowMillis - run.endTime in 0..FRESH_FOR_MILLIS
}

/** Where the "asked once" watermark for [GarminPostOffer] is kept. */
interface GarminPostLedger {
    /** The highest Run id already asked about, or 0 if none. */
    suspend fun askedThroughRunId(): Long

    /** Records that [runId] has been asked about. Never moves the watermark backwards. */
    suspend fun markAsked(runId: Long)
}

/** The rules the in-app Garmin page runs by (#544), kept apart from the WebView so they can be tested. */
object GarminWebPage {

    /**
     * Hosts the page may move between without leaving the app: Garmin's own (Connect and its
     * sign-in), and the two sign-in providers Garmin's sign-in page offers. Anything else is
     * opened in the phone's browser, so a stray link never turns this into a general browser.
     */
    private val inAppDomains = listOf("garmin.com", "google.com", "apple.com")

    /**
     * Schemes a page makes for itself rather than goes to — a blank frame, an inline document, a
     * generated file. Sent to the browser they would pop up an empty tab in the middle of a sign-in.
     */
    private val pageLocalSchemes = setOf("about", "data", "blob")

    /** Whether a top-level navigation to [url] stays inside the app. Only https is kept. */
    fun staysInApp(url: String): Boolean {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme in pageLocalSchemes) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return inAppDomains.any { host == it || host.endsWith(".$it") }
    }

    /**
     * The page's user agent with the WebView marks taken out, so it reads as Chrome on a phone.
     *
     * Google's sign-in refuses to run inside an app's WebView and finds one by the "; wv" token;
     * Garmin's sign-in hands off to Google's. The "Version/4.0" token is the other WebView mark.
     */
    fun mobileUserAgent(webViewUserAgent: String): String =
        webViewUserAgent
            .replace("; wv)", ")")
            .replace(" Version/4.0", "")

    /**
     * What the page's file box is answered with: the Run's file, so no picker is shown. With no
     * file it is cancelled (null) — a file box left unanswered never opens again on that page.
     */
    fun <T> chooserAnswer(file: T?): List<T>? = file?.let { listOf(it) }
}
