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
 * Cleared when the pass's [kotlinx.coroutines.Job] completes, however it completes — so a pass that
 * fails, or is cancelled before its body ever ran, still lets the next ask through.
 */
class RepeatablePass(
    private val passes: BackgroundPasses,
    private val name: String,
    private val work: suspend () -> Unit,
) {
    private val running = AtomicBoolean(false)

    /** Starts the pass unless one is still running. Returns whether it started one. */
    fun startUnlessRunning(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        passes.launch(name, work).invokeOnCompletion { running.set(false) }
        return true
    }
}
