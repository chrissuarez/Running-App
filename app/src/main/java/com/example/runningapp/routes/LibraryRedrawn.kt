package com.example.runningapp.routes

/**
 * What the upgrade does to a library kept before #354, and the whole of the deciding of it (#399).
 *
 * #354 made both doors into the library draw a Route's line the one way: every place snapped to the
 * seven decimal places the row keeps it at, a place recorded twice written once, and the whole
 * thinned to its shape at two metres ([courseOf]). Rows already in the library were not written that
 * way. An imported row holds *every point its file held*, unthinned, and a Run-saved row was thinned
 * from places that had not been snapped first.
 *
 * A Route's identity is the exact text of its line ([com.example.runningapp.data.RouteDao.findRouteByPolyline]),
 * so a row drawn by the old rule is a *different Route* from the one its own file makes today. Hand
 * the app a file it already imported and it finds nothing to re-measure and keeps a second row,
 * under the same name (#304), with nothing to tell the two apart — the very fault #354 set out to
 * close, arriving by the upgrade instead of by the two doors.
 *
 * **So the library is redrawn once, at the upgrade, and there is one identity rule afterwards.** The
 * alternative — leaving the table alone and teaching the importer to also look for the old
 * encoding — is the one ADR 0014 argues against: it would put a second way of saying "the same
 * course" into the library permanently, to spare a one-off pass over a table with a handful of rows
 * in it.
 *
 * Pure and free of Android and of SQL, so all of the below is pinned by `LibraryRedrawnTest` rather
 * than found on a phone. `MIGRATION_41_42` is the cursor loop that hands it the rows and writes back
 * what it decides.
 */
data class LibraryRedrawn(
    /** The rows whose stored numbers or line moved. A row already in the new form is not here. */
    val redrawn: List<RouteRedrawn>,
    /** The rows that turned out to be a second copy of another and are dropped. */
    val merged: List<RouteMerged>,
)

/** One row of `routes` as the upgrade finds it. The name and dates are not read: they never move. */
data class RouteAsKept(
    val id: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val polyline: String,
)

/**
 * One row rewritten where it stands.
 *
 * The name, the source and `createdAtMillis` are absent because the upgrade does not touch them: the
 * name is the runner's, and when the row was kept is a fact about their library, not about the rule
 * that draws its line.
 */
data class RouteRedrawn(
    val id: Long,
    val polyline: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
)

/** One row that redraws onto a line [keptId] already holds, so [lostId] goes and [keptId] stays. */
data class RouteMerged(val lostId: Long, val keptId: Long)

/**
 * The library as #354's rule draws it, out of the library as it was kept.
 *
 * Four decisions, and each of them is one the upgrade is forced to make rather than one it chooses.
 *
 * **The line is redrawn through [courseOf], the same function both doors use.** Nothing else would
 * do: the point of the pass is that afterwards there is one rule, so it has to be *that* rule and
 * not a copy of it. The row's stored line is already written to seven places, so snapping it again
 * changes nothing, and what the redraw actually does is thin an imported row to its shape.
 *
 * **The distance is measured again, along the line the row now holds.** #354's rule is that a
 * Route's distance is the distance along the line kept ([Course.line]), and a row left with its old
 * number would be stating a distance along a line it no longer has. Re-measuring is also what makes
 * the pass finish the job: afterwards each row is exactly what its own file would make today, so
 * handing that file back is a true no-op rather than a re-measure the runner is told about. The cost
 * is named in ADR 0014 and is a handful of metres over a long course — the corners the thinning cut.
 *
 * **The climb is left exactly as it was banked.** It cannot be measured again: a row keeps no height
 * profile, only the line ([RoutePolyline]), and the file it came from is gone. This is the one
 * number the pass cannot bring into line with what a re-import would produce, and it does not
 * pretend otherwise — a re-import of that file will re-measure the climb when it arrives, which is
 * the remedy ADR 0014 already names.
 *
 * **Two rows landing on one line become one, and the lower `id` is the one that stays.** That is the
 * pair #354 was about — a Run saved as a course and that same Run's GPX handed back — so the pass
 * would be pointless if it left them side by side. The lower `id` because it is the tiebreak the app
 * itself already uses when a line somehow has two rows
 * ([com.example.runningapp.data.RouteDao.findRouteByPolyline] orders by `id`), so the row that
 * survives here is the row the library would have sent an importer to anyway; and because it is the
 * one the runner has had longest, under the name they have been seeing for it.
 *
 * A survivor with no climb takes the first climb any of the rows it absorbed had. That is #355's
 * rule, and for #355's reason: a null is silence about a course that may well go over a hill, not a
 * statement that it is flat, so it does not get to take a real answer away.
 *
 * A row whose line will not decode at all is left completely alone and takes no part in the
 * merging. There is no line there to redraw, and an upgrade is the wrong moment to decide what a
 * damaged row meant.
 */
fun libraryRedrawn(rows: List<RouteAsKept>): LibraryRedrawn {
    // In id order, because the id order is what decides which row of a collision survives. Sorted
    // here as well as asked for in the read ([READ_LIBRARY_AS_KEPT_SQL]) because the two say
    // different things: the query says what the upgrade reads, and this says what the answer
    // depends on — so a caller handing the rows over in any order gets the same library back.
    val inIdOrder = rows.sortedBy { it.id }

    val redrawn = ArrayList<RouteRedrawn>()
    val merged = ArrayList<RouteMerged>()
    // The row already holding each redrawn line, and what it will end up banking. Kept as a map from
    // the new line so the second row to land on one is recognised as it arrives.
    val holderOfLine = LinkedHashMap<String, RouteRedrawn>()

    for (row in inIdOrder) {
        val points = RoutePolyline.decode(row.polyline)
        if (points.isEmpty()) continue

        val line = courseOf(points).line
        val polyline = RoutePolyline.encode(line)
        val held = holderOfLine[polyline]
        if (held != null) {
            merged += RouteMerged(lostId = row.id, keptId = held.id)
            // The absorbed row's climb, but only where the survivor has none to lose (#355).
            if (held.elevationGainMeters == null && row.elevationGainMeters != null) {
                holderOfLine[polyline] = held.copy(elevationGainMeters = row.elevationGainMeters)
            }
            continue
        }
        holderOfLine[polyline] = RouteRedrawn(
            id = row.id,
            polyline = polyline,
            distanceMeters = routeDistanceMeters(line),
            elevationGainMeters = row.elevationGainMeters,
        )
    }

    // Written back only where something actually moved, so a library already in the new form — every
    // library that never held a Route before the upgrade, which is most of them — is not touched at
    // all. Compared against the row as it was found, not against the row beside it.
    val asKept = inIdOrder.associateBy { it.id }
    for (row in holderOfLine.values) {
        val was = asKept.getValue(row.id)
        val moved = row.polyline != was.polyline ||
            row.distanceMeters != was.distanceMeters ||
            row.elevationGainMeters != was.elevationGainMeters
        if (moved) redrawn += row
    }

    return LibraryRedrawn(redrawn = redrawn.sortedBy { it.id }, merged = merged)
}
