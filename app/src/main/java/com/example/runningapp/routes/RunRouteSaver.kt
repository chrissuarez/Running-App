package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.isFinished
import com.example.runningapp.export.RunExportName
import com.example.runningapp.recording.SessionRecorder
import java.time.ZoneId

/**
 * How far a Run has to reach across the ground before it holds a course worth keeping (#55).
 *
 * The same width, and for the same reason, as the drawing on a History row
 * ([com.example.runningapp.analysis.routeThumbnailOf]): a fix is accepted at up to
 * [SessionRecorder.ACCURACY_THRESHOLD_METERS] of error, so two accepted fixes from a runner who
 * never moved can sit twice that apart. Anything that never reaches outside that width is the error
 * alone, and a Route made of it would be a course the runner could never follow.
 *
 * Measured across rather than along, because the Run this turns away is the one that stood still
 * and the length of *that* line is the length of ten minutes of wandering — see [courseSpanMeters].
 */
private val ROUTE_MINIMUM_METERS = 2 * SessionRecorder.ACCURACY_THRESHOLD_METERS

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
 * The Run is untouched by it. A Route is a plan and a Run is a recording, and this makes a new plan
 * that happens to have been traced off one — nothing is written back to the Run, nothing in the new
 * row points at it, and deleting either costs the other nothing
 * ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)).
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
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun save(
        run: RunnerSession,
        trackPoints: List<TrackPoint>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): RunRouteOutcome {
        if (!run.isFinished()) return RunRouteOutcome.StillRunning

        val course = runAsCourse(trackPoints)
        if (course.line.size < 2) return RunRouteOutcome.NoGround
        if (courseSpanMeters(course.line) < ROUTE_MINIMUM_METERS) return RunRouteOutcome.NoGround

        // Offered rather than asked about first: the line is the course's identity, and the library
        // itself decides in one go whether this one is new — asking and then writing would leave a
        // gap for a second tap to walk into, and the runner would have two rows of the same course.
        // What comes back names the row they have, which after a rename is not the Run's own name.
        val polyline = RoutePolyline.encode(course.line)
        // The Run's own name, in the same words it is exported under, so a course saved off a Run
        // and a file shared from it cannot disagree about which evening they came from (#304).
        val name = RunExportName.runName(run, zoneId)
        val kept = routeDao.keepRoute(
            Route(
                name = name,
                // Along the line that was kept, and up the hills that were recorded — the two are
                // measured off different readings of the walk, and [RunCourse] is where that is
                // argued.
                distanceMeters = routeDistanceMeters(course.line),
                elevationGainMeters = routeElevationGainMeters(course.asRecorded),
                polyline = polyline,
                createdAtMillis = now(),
                source = RouteSource.FROM_RUN,
            )
        )
        return if (kept.alreadyKept) {
            RunRouteOutcome.AlreadySaved(name = kept.name)
        } else {
            RunRouteOutcome.Saved(routeId = kept.id, name = kept.name)
        }
    }
}
