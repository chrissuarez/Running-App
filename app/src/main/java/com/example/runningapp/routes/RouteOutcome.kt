package com.example.runningapp.routes

import com.example.runningapp.data.KeptRoute
import com.example.runningapp.data.RouteKeeping

/**
 * What became of a course offered to the library, by either door: a GPX file handed over (#54,
 * [RouteImporter]) or a Run the runner asked to keep (#55, [RunRouteSaver]) (#480).
 *
 * One type for both doors because the library's answer is one answer. Both doors hand their course
 * to the one keep ([com.example.runningapp.data.RouteDao.keepRoute]), and what that keep comes back
 * with is carried as it is ([Kept]) rather than translated into a second vocabulary per door. The
 * doors differ only in what can stop a course before it reaches the table, and in the words the
 * runner is told ([com.example.runningapp.ui.routeOutcomeMessage]).
 */
sealed interface RouteOutcome {
    /**
     * The course reached the library, and [kept] says what the library made of it: a new row, the
     * row already holding this line, or that row re-measured ([RouteKeeping]).
     *
     * [KeptRoute.name] is the kept row's name, which for a course already held is the runner's name
     * for it and not the file's or the Run's — the name the runner will find it under.
     */
    data class Kept(val kept: KeptRoute) : RouteOutcome

    /**
     * The course covers no ground: no places at all, or none that reach further across the ground
     * than the error of a fix ([ROUTE_MINIMUM_METERS]). Nothing is written.
     *
     * Both doors, and one outcome for both, because it is one test asked in one place
     * ([holdsACourse], #397) — the rule cannot drift apart between them.
     */
    data object NoGround : RouteOutcome

    /** The file door only: the file could not be read as a course at all. Nothing is written. */
    data class FileRefused(val reason: GpxRefusal) : RouteOutcome

    /**
     * The Run door only: the Run is still being recorded, so the course it will go over is not yet
     * a course. Nothing is written.
     *
     * Reachable, not defensive: History lists a Run the moment it starts, so its page can be opened
     * while the runner is still on it. Kept then, the Route would be however far they had got when
     * they looked at their phone — banked, never re-measured, and named after a Run that went twice
     * as far.
     */
    data object StillRunning : RouteOutcome
}

/**
 * The course this outcome added to the library as a new row, or null where nothing was added (#458).
 *
 * Only [RouteKeeping.KEPT] wrote a row. A course the library already held, a re-measure, and every
 * refusal leave the list exactly as it was.
 */
val RouteOutcome.addedRouteId: Long?
    get() = (this as? RouteOutcome.Kept)?.kept?.takeIf { it.keeping == RouteKeeping.KEPT }?.id
