package com.example.runningapp.routes

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.courseThumbnailOf
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.asCourseShape
import com.example.runningapp.data.decoded
import com.example.runningapp.segments.RunShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Everything one course's page lists under it, from one read (#420, #74).
 *
 * The course travels with the Runs because the best-time band is measured against the course's own
 * length and the recognising against its own shape, so a row taken a moment apart from the Runs
 * could rank them against a course the runner has since renamed, re-measured or deleted.
 */
data class RunsOnRoute(
    val course: RouteHeader,
    /** The Runs remembered on the course and the ones recognised on it, each once ([runsOnCourse]). */
    val runs: List<RouteRunRow>,
)

/**
 * The runner's library of courses and the rules it keeps (#480): who a course's siblings are, which
 * length a family's page lands on, and what a family name, a rename, a flip and a delete write.
 *
 * Out of the screen's view model so the rules can be stated — and tested — with no screen in front
 * of them. The view model keeps what only a screen has: what is on show, what is being imported,
 * and which course the list was asked to reach.
 *
 * **No course's line is ever held here, and never two at once** — the rule and its sizes are at
 * [com.example.runningapp.data.Route.polyline] (#403). The library is listed without its lines
 * ([RouteDao.getLibraryFlow]), and a line is fetched only to be drawn from and let go as soon as it
 * has been.
 *
 * Nothing is held at all: the table is the one copy of the truth, and every answer below is read
 * from it when it is asked for.
 */
