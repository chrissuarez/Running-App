package com.example.runningapp.export

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.example.runningapp.BuildConfig

/**
 * Garmin Connect's import page, inside the app, with the Run's FIT file handed to its file box
 * (#544).
 *
 * A browser cannot fill a web page's file box; a WebView can, by answering
 * [WebChromeClient.onShowFileChooser] with the file itself. So the runner taps the page's upload box
 * and the Run's file is already in it, with no picker shown.
 *
 * Everything this page decides — which hosts stay inside it, how it presents itself, what the file
 * box is answered with — is [GarminWebPage]'s, so it can be tested off the phone. What is left here
 * is the WebView plumbing: the sign-in cookie kept between visits, so the runner signs in once, and
 * Back walking the page's own history before it closes.
 */
class GarminUploadActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Run's file, granted to this activity by the intent that opened it. Null still opens
        // the page — the runner can pick a file by hand — but the file box is then cancelled.
        val fitFile: Uri? = intent.data

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(this).apply { fitsSystemWindows = true }
        setContentView(webView)

        // Kept between visits so the runner signs in once. Third-party cookies too: Garmin's sign-in
        // runs on its own host and in frames, and a sign-in whose cookie is thrown away is a
        // sign-in every time.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = GarminWebPage.mobileUserAgent(userAgentString)
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Frames inside a page (a captcha, the sign-in widget) load wherever they load;
                // only where the page itself goes is ruled on.
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (GarminWebPage.staysInApp(url)) return false
                openOutside(request.url)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                val answer = GarminWebPage.chooserAnswer(fitFile)
                filePathCallback.onReceiveValue(answer?.toTypedArray())
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(GARMIN_IMPORT_URL)
        }
        if (savedInstanceState == null && fitFile != null) {
            Toast.makeText(this, "Tap Garmin's upload box — this run's file goes in by itself.", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w("GarminUpload", "Nothing would open $uri", e)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        // Written to disk now, not whenever WebView gets round to it: the app being swiped away
        // straight after signing in must not cost the runner the sign-in.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Opens the in-app Garmin page with [fitFile] ready for its file box. */
        fun intent(context: Context, fitFile: Uri): Intent =
            Intent(context, GarminUploadActivity::class.java)
                .setData(fitFile)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
