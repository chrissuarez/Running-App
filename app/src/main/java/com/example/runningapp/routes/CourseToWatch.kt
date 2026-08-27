package com.example.runningapp.routes

import com.example.runningapp.data.RouteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The course a Run is being watched against, as it stands and as it stands again every time the
 * library moves under it (#58).
 *
 * Null throughout for a Run following no course, and null again the moment the Route is deleted —
 * which is the whole reason this is watched rather than read once at START. A Route stays the
 * runner's to edit and to delete while they are out on it, and the promise made where deleting is
 * offered is that it costs the Run nothing ([RouteDao.getRouteFlow]). The live map keeps that
 * promise by drawing nothing; this keeps it by saying nothing. A course read once would have the app
 * telling a runner they had left a line that no longer exists and the screen no longer shows.
 *
 * A Route *edited* mid-Run ends one watch and begins another, so nothing is carried over: the alerts
 * arm again the next time the runner comes within [REACHED_THE_COURSE_METERS] of the new line, and a
 * runner who had been told they were off the old line is not told they are back on this one. That is
 * the cost of following the library, and it is the right way round — a line that has changed shape
 * underneath a Run has no earlier judgement about it worth keeping.
 *
 * **Only a line that has changed shape, though.** [RouteDao.getRouteFlow] is a Room query, and Room
 * hands a query its rows again whenever the *table* is written to — renaming some other Route,
 * importing one, deleting one. None of those is this course changing, and every one of them would
 * otherwise end the watch: a runner already told they were off course would never be told they were
 * back, and a ten-second wait halfway through would start again from nothing. So the course is
 * compared, not the row, and an emission that describes the same ground keeps the watch that is
 * already reading it. The Route's name and its row are nothing to do with where the line goes.
 *
 * [reversed] is the runner's word that they set off the other way round, applied here so the course
 * is handed over in the order the Run is running it, exactly as it is handed to the map (#56). It
 * makes no difference to how far off a line the runner is; handing it over any other way is how two
 * readers of one course come to disagree about it.
 */
fun courseToWatchFlow(routeDao: RouteDao, routeId: Long?, reversed: Boolean): Flow<OffCourseWatch?> {
    if (routeId == null) return flowOf(null)
    return routeDao.getRouteFlow(routeId)
        .map { route ->
            val course = route?.let { RoutePolyline.decode(it.polyline) }.orEmpty()
            if (reversed) course.reversed() else course
        }
        .distinctUntilChanged()
        .map(OffCourseWatch::of)
}
