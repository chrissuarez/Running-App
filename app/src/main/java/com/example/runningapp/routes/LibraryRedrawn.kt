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
 * That "exactly what its own file would make" is the whole truth for a row that came in from a file:
 * such a row holds every point the file held, so redrawing it here is the arithmetic a re-import
 * does. It is not the whole truth for a row saved from a Run *before* #354. That row was thinned
 * from places that had not been snapped first, and thinning only ever removes — so a place the old
 * rule dropped is gone from the row, and no pass over what is left can put it back. Where that
 * dropped place is one today's rule would have kept (a place sitting within a centimetre of the
 * two-metre line, the case `OneRunOneRouteTest` pins), that Run's own GPX handed back still draws a
 * line one point longer than the row, and the library still keeps it twice. Rare, not made worse by
 * this pass, and not closed by it either: #402 holds it, because both remedies — a second permanent
 * identity rule, or a link from a Route back to the Run it was saved from — are larger than an
 * upgrade.
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
 *
 * **The one rule about what the pass may hold: nothing here grows with how long the library's lines
 * are, only with how many rows it has.** A line kept before #354 holds every point its file held,
 * and a file may hold two hundred thousand of them — megabytes of text in one row, and more again
 * while it is decoded. The rows therefore arrive as a [Sequence], each line fetched as it is reached
 * and dropped when the next one is: one line at a time is in hand, and what is kept from it is the
 * *thinned* line and a handful of numbers. Anything that failed this rule would fail it inside the
 * upgrade, which rolls back and is tried again at every launch — an app that never opens, for the
 * one runner whose library is the reason the pass exists.
 *
 * The sequence must arrive in id order, which is checked rather than assumed: the id order is what
 * decides which row of a collision survives, and sorting it here would mean holding all of it at
 * once. [com.example.runningapp.data.READ_LIBRARY_AS_KEPT_SQL] reads it that way.
 */
fun libraryRedrawn(rows: Sequence<RouteAsKept>): LibraryRedrawn {
    val redrawn = ArrayList<RouteRedrawn>()
    val merged = ArrayList<RouteMerged>()
    // The row already holding each redrawn line, and what it will end up banking, beside whether
    // that row was already right. Kept as a map from the *new* line, so the second row to land on
    // one is recognised as it arrives — and the new line is a thinned one, so the map grows with
    // the shape of the library rather than with the size of the lines that made it.
    val holderOfLine = LinkedHashMap<String, RowSoFar>()

    var lastId = Long.MIN_VALUE
    for (row in rows) {
        require(row.id > lastId) {
            "The library is redrawn in id order: id ${row.id} arrived after $lastId"
        }
        lastId = row.id
        val points = RoutePolyline.decode(row.polyline)
        if (points.isEmpty()) continue

        val line = courseOf(points).line
        val polyline = RoutePolyline.encode(line)
        val held = holderOfLine[polyline]
        if (held != null) {
            merged += RouteMerged(lostId = row.id, keptId = held.row.id)
            // The absorbed row's climb, but only where the survivor has none to lose (#355).
            if (held.row.elevationGainMeters == null && row.elevationGainMeters != null) {
                holderOfLine[polyline] = held.copy(
                    row = held.row.copy(elevationGainMeters = row.elevationGainMeters),
                )
            }
            continue
        }
        // Whether anything moved is decided here, while the row as it was found is still to hand,
        // and only the answer is kept: comparing later would mean holding every line the library
        // arrived with — see [libraryRedrawn]'s rule about what the pass may hold.
        holderOfLine[polyline] = RowSoFar(
            row = RouteRedrawn(
                id = row.id,
                polyline = polyline,
                distanceMeters = routeDistanceMeters(line),
                elevationGainMeters = row.elevationGainMeters,
            ),
            lineWasAlreadyRight = polyline == row.polyline,
            distanceAsKept = row.distanceMeters,
            climbAsKept = row.elevationGainMeters,
        )
    }

    // Written back only where something actually moved, so a library already in the new form — every
    // library that never held a Route before the upgrade, which is most of them — is not touched at
    // all. Compared against the row as it was found, not against the row beside it.
    for (soFar in holderOfLine.values) {
        val moved = !soFar.lineWasAlreadyRight ||
            soFar.row.distanceMeters != soFar.distanceAsKept ||
            soFar.row.elevationGainMeters != soFar.climbAsKept
        if (moved) redrawn += soFar.row
    }

    return LibraryRedrawn(redrawn = redrawn.sortedBy { it.id }, merged = merged)
}

/**
 * A row redrawn, beside just enough of the row as it was found to say whether anything moved.
 *
 * The line as it was found is deliberately *not* here. Whether the line moved is a yes or a no, and
 * that is settled the moment the row is redrawn — so the answer is kept and the line itself is let
 * go, which is what stops the pass holding the whole library's text at once.
 */
private data class RowSoFar(
    val row: RouteRedrawn,
    val lineWasAlreadyRight: Boolean,
    val distanceAsKept: Double,
    val climbAsKept: Double?,
)
