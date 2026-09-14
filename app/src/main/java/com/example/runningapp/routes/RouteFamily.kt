package com.example.runningapp.routes

import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.decoded

/**
 * The rules a family of courses keeps: one route, many lengths (#421).
 *
 * The library's rules rather than the screen's, which is why they sit here and not in `ui/` (#480):
 * who a course's siblings are and which length its page lands on are true of the table whatever
 * draws it. The folding into library rows and the words on them stay with the screen
 * ([com.example.runningapp.ui.routeLibraryRows]).
 *
 * **A family is a name the runner typed** ([com.example.runningapp.data.Route.family]). Nothing
 * here reads a Route's *name*: two courses are siblings because they carry the same family text and
 * for no other reason, so "Cuckoo Trail 8k" and "Cuckoo Trail 12k" are strangers until the runner
 * says otherwise.
 */

/**
 * The name that decides who a course's siblings are, or null where it has none (#421).
 *
 * Stated once and read by everything below, so the three readers cannot come to three answers.
 * Trimmed and emptied-to-null here as well as in the write
 * ([com.example.runningapp.data.RouteDao.setRouteFamily]) — the write is the only door the runner
 * has, but a row put in the table any other way must still group with the rest rather than sit
 * ungroupable beside it.
 */
fun routeFamilyKey(route: RouteHeader): String? = route.family?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Every length of the course this one belongs to, shortest first — itself alone where it has no
 * family, or where nothing else carries the name (#421).
 *
 * Shortest first because the chips are a ladder the runner climbs as they get fitter, and a row of
 * `5k 8k 12k` reads as one. Ties are settled by id so the order cannot shuffle between two reads of
 * the same table.
 *
 * Empty only where [routeId] is not in [library] at all, which is the row having been deleted.
 */
fun routeSiblings(library: List<RouteHeader>, routeId: Long): List<RouteHeader> {
    val route = library.firstOrNull { it.id == routeId } ?: return emptyList()
    val family = routeFamilyKey(route) ?: return listOf(route)
    val siblings = library.filter { routeFamilyKey(it) == family }
    return if (siblings.size < 2) {
        listOf(route)
    } else {
        siblings.sortedWith(compareBy({ it.distanceMeters }, { it.id }))
    }
}

/**
 * When each of a family's lengths was last run, counting the Runs it **recognises** as well as the
 * Runs remembered on it (#436).
 *
 * Since #74 a course's page lists both kinds and names them the same way, so the landing has to read
 * the same history the page does. A family whose lengths were all run before the courses were
 * imported — or run without picking one at START — would otherwise open on its shortest length, the
 * "nobody has run this" fallback, with a page full of Runs one tap away.
 *
 * **Recognised is still not Routed.** Nothing here writes `ranAlongRouteId`, and nothing here claims
 * the runner set out to run a course ([runIsOnCourse], CONTEXT.md).
 * The claim is the smaller, checkable one the page already makes — this Run covered this ground — and
 * "the rung they were on last" is a question about ground covered, not about a choice made at START.
 *
 * **The later of the two wins per length**, because a Run can be both: remembered on the course and
 * recognised on it. Taking the later rather than preferring one kind means a length cannot be moved
 * *backwards* by being asked a second question.
 *
 * [courses] is what the family's own shapes came back as, and a sibling missing from it is a course
 * still owed its measurement ([com.example.runningapp.data.RouteShapeRow]) — its remembered Runs are
 * unaffected, which is the same bargain [runsOnCourse] strikes for the page.
 *
 * A Walk is absent from [shaped] rather than filtered here: a Walk is measured once and banked with
 * no shape ([com.example.runningapp.data.RunShapeRow]), so it is not the runner running this route
 * and it does not move where the family opens. That is the rule the record book already keeps, kept
 * once rather than restated.
 */
fun routeFamilyLastRuns(
    remembered: List<RouteLastRunRow>,
    courses: List<CourseShape>,
    shaped: List<ShapedRunRow>,
): List<RouteLastRunRow> {
    if (courses.isEmpty()) return remembered
    // Decoded once for the whole family rather than once per length: the shapes are the same rows
    // whichever course is asking, and a family of four lengths would otherwise decode the runner's
    // whole shaped history four times over.
    val runs = shaped.mapNotNull { row -> row.decoded()?.let { it to row.run.startTime } }
    val latest = HashMap<Long, Long>()
    val noteRunOn = { routeId: Long, startTime: Long ->
        latest[routeId] = maxOf(latest[routeId] ?: Long.MIN_VALUE, startTime)
    }
    remembered.forEach { noteRunOn(it.routeId, it.lastRunStartTime) }
    courses.forEach { course ->
        runs.forEach { (shape, startTime) ->
            if (runIsOnCourse(shape, course.shape)) noteRunOn(course.routeId, startTime)
        }
    }
    return latest.map { (routeId, startTime) -> RouteLastRunRow(routeId, startTime) }
}

/**
 * Which of a family's lengths its page opens on: **the one run most recently, and the shortest
 * where none has been run** (#421).
 *
 * The recent one because that is what the runner means by the family name today — they are working
 * their way up the ladder, and the rung they were on last is the rung they want to see. The shortest
 * as the fallback because a family nobody has run yet is a plan, and the plan starts at the bottom.
 *
 * [lastRuns] is what [routeFamilyLastRuns] made of the two histories a course has — the Runs
 * remembered on it and the Runs recognised on it (#436) — and a course missing from it has never
 * been run, by either reading.
 * Rows about courses outside this family are ignored rather than trusted, so a caller that asked a
 * wider question cannot land the page on a course it does not show.
 *
 * Ties — two lengths whose last Runs began on the very same millisecond — fall to the shorter, which
 * is the same tie-break the empty case uses rather than a second rule.
 */
fun routeFamilyLandingId(siblings: List<RouteHeader>, lastRuns: List<RouteLastRunRow>): Long? {
    if (siblings.isEmpty()) return null
    val ours = siblings.map { it.id }.toSet()
    val ranAt = lastRuns.filter { it.routeId in ours }.associate { it.routeId to it.lastRunStartTime }
    // Sorted shortest first already, so `maxByOrNull` keeping the first of equals *is* the tie-break.
    val mostRecent = siblings.filter { it.id in ranAt }.maxByOrNull { ranAt.getValue(it.id) }
    return (mostRecent ?: siblings.first()).id
}

/** Every family name the library already holds, in order, for the box that offers them (#421). */
fun routeFamilyNames(library: List<RouteHeader>): List<String> =
    library.mapNotNull { routeFamilyKey(it) }.distinct().sorted()
