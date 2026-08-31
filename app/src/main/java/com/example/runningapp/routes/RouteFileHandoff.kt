package com.example.runningapp.routes

import android.content.Intent
import android.net.Uri

/**
 * One launch of the app's own screen, made when another app asks this one to open a `.gpx` (#277).
 *
 * **The rule the hand-off exists for, stated here and nowhere else.** Android keeps its own copy of
 * the intent that started a task and hands it back whenever that task is reopened — from the recents
 * list, into a fresh process, days later — and no app can reach in and edit it. So whatever is in
 * the launch that CREATES the task is what the runner gets when they tap their own app expecting
 * Home.
 *
 * That is why there are two launches and why their order is the fix: the task is created by a launch
 * carrying nothing, and the file arrives on top of it.
 *
 * Each launch describes the whole intent rather than leaving the caller to remember the flags,
 * because the flags are the fix. They are pinned by [com.example.runningapp.routes.RouteFileHandoffTest]
 * rather than by opening a file on a phone.
 */
internal sealed interface RouteFileLaunch {

    /** What the launch asks for. */
    val action: String

    /** The category to put on it, or null for none. */
    val category: String?

    /** The flags it is started with. */
    val flags: Int

    /** The file it carries, or null when it carries none. */
    val file: Uri?

    /**
     * The app opened as the launcher opens it. This is the one that may create the task, so it
     * carries no file — see above.
     */
    data object Home : RouteFileLaunch {
        override val action: String = Intent.ACTION_MAIN
        override val category: String = Intent.CATEGORY_LAUNCHER
        override val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK
        override val file: Uri? = null
    }

    /**
     * The file, delivered onto the [Home] launch rather than starting a task of its own.
     *
     * `FLAG_ACTIVITY_CLEAR_TOP` and `FLAG_ACTIVITY_SINGLE_TOP` together are what make "onto" true.
     * SINGLE_TOP alone only reuses the screen when it is the top of its task, and it often is not:
     * the file picker, the archive folder chooser and the share sheet are all started into
     * MainActivity's own task and sit above it. A runner who leaves the app with one of those open
     * and then opens a `.gpx` would get a SECOND MainActivity built on top of the stale chooser —
     * two screens bound to the service, and a Back that walks through a picker nobody asked for.
     * CLEAR_TOP finishes whatever is above the screen instead, and SINGLE_TOP then hands the file to
     * the screen itself rather than rebuilding it.
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` is what makes the file readable. The read was granted to the
     * activity Android handed the file to, and a grant belongs to the activity it was given to, so
     * without passing it on the import fails with a `SecurityException` the runner sees as "That GPX
     * couldn't be read".
     */
    data class Handover(override val file: Uri) : RouteFileLaunch {
        override val action: String = Intent.ACTION_VIEW
        override val category: String? = null
        override val flags: Int =
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}

/**
 * The launches an "Open with" on a `.gpx` becomes, in the order they must be started — see
 * [RouteFileLaunch] for why the order is the whole of it.
 *
 * An intent with no file still opens the app, which is what it did before this hand-off existed.
 * Every filter that reaches this door names both a scheme and a type, so such an intent should not
 * arrive at all; if one does, landing the runner on Home is better than a tap that appears to do
 * nothing.
 */
internal fun routeFileHandoff(file: Uri?): List<RouteFileLaunch> =
    if (file == null) listOf(RouteFileLaunch.Home)
    else listOf(RouteFileLaunch.Home, RouteFileLaunch.Handover(file))
