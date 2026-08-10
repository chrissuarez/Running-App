package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The routes table in memory, newest first, exactly as the real DAO orders it. */
class FakeRouteDao : RouteDao {
    private val rows = MutableStateFlow<List<Route>>(emptyList())
    private var nextId = 1L

    val stored: List<Route> get() = rows.value

    override fun getAllRoutesFlow(): Flow<List<Route>> =
        rows.map { it.sortedWith(compareByDescending<Route> { row -> row.createdAtMillis }.thenByDescending { row -> row.id }) }

    override suspend fun insertRoute(route: Route): Long {
        val id = nextId++
        rows.value = rows.value + route.copy(id = id)
        return id
    }

    override suspend fun getRoute(routeId: Long): Route? = rows.value.firstOrNull { it.id == routeId }

    override suspend fun findRouteByPolyline(polyline: String): Route? =
        rows.value.filter { it.polyline == polyline }.minByOrNull { it.id }

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
}
