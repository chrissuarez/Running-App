package com.example.runningapp.routes

import com.example.runningapp.data.KeptRoute
import com.example.runningapp.data.RouteKeeping
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.runShapeRowOf
import com.example.runningapp.segments.RunShape

/**
 * Shared by the route tests (#480): one piece of ground and a Run over it, and the two answers a
 * door gets back from the library, stated once rather than copied into each test class.
 */

/** A line about 2 km north from latitude [at], so two of them at different [at] are different ground. */
internal fun lineAt(at: Double): String = RoutePolyline.encode(
    listOf(
        RoutePoint(at, -0.1, elevationMeters = null),
        RoutePoint(at + 0.02, -0.1, elevationMeters = null),
    )
)

/** The shape of [lineAt]'s ground. */
internal fun shapeAt(at: Double): RunShape = routeShapeOf(RoutePolyline.decode(lineAt(at)))!!

/** One Run over [shape]'s ground, as the shaped read hands it over. */
internal fun runOver(shape: RunShape, sessionId: Long, startTime: Long) = ShapedRunRow(
    run = RouteRunRow(
        sessionId = sessionId,
        startTime = startTime,
        ranAtUtcOffsetSeconds = 0,
        durationSeconds = 300,
        movingTimeSeconds = 300,
        distanceKm = shape.distanceMeters / 1_000.0,
    ),
    shape = runShapeRowOf(sessionId, shape).shape!!,
    shapeDistanceMeters = shape.distanceMeters,
)

/** A course the library had no row for, now kept as a new one. */
internal fun added(id: Long, name: String) = RouteOutcome.Kept(KeptRoute(id, name, RouteKeeping.KEPT))

/** The one row this library already held for the line offered, answered under [name]. */
internal fun FakeRouteDao.alreadyHeld(name: String) =
    RouteOutcome.Kept(KeptRoute(stored.single().id, name, RouteKeeping.ALREADY_KEPT))
