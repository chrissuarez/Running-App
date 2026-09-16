package com.example.runningapp

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
 * Any number of such asks become one more pass, started when the running one ends.
 *
 * **A pass covers every ask made before its body begins**, so the body's first act is to clear the
 * kept ask, under the same lock the asks take. That is the one rule, and it holds wherever the ask
 * lands: while the last pass was running, in its hand-off, or before this one has been scheduled.
 * An ask in the instant between that clear and the pass reading its list costs one more pass —
 * never a lost ask, which is the failure that matters. The hand-off itself keeps the pass marked
 * running, so two passes never run side by side.
 *
 * Handed on when the pass's [kotlinx.coroutines.Job] completes, however it completes — so a pass that
 * fails, or is cancelled before its body ever ran, still lets the next ask through.
 */
class RepeatablePass(
    private val passes: BackgroundPasses,
    private val name: String,
    private val work: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var askedWhileRunning = false

    /**
     * Starts the pass unless one is still running, in which case one more pass follows it. Returns
     * whether it started one now.
     */
    fun startUnlessRunning(): Boolean {
        synchronized(lock) {
            if (running) {
                askedWhileRunning = true
                return false
            }
            running = true
        }
        launchPass()
        return true
    }

    private fun launchPass() {
        passes.launch(name) {
            synchronized(lock) { askedWhileRunning = false }
            work()
        }.invokeOnCompletion {
            val again = synchronized(lock) {
                if (askedWhileRunning) {
                    askedWhileRunning = false
                    true
                } else {
                    running = false
                    false
                }
            }
            if (again) launchPass()
        }
    }
}
