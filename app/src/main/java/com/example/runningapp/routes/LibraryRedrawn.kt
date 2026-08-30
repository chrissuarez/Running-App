package com.example.runningapp.routes

/** One row of `routes` as the upgrade finds it. The name and dates are not read: they never move. */
data class RouteAsKept(
    val id: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val polyline: String,
)

/**
 * One thing the upgrade does to one row, handed over the moment it is decided (#403).
 *
 * A step rather than a list of them because of the rule this pass lives under: no reader may hold
 * two lines at once ([com.example.runningapp.data.Route.polyline]). So each row's redrawn line
 * leaves the pass as it is decided and is let go, rather than being kept in a list until the pass
 * ends and every one of them with it.
 *
 * The steps are given in the order they must be carried out.
 */
sealed interface RouteRedrawStep

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
) : RouteRedrawStep

/** One row that redraws onto a line [keptId] already holds, so [lostId] goes and [keptId] stays. */
data class RouteMerged(val lostId: Long, val keptId: Long) : RouteRedrawStep

/**
 * A climb moved onto the row that survived a merge, from the row it absorbed (#355).
 *
 * Its own step rather than a field on [RouteRedrawn] because the survivor is decided, and written,
 * long before the row that hands it a climb arrives — that is what handing each row over as it is
 * decided costs, and it costs nothing else. A survivor whose line and distance were already right is
 * never redrawn at all, so for that row this is the only write there is.
 */
data class RouteClimbBanked(val id: Long, val elevationGainMeters: Double) : RouteRedrawStep

/**
 * The library as #354's rule draws it, out of the library as it was kept.
 *
 * A library kept before #354 is redrawn once, here, and the whole of the deciding of it is here (#399).
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
 * than found on a phone. `MIGRATION_41_42` is the cursor loop that hands it the rows and carries out
 * what it decides.
 *
 * **Four decisions**, and each of them is one the upgrade is forced to make rather than one it chooses.
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
 * A survivor with no climb takes the first climb any of the rows it absorbed had, as a
 * [RouteClimbBanked] step of its own. That is #355's rule, and for #355's reason: a null is silence
 * about a course that may well go over a hill, not a statement that it is flat, so it does not get
 * to take a real answer away.
 *
 * A row whose line will not decode at all is left completely alone and takes no part in the
 * merging. There is no line there to redraw, and an upgrade is the wrong moment to decide what a
 * damaged row meant.
 *
 * **The rule this pass lives under is [com.example.runningapp.data.Route.polyline]'s first: no
 * reader may hold two lines at once** (#403). It is stated there, with the sizes; what it costs
 * here is the whole shape of this function.
 *
 * - The rows arrive as a [Sequence], each stored line fetched as it is reached and dropped when the
 *   next one is.
 * - The steps leave as a [Sequence] too, each row's redrawn line handed to the writer and let go.
 * - What stays behind between rows is [RoutePolyline.digestOf] each surviving line and two numbers,
 *   so the pass grows with how many rows the library has and not with how long its lines are.
 *
 * Broken here it is broken inside the upgrade, which rolls back and is tried again at every
 * launch — an app that never opens, for the one runner whose library is the reason the pass exists.
 *
 * The sequence must arrive in id order, which is checked rather than assumed: the id order is what
 * decides which row of a collision survives, and sorting it here would mean holding all of it at
 * once. [com.example.runningapp.data.READ_LIBRARY_AS_KEPT_SQL] reads it that way.
 *
 * Nothing is decided until the returned sequence is walked, and it may be walked only once — it
 * fetches each stored line as it goes, which is the whole of why the rows are a sequence too.
 */
fun libraryRedrawn(rows: Sequence<RouteAsKept>): Sequence<RouteRedrawStep> = sequence {
    // The row holding each redrawn line, found by a digest of that line rather than by the line
    // itself: sixty-four characters a row, so the pass grows with the shape of the library and not
    // with the size of the lines that made it.
    val holderOfLine = HashMap<String, Survivor>()

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
        val digest = RoutePolyline.digestOf(polyline)
        val held = holderOfLine[digest]
        if (held != null) {
            yield(RouteMerged(lostId = row.id, keptId = held.id))
            // The absorbed row's climb, but only where the survivor has none to lose (#355).
            val climb = row.elevationGainMeters
            if (held.climb == null && climb != null) {
                held.climb = climb
                yield(RouteClimbBanked(id = held.id, elevationGainMeters = climb))
            }
            continue
        }
        holderOfLine[digest] = Survivor(id = row.id, climb = row.elevationGainMeters)
        // Whether anything moved is decided here, while the row as it was found is still to hand.
        // The climb is not in the comparison because the redraw never touches it: a climb that
        // moves does so later, when a merge hands one over, and says so as its own step.
        val distanceMeters = routeDistanceMeters(line)
        val moved = polyline != row.polyline || distanceMeters != row.distanceMeters
        if (moved) {
            yield(
                RouteRedrawn(
                    id = row.id,
                    polyline = polyline,
                    distanceMeters = distanceMeters,
                    elevationGainMeters = row.elevationGainMeters,
                )
            )
        }
    }
}

/**
 * A row that has survived to hold one line, and the climb it holds as things stand.
 *
 * The line itself is deliberately not here — see the rule at [libraryRedrawn] about what the pass
 * may hold. The climb is, and it is mutable, because whether a later row's climb may be taken
 * depends on whether an earlier one already was (#355).
 */
private class Survivor(val id: Long, var climb: Double?)