class RouteLibrary(
    private val routeDao: RouteDao,
    /**
     * Every finished Run remembered on one course, watched (#420) —
     * [com.example.runningapp.data.SessionDao.getRunsAlongRouteFlow], and only that.
     *
     * A function rather than the whole `SessionDao`, the bargain `onSegmentSaved` makes in
     * [com.example.runningapp.ui.SegmentsViewModel]: what the library wants of `sessions` is one
     * question. No default, so a wiring that forgot it would not compile rather than quietly show
     * every course an empty history.
     */
    private val runsAlongRoute: (routeId: Long) -> Flow<List<RouteRunRow>>,
    /**
     * When each of a family's lengths was last run, asked once when a page opens (#421) —
     * [com.example.runningapp.data.SessionDao.lastRunOnRoutes], and only that. No default, for
     * [runsAlongRoute]'s reason: a wiring that forgot it would land every family on its shortest.
     */
    private val lastRunOnRoutes: suspend (routeIds: List<Long>) -> List<RouteLastRunRow>,
    /**
     * One course's shape, watched — what Runs on its ground are recognised by (#74) —
     * [com.example.runningapp.data.RouteShapeDao.getCourseShapeFlow], and only that.
     *
     * Never the line itself: a shape is five places, and a line is the one column in the app that
     * can be megabytes. Null while a course is still owed its measurement.
     */
    private val courseShape: (routeId: Long) -> Flow<RouteShapeCandidate?>,
    /**
     * Every finished Run that holds a shape, watched — the field a course recognises its Runs from
     * (#74). [com.example.runningapp.data.RunShapeDao.getShapedRunsForCoursesFlow], and only that.
     */
    private val shapedRuns: Flow<List<ShapedRunRow>>,
    /** Where a course is measured, drawn or decoded — anywhere but the thread drawing the screen. */
    private val measuring: CoroutineDispatcher = Dispatchers.Default,
) {

    /** The library as it is stored, minus the lines, newest first ([RouteDao.getLibraryFlow]). */
    val routes: Flow<List<RouteHeader>> = routeDao.getLibraryFlow()

    /**
     * Every family name the library already holds, for the box that offers them (#421).
     *
     * Read off the library rather than asked of the table as a question of its own: the names *are*
     * the library's families, so a separate query would be a second answer that could disagree with
     * the rows on screen.
     */
    val familyNames: Flow<List<String>> = routes.map { routeFamilyNames(it) }

    /**
     * One course, watched: a rename reaches its own title, and a delete made elsewhere empties it.
     *
     * The row without its line ([RouteDao.getRouteHeaderFlow]) — the line comes back on its own from
     * [line], because it never changes and the row does.
     */
    fun route(routeId: Long): Flow<RouteHeader?> = routeDao.getRouteHeaderFlow(routeId)

    /**
     * One course's line, ready to draw — read once, because a Route's line is written once and never
     * rewritten ([com.example.runningapp.data.Route.polyline]).
     *
     * Empty for a row that has gone, which a page draws as no map rather than as an empty course.
     * Decoded on [measuring]: a course kept before #354 holds every point its file held.
     */
    suspend fun line(routeId: Long): List<MapFix> = withContext(measuring) {
        routeDao.getRoutePolyline(routeId)
            ?.let { RoutePolyline.decode(it).map { point -> MapFix(point.latitude, point.longitude) } }
            .orEmpty()
    }

    /**
     * The drawing beside each of [routeIds] in the library (#59), null where there is no shape
     * worth drawing — and null where the row has gone since it was listed, since there is nothing to
     * draw.
     *
     * **One line at a time** (#403): each is asked for, drawn from, and let go before the next id is
     * reached, so this holds one course's text however many are asked about. What comes back is the
     * drawing, which is a few dozen points whatever the course — the drawing samples any line down
     * before it thins it, so what one costs is bounded whatever the file held (`courseThumbnailOf`).
     */
    suspend fun thumbnailsOf(routeIds: List<Long>): Map<Long, RouteThumbnail?> =
        withContext(measuring) {
            routeIds.associateWith { id ->
                routeDao.getRoutePolyline(id)?.let { polyline ->
                    courseThumbnailOf(RoutePolyline.decode(polyline).asShape())
                }
            }
        }

    /**
     * Every Run on one course, remembered or recognised, with the course they are measured against
     * — null where the row has gone, since a Run on ground the library no longer keeps is nothing a
     * page can rank.
     *
     * One read of four sources: the row, the Runs remembered on it, its shape, and every shaped Run.
     * They must be one answer rather than four taken a moment apart — see [RunsOnRoute].
     */
    fun runsOn(routeId: Long): Flow<RunsOnRoute?> =
        combine(
            routeDao.getRouteHeaderFlow(routeId),
            runsAlongRoute(routeId),
            courseShape(routeId),
            shapedRuns,
        ) { course, remembered, shape, shaped ->
            course?.let { RunsOnRoute(it, runsOnCourse(remembered, shaped, shape?.decoded())) }
        }

    /**
     * Every length of one course's family, shortest first — itself alone where it has none (#421).
     *
     * Watched, because the chips move under an open page: a length imported, deleted, or given this
     * very family name on this very page has to appear without the runner leaving and coming back.
     * Folded by the same [routeFamilyKey] the library row uses, so the chips and the row settle on
     * one answer to "how many lengths is this".
     */
    fun siblings(routeId: Long): Flow<List<RouteHeader>> =
        routes.map { library -> routeSiblings(library, routeId) }

    /**
     * Which of a family's lengths the page should open on — see [routeFamilyLandingId] (#421).
     *
     * Asked once, when the page opens, rather than watched: it settles where the runner lands, and a
     * page that re-landed every time a Run finished would move the course out from under them. Null
     * is the course having gone from the library.
     *
     * Since #436 it reads the Runs each length **recognises** as well as the Runs remembered on it,
     * which is the history the page itself prints ([routeFamilyLastRuns]). The rows are five places
     * and two numbers each ([com.example.runningapp.data.RunShapeRow]), never a line, so the read is
     * a few kilobytes and a few hundred comparisons, done on [measuring]. The page waits on it by
     * choice: drawing one length and re-landing once the read came back would move the course out
     * from under a runner who has already started reading it.
     *
     * **This family's own shape debt is paid before the shapes are read** (#440). The library's
     * shapes are backfilled by a pass started at launch on a scope of its own, so on the first
     * launch after they shipped — or after a pass the runner cut short — a length with no shape yet
     * reads as one nothing has ever been run on. Because the answer is settled once and never
     * re-landed, it is paid here rather than waited on: a pass cut short never reports itself paid,
     * so a page that waited for "paid" could wait for ever. See
     * [RouteDao.takeTheShapesStillOwedBy], which also names the half of the debt this knowingly
     * leaves — a Run never shaped is still absent from the recognising.
     */
    suspend fun landingSibling(routeId: Long): Long? = withContext(measuring) {
        val siblings = routeSiblings(routes.first(), routeId)
        if (siblings.size < 2) return@withContext siblings.firstOrNull()?.id
        // Before the shapes are read, not after: this decides where the runner lands and then has no
        // further say, so a shape arriving a moment later is a shape this answer never sees (#440).
        routeDao.takeTheShapesStillOwedBy(siblings.map { it.id })
        // The shapes as they stand, not watched: `first()` on the flows a page already watches is
        // the one-shot read of them, rather than the same rows asked for again under another name.
        val courses = siblings.mapNotNull { sibling -> courseShape(sibling.id).first()?.asCourseShape() }
        routeFamilyLandingId(
            siblings,
            routeFamilyLastRuns(
                remembered = lastRunOnRoutes(siblings.map { it.id }),
                courses = courses,
                shaped = shapedRuns.first(),
            ),
        )
    }

    /**
     * Puts a course in a family, or takes it out of one (#421).
     *
     * A blank box is no family, and it is [RouteDao.setRouteFamily] that settles that — so the rule
     * holds for every writer of the table. Unlike [rename], an unchanged value is still written:
     * the write is one short column, and comparing first would mean deciding here what "unchanged"
     * means about a value the table trims.
     */
    suspend fun setFamily(routeId: Long, family: String?) = routeDao.setRouteFamily(routeId, family)

    /**
     * Turns a course round for good, or back again (#466). No question asked first: the same button
     * undoes it.
     */
    suspend fun flip(routeId: Long) = routeDao.flipRoute(routeId)

    /**
     * Renames a course. A blank name is no name, so an empty box leaves the course called what it
     * was called; the surrounding spaces are a slip of the keyboard, not part of the name.
     */
    suspend fun rename(route: RouteHeader, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == route.name) return
        routeDao.renameRoute(route.id, trimmed)
    }

    /**
     * Forgets a course.
     *
     * It takes nothing else with it. A Route has no key into `sessions` and none out of it, so a Run
     * that followed this course keeps its own recording of where it went, which was never this row.
     */
    suspend fun delete(routeId: Long) = routeDao.deleteRoute(routeId)
}

/**
 * Every Run on one course: the ones remembered on it and the ones recognised on it, each named once
 * (#74).
 *
 * A Run can be both — the runner picked the course and then ran it — and it is one Run, so the
 * remembered row is the one kept. They carry the same columns either way ([ShapedRunRow] embeds the
 * very row the remembered read returns), so which copy survives changes nothing the page prints; it
 * is settled here rather than left to chance so that two reads of the same history cannot come back
 * in different orders.
 *
 * [course] null is a course with no shape to recognise anything by — one still owed its measurement
 * at the first launch after this shipped, or a line too short to hold a route ([routeShapeOf]). Its
 * remembered Runs are unaffected, because those were written down rather than recognised.
 */
fun runsOnCourse(
    remembered: List<RouteRunRow>,
    shaped: List<ShapedRunRow>,
    course: RunShape?,
): List<RouteRunRow> {
    if (course == null) return remembered
    val alreadyNamed = remembered.mapTo(mutableSetOf()) { it.sessionId }
    return remembered + shaped.filter { row ->
        row.run.sessionId !in alreadyNamed && row.decoded()?.let { runIsOnCourse(it, course) } == true
    }.map { it.run }
}
