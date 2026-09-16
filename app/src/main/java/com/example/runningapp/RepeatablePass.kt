package com.example.runningapp

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A background pass that may be asked for again and again in one process, but never runs twice at
 * the same time (#444).
 *
 * The launch passes are once per process ([LaunchPasses]), and that suits work a previous process
 * owed: nothing about it changes while this process lives. It does not suit work that waits on
 * something outside the phone. The weather backfill was once per process too, so a phone that was
 * offline at the one launch left every Run owed until Android next killed the process — hours on a
 * phone that keeps it warm, with the runner opening the app all the while.
 *
 * The guard is what a latch was standing in for: **not while one is already running.** Two passes
 * over the same list would ask the service twice for every Run. A second attempt after the first has
 * finished is the whole point.
 *
 * **An ask that lands while a pass is running is kept, not dropped.** The running pass read its list
 * before that ask, and a Run it already tried while the phone was offline is not tried again inside
 * it — so the runner coming back to the app with signal is exactly the ask that must still be paid.
 * Any number of such asks become one more pass, started when the running one ends. A pass that
 * starts clears the kept ask, because it reads its list after every ask made before it.
 *
 * Cleared when the pass's [kotlinx.coroutines.Job] completes, however it completes — so a pass that
 * fails, or is cancelled before its body ever ran, still lets the next ask through.
 */
class RepeatablePass(
    private val passes: BackgroundPasses,
    private val name: String,
    private val work: suspend () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val askedWhileRunning = AtomicBoolean(false)

    /**
     * Starts the pass unless one is still running, in which case one more pass follows it. Returns
     * whether it started one now.
     */
    fun startUnlessRunning(): Boolean {
        if (!running.compareAndSet(false, true)) {
            askedWhileRunning.set(true)
            // The running pass may have ended between the check and the mark, and seen no mark.
            if (!running.get() && askedWhileRunning.getAndSet(false)) return startUnlessRunning()
            return false
        }
        askedWhileRunning.set(false)
        passes.launch(name, work).invokeOnCompletion {
            running.set(false)
            if (askedWhileRunning.getAndSet(false)) startUnlessRunning()
        }
        return true
    }
}
