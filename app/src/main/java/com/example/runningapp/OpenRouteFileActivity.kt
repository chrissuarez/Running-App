package com.example.runningapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.example.runningapp.routes.RouteFileLaunch
import com.example.runningapp.routes.routeFileHandoff

/**
 * The door another app opens a `.gpx` through (#54, #277).
 *
 * It owns the file's intent filters so that [MainActivity] never does. While [MainActivity] owned
 * them, tapping the app in the recents list landed the runner in the Route library being told about
 * an import they had not asked for — [com.example.runningapp.routes.RouteFileLaunch] states why, and
 * what shape the hand-off has to have because of it. Nothing was ever imported twice; see
 * [com.example.runningapp.routes.RouteImporter]. The app just went somewhere the runner did not ask
 * to go.
 *
 * This activity is kept out of the replay instead: `excludeFromRecents` so the task it is started in
 * is never offered back, and `noHistory` so it is gone the moment it has handed the file on. It
 * shows nothing and lives for one `onCreate`.
 *
 * Plain [Activity] rather than the `ComponentActivity` every other screen here uses: it has no
 * Compose content, no view model and no lifecycle to speak of, and the heavier base class would buy
 * a door nothing.
 */
class OpenRouteFileActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val file = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        // startActivities, not one call per launch: Android starts them in order into one task, so
        // the file lands on the Home launch as an onNewIntent rather than building a second
        // MainActivity.
        startActivities(routeFileHandoff(file).map { it.asIntent(this) }.toTypedArray())
        finish()
    }
}

/**
 * The one intent a [RouteFileLaunch] is: it says what to ask for, what to carry and what flags to
 * carry it under, and this only addresses it to [MainActivity].
 */
private fun RouteFileLaunch.asIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = this@asIntent.action
        this@asIntent.category?.let { addCategory(it) }
        data = this@asIntent.file
        addFlags(this@asIntent.flags)
    }
