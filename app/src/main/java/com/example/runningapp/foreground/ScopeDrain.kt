package com.example.runningapp.foreground

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/**
 * How many times [drainChildren] will look for work before it gives up and lets the teardown
 * carry on regardless.
 *
 * A fixed number of passes rather than a clock: a teardown's waits run under `NonCancellable`,
 * where a timeout is a promise the runtime cannot keep, and a pass count is the same guarantee
 * stated in terms of the thing that actually varies — how many times a producer managed to slip a
 * new write in behind the one being waited for. One late arrival is ordinary; five rounds of them
 * is a producer that is still running, which is a different fault and not one a destroy should
 * hang on.
 */
const val SCOPE_DRAIN_PASSES = 5

/**
 * Wait for a scope's work to be finished — all of it, including what turns up while waiting.
 *
 * A wait for work in flight is not over until the scope is empty. Taking a scope's children once
 * and joining that list answers a different question: what was running at the instant of the
 * snapshot. Anything launched after it — a GPS fix already queued on a looper that was asked to
 * quit safely, a run event dispatched by a session thread whose join timed out — lands behind the
 * wait and is not waited for. Draining is right whether or not the producers were stopped
 * cleanly first, which is why the teardown's waits are drains: producer quiescence is bounded and
 * a wait that depends on it being perfect is a wait that is sometimes wrong (#309).
 *
 * A drain is bounded by [SCOPE_DRAIN_PASSES] joins rather than by a clock, so a producer that
 * never stops cannot hang the destroy that is trying to shut it down.
 *
 * @param job the scope's own job, whose children are the work.
 * @param except a child to leave out. The rescue that drains its own scope is itself one of that
 *   scope's children, and a drain that joined itself would wait for ever; the coroutine passes its
 *   own job here rather than the code taking a snapshot before launching, because the snapshot is
 *   the very thing this replaces.
 * @return true if the scope came up empty, false if it still had children after the last pass.
 */
suspend fun drainChildren(
    job: Job,
    except: Job? = null,
    passes: Int = SCOPE_DRAIN_PASSES,
): Boolean {
    repeat(passes) {
        val children = job.children.filter { it !== except }.toList()
        if (children.isEmpty()) return true
        children.joinAll()
    }
    return job.children.none { it !== except }
}
