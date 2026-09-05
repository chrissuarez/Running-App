package com.example.runningapp.routes

import com.example.runningapp.data.KeptRoute
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.RouteShapeRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The routes table in memory, newest first, exactly as the real DAO orders it. */
class FakeRouteDao : RouteDao {
    private val rows = MutableStateFlow<List<Route>>(emptyList())
    private var nextId = 1L
    private val transaction = Mutex()

    val stored: List<Route> get() = rows.value

    /**
     * How long a lookup takes to answer, so a test can hold one open while a second caller arrives.
     *
     * Nought by default. A real lookup takes time too; this is only the amount of it a test needs to
     * be able to point at.
     *
     * What it answers is the table as it stood when it looked, and the wait is the answer coming
     * back — which is the whole of why looking and then writing is not safe. By the time a caller
     * has its answer in hand the table has moved on, and a caller that goes off to ask another app
     * what a file is called before writing has given it every chance to.
     */
    var findDelayMillis: Long = 0L

    /** The library without its lines, in the real DAO's order — see [RouteDao.getLibraryFlow]. */
    override fun getLibraryFlow(): Flow<List<RouteHeader>> = rows.map { table ->
        table
            .sortedWith(
                compareByDescending<Route> { row -> row.createdAtMillis }
                    .thenByDescending { row -> row.id }
            )
            .map { row ->
                RouteHeader(
                    id = row.id,
                    name = row.name,
                    distanceMeters = row.distanceMeters,
                    elevationGainMeters = row.elevationGainMeters,
                    createdAtMillis = row.createdAtMillis,
                    source = row.source,
                    family = row.family,
                )
            }
    }

    /**
     * One row's line, and how many times one has been asked for.
     *
     * Counted because the rule #403 states is about what a reader *holds*, and the way a reader
     * keeps to it is by asking for one line at a time and letting it go. A test that wants to prove
     * the drawing pass does that has to be able to see the asks.
     */
    val lineAsks = ArrayList<Long>()

    override suspend fun getRoutePolyline(routeId: Long): String? {
        lineAsks += routeId
        return rows.value.firstOrNull { it.id == routeId }?.polyline
    }

    override suspend fun insertRoute(route: Route): Long {
        val id = nextId++
        rows.value = rows.value + route.copy(id = id)
        return id
    }

    /**
     * The shapes written beside the courses, by the course they belong to (#74).
     *
     * Kept rather than discarded so a test can say that keeping a course measures it in the same
     * breath — the whole point of the write living inside [RouteDao.keepRoute].
     */
    val shapes = mutableMapOf<Long, RouteShapeRow>()

    override suspend fun insertRouteShape(shape: RouteShapeRow) {
        shapes[shape.routeId] = shape
    }

    /** The debt as the real query states it: a row with no shape beside it, oldest first. */
    override suspend fun coursesOwedShapes(): List<Long> =
        rows.value.map { it.id }.filterNot { it in shapes }.sorted()

    /**
     * The shaped courses, as the real read returns them — the name taken from the row rather than
     * kept beside the shape, and a shape of null left out, exactly as `SHAPED_COURSES_SQL` does.
     */
    override suspend fun shapedCourses(): List<RouteShapeCandidate> = shapes.values.mapNotNull { row ->
        val course = rows.value.firstOrNull { it.id == row.routeId } ?: return@mapNotNull null
        row.shape?.let {
            RouteShapeCandidate(
                routeId = row.routeId,
                name = course.name,
                shape = it,
                distanceMeters = row.distanceMeters,
            )
        }
    }

    override suspend fun getRoute(routeId: Long): Route? = rows.value.firstOrNull { it.id == routeId }

    /** The row without its line, watched — see [RouteDao.getRouteHeaderFlow]. */
    override fun getRouteHeaderFlow(routeId: Long): Flow<RouteHeader?> = rows.map { table ->
        table.firstOrNull { it.id == routeId }?.let { row ->
            RouteHeader(
                id = row.id,
                name = row.name,
                distanceMeters = row.distanceMeters,
                elevationGainMeters = row.elevationGainMeters,
                createdAtMillis = row.createdAtMillis,
                source = row.source,
                family = row.family,
            )
        }
    }

    /** One row, and again every time the table moves — a delete included, which is the point. */
    override fun getRouteFlow(routeId: Long): Flow<Route?> =
        rows.map { table -> table.firstOrNull { it.id == routeId } }

    override suspend fun findRouteByPolyline(polyline: String): Route? {
        val asItStandsNow = rows.value.filter { it.polyline == polyline }.minByOrNull { it.id }
        if (findDelayMillis > 0L) delay(findDelayMillis)
        return asItStandsNow
    }

    /**
     * The looking and the writing, with no other caller allowed between them — which is the whole of
     * what Room's `@Transaction` is worth to this decision, and the part a table in memory would
     * otherwise quietly not do.
     *
     * Written out here rather than inherited so that a test about two taps at once is a test about
     * the code under it: a caller that goes around this method, asking and then writing, gets the
     * doubled row it has earned.
     */
    /**
     * Run the moment a keep begins, before anything is written (#420).
     *
     * A test's window onto what the caller was *inside* when it wrote — the only way to see from
     * out here that the keep and the stamp on the source Run share one commit.
     */
    var onKeepRoute: () -> Unit = {}

    override suspend fun keepRoute(route: Route, remeasuring: Boolean): KeptRoute =
        transaction.withLock {
            onKeepRoute()
            super<RouteDao>.keepRoute(route, remeasuring)
        }

    override suspend fun remeasureRoute(
        routeId: Long,
        distanceMeters: Double,
        elevationGainMeters: Double?,
    ) {
        rows.value = rows.value.map {
            if (it.id == routeId) {
                it.copy(distanceMeters = distanceMeters, elevationGainMeters = elevationGainMeters)
            } else {
                it
            }
        }
    }

    override suspend fun renameRoute(routeId: Long, name: String) {
        rows.value = rows.value.map { if (it.id == routeId) it.copy(name = name) else it }
    }

    /**
     * The write behind [RouteDao.setRouteFamily], which is inherited so that the settling of blank
     * into null is the code under test rather than a second copy of it here.
     */
    override suspend fun writeRouteFamily(routeId: Long, family: String?) {
        rows.value = rows.value.map { if (it.id == routeId) it.copy(family = family) else it }
    }

    override suspend fun deleteRoute(routeId: Long) {
        rows.value = rows.value.filterNot { it.id == routeId }
    }


    /**
     * A row's course replaced under the same id.
     *
     * A state the real table cannot reach: a Route's line is written once, when the row is inserted
     * (the rule is stated at [com.example.runningapp.data.Route.polyline]). It is staged here all
     * the same, for `CourseAlertsTest`: what that watch has to do when the course it is following
     * changes under it is a branch of its own, and a test that could only reach it by deleting the
     * row would be testing the deletion instead.
     */
    fun replaceLine(routeId: Long, polyline: String) {
        rows.value = rows.value.map { if (it.id == routeId) it.copy(polyline = polyline) else it }
    }
}
