package com.example.runningapp.routes

import com.example.runningapp.data.KeptRoute
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteKeeping
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.isFinished
import com.example.runningapp.export.RunExportName
import java.time.ZoneId

/** What became of a Run the runner asked to keep as a course. */
sealed interface RunRouteOutcome {
    data class Saved(val routeId: Long, val name: String) : RunRouteOutcome

    /**
     * The library already held this course, drawn exactly as this Run draws it.
     *
     * [name] is what the kept Route is called, which may not be what this Run is called: a runner
     * who saved a lap in the spring and renamed it needs telling which row is the one they have.
     */
    data class AlreadySaved(val name: String) : RunRouteOutcome

    /**
     * The Run has no course in it: no fixes at all, or none that reach further across the ground
     * than the error of the fixes themselves ([ROUTE_MINIMUM_METERS]).
     *
     * Nothing is written. A treadmill Run never gets this far — the button is not offered without a
     * recorded track — so this is the outdoor Run that stopped in the first seconds, and the one
     * that recorded a standstill.
     */
    data object NoGround : RunRouteOutcome

    /**
     * The Run is still being recorded, so the course it will go over is not yet a course.
     *
     * Reachable, not defensive: History lists a Run the moment it starts, so its page can be opened
     * while the runner is still on it. Kept then, the Route would be however far they had got when
     * they looked at their phone — banked, never re-measured, and named after a Run that went twice
     * as far.
     */
    data object StillRunning : RunRouteOutcome
}

/**
 * Keeps the ground a Run went over as a Route to run again (#55).
 *
 * A Route is a plan and a Run is a recording, and this makes a new plan that happens to have been
 * traced off one: nothing in the new row points at the Run, and deleting either costs the other
 * nothing ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)).
 *
 * **The one thing written back to the Run is that it went this way** ([rememberRunAlongRoute],
 * #420). That is not a link from the plan to the recording — the Route knows nothing about it, and
 * still deletes without touching a Run — it is the Run remembering its own course, in the very
 * column START writes when the runner picks one (`RunnerSession.ranAlongRouteId`, #56). Without it
 * every course traced off a Run opens its own page with an empty history, having been made from a
 * Run that is plainly on it.
 *
 * Saving twice is saving once. The line is a Route's identity, exactly as it is for an imported file
 * ([RouteImporter]), so a runner who taps the button again — or saves the same lap they ran last
 * week and saved then — is told which row they already have rather than given a second one. Unlike
 * an import there is nothing to re-measure: an import can arrive carrying better heights than the
 * file before it, while a Run measured twice by the same rules off the same fixes can only ever say
 * what it said the first time.
 *
 * The distance banked here is the length of the line, which is not the distance shown on the Run's
 * own page and is not meant to be. The Run counted the ground it covered, pauses and lost signal and
 * all; this is how far the course goes for whoever follows it, thinned to its shape and joined
 * across the coffee stop ([runAsCourse]).
 */
class RunRouteSaver(
    private val routeDao: RouteDao,
    /**
     * Writes the kept course's id onto the Run it was traced from, unless that Run already names a
     * course (#420) — [com.example.runningapp.data.SessionDao.rememberRunAlongRoute], where the
     * "unless" lives inside the write and every reason for that is argued.
     *
     * Handed in rather than a second DAO on the constructor, the bargain
     * [com.example.runningapp.ui.SegmentsViewModel] makes with `onSegmentSaved`: this class is about
     * the library, and what it wants of `sessions` is one sentence. No default, so a wiring that
     * forgot it would not compile.
     */
    private val rememberRunAlongRoute: suspend (sessionId: Long, routeId: Long) -> Unit,
    /**
     * Runs the keep and the stamp as one commit (#420) — `database.withTransaction`, wired in
     * `AppContainer`/`MainActivity`, the shape [com.example.runningapp.data.SessionRepository]
     * already uses.
     *
     * The two writes are one fact. A course kept off a Run *is* a Run on that course, so a commit
     * that landed the row and lost the stamp would open the new course's page with an empty history
     * and nothing on screen to say why. Read-then-write cannot close that: the gap is not in the
     * deciding, it is between the two commits, and only one commit removes it.
     *
     * No default, for [rememberRunAlongRoute]'s reason: a default would run the block as-is, which
     * is atomic-looking code that is not atomic, and nothing would fail to compile to say so.
     */
    private val inTransaction: suspend (suspend () -> KeptRoute) -> KeptRoute,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun save(
        run: RunnerSession,
        trackPoints: List<TrackPoint>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RunRouteOutcome {
        if (!run.isFinished()) return RunRouteOutcome.StillRunning

        val course = runAsCourse(trackPoints)
        // What counts as a course at all is one question, asked here and at the file door in the
        // same place, so the two cannot come to disagree about it (#397) — see [holdsACourse].
        if (!course.holdsACourse()) return RunRouteOutcome.NoGround

        // Offered rather than asked about first: the line is the course's identity, and the library
        // itself decides in one go whether this one is new — asking and then writing would leave a
        // gap for a second tap to walk into, and the runner would have two rows of the same course.
        // What comes back names the row they have, which after a rename is not the Run's own name.
        //
        // The row itself is built by [asRoute], the one place that decides which reading of the walk
        // feeds which number, so this door and the file door cannot come to disagree (#354).
        //
        // The Run's own name, in the same words it is exported under, so a course saved off a Run
        // and a file shared from it cannot disagree about which evening they came from (#304).
        val name = RunExportName.runName(run, zoneId)
        // Both writes or neither ([inTransaction]): the row and the stamp on the Run it was traced
        // from are one fact, and half of it is a course that opens with an empty history.
        val kept = inTransaction {
            val kept = routeDao.keepRoute(
                course.asRoute(name, createdAtMillis = now(), source = RouteSource.FROM_RUN),
                // A Run brings nothing new to a course already kept, so the row is left as it
                // stands. An import can arrive carrying better heights than the file before it; a
                // Run measured twice by the same rules off the same fixes can only ever repeat
                // itself.
                remeasuring = false,
            )
            // After the row is settled, and for both answers. A course the library already held is
            // the very ground this Run covered — the line is a Route's identity, so an identical
            // line is not a lookalike — and a runner who saves the same lap twice should find both
            // Runs on its page. A Run already stamped with a course keeps it: the write says so,
            // not this call site.
            rememberRunAlongRoute(run.id, kept.id)
            kept
        }

        return if (kept.keeping == RouteKeeping.KEPT) {
            RunRouteOutcome.Saved(routeId = kept.id, name = kept.name)
        } else {
            RunRouteOutcome.AlreadySaved(name = kept.name)
        }
    }
}
