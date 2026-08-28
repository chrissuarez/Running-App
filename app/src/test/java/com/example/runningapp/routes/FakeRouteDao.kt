package com.example.runningapp.routes

import com.example.runningapp.data.KeptRoute
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
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

    override fun getAllRoutesFlow(): Flow<List<Route>> =
        rows.map { it.sortedWith(compareByDescending<Route> { row -> row.createdAtMillis }.thenByDescending { row -> row.id }) }

    override suspend fun insertRoute(route: Route): Long {
        val id = nextId++
        rows.value = rows.value + route.copy(id = id)
        return id
    }

    override suspend fun getRoute(routeId: Long): Route? = rows.value.firstOrNull { it.id == routeId }

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
    override suspend fun keepRoute(route: Route, remeasuring: Boolean): KeptRoute =
        transaction.withLock { super<RouteDao>.keepRoute(route, remeasuring) }

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

    override suspend fun deleteRoute(routeId: Long) {
        rows.value = rows.value.filterNot { it.id == routeId }
    }

    /**
     * A row's course replaced under the same id, which is what re-importing a kept Route does.
     *
     * The real DAO reaches this state through [keepRoute], which matches a file to a Route by its
     * line and then re-measures the row it found. There is no one query to imitate, so a test that
     * cares only that the row's line moved says so directly.
     */
    fun replaceLine(routeId: Long, polyline: String) {
        rows.value = rows.value.map { if (it.id == routeId) it.copy(polyline = polyline) else it }
    }
}
