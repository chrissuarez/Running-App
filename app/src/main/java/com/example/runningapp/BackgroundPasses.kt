package com.example.runningapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one door every deferrable background pass is started through (#375).
 *
 * The passes [AppContainer] runs at launch are all written the same way: they read a work list,
 * work through it an item at a time, and guard each item so a failure costs a launch and nothing
 * more. What the item guards never covered is the read that produces the list. A `launch` on a
 * scope with a `SupervisorJob` and no handler has nothing to catch what escapes it, so a throw on
 * that one line — Room on a disk under pressure at cold start — reached the default uncaught
 * handler and took the app down before the runner had seen a screen.
 *
 * Stated here once rather than a `try` per pass, because it is one rule about a whole class of
 * work: **nothing on this scope is worth a crash.** Every pass started through it writes its
 * progress down as it goes and reads it back at the next launch, so a pass that dies leaves the
 * work owed and the next launch does it. A pass that must not fail silently does not belong here.
 *
 * **An [Error] is still fatal, deliberately.** Only [Exception] is caught. An out-of-memory or a
 * broken build is not "leave it for the next launch" — the next launch would meet the same wall,
 * and a process quietly carrying on past one is a worse thing to hand a runner than a crash report.
 * A [CancellationException] is rethrown for the same reason it always is: it is the scope being
 * torn down talking, not a pass failing.
 *
 * [onFailed] is injectable only so a test can see what a pass reported; nothing but the default is
 * used in the app.
 */
class BackgroundPasses(
    private val scope: CoroutineScope,
    private val onFailed: (String, Throwable) -> Unit = { name, e ->
        Log.w("AppContainer", "The $name pass failed; leaving its work for the next launch", e)
    },
) {
    /**
     * Starts [block] as a named pass. Returns its [Job] for a caller that wants to wait on it —
     * nothing in the app does, and a caller that did would be waiting on work whose whole point is
     * that it can be left.
     */
    fun launch(name: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(name, e)
        }
    }
}
