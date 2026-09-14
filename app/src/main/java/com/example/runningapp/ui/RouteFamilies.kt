package com.example.runningapp.ui

import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.routes.routeFamilyKey
import java.util.Locale

/**
 * A family of courses as the screens draw it: one route, many lengths (#421).
 *
 * Pure and outside the composables, the bargain [routeRowSubtitle] and [routeRunsUi] make — what
 * the runner reads is the feature, so it is pinned by unit tests rather than by opening the library
 * on a phone. Who a course's siblings are and which length a page lands on are the library's rules,
 * not the screen's, and live in `routes/RouteFamily.kt` (#480).
 *
 * **A family of one is drawn as a plain course.** The runner may put a family name on a course
 * before drawing its siblings, or take the last sibling away; either leaves one row that has nothing
 * to be grouped with. Calling it "1 length" of a family would print a heading where there is only a
 * course, so the library shows the course itself and its own page offers no chips. The name stays on
 * the row, so drawing the second length turns it back into a family with no re-typing.
 */

/**
 * One row of the library: a lone course, or a whole family folded into a single line (#421).
 *
 * The library must not grow a repeated name per length, which is the whole reason this exists. A
 * runner with three Cuckoo Trails wants one Cuckoo Trail in the list.
 *
 * [openRouteId] is which course tapping the row goes to. For a family it is the shortest sibling
 * and it is only a starting point — the page settles for itself which length to land on
 * ([com.example.runningapp.routes.routeFamilyLandingId]), because that answer needs the runner's
 * history and the library has none.
 */
data class RouteLibraryRow(
    /** The family's name, or the lone course's own name. */
    val title: String,
    val subtitle: String,
    val thumbnail: RouteThumbnail?,
    val openRouteId: Long,
    /**
     * The family this row stands for, or null for a lone course.
     *
     * What tells the two kinds of row apart. The screen reads it to decide whether the bin forgets
     * one course or asks about a family, and a test reads it to say which row is which.
     */
    val family: String?,
    /** How many lengths the family holds — always 1 for a lone course. */
    val lengthCount: Int,
    /** The lone course itself, or null for a family row. Carried so the bin still has a course. */
    val route: RouteHeader?,
)

/**
 * The library folded into one row per family, in the order the library already came in (#421).
 *
 * A family takes the place of its **newest** member, because that is where the runner last saw it:
 * importing the 12 km version of a course they already keep should move the family to where a new
 * import goes, not leave it buried at the position of the 5 km one they saved last spring.
 *
 * The rows arrive newest first ([com.example.runningapp.data.RouteDao.getLibraryFlow]) and that
 * order is kept exactly — this does not re-sort, it only folds.
 */
fun routeLibraryRows(rows: List<RouteRowUi>): List<RouteLibraryRow> {
    val familyOf = { row: RouteRowUi -> routeFamilyKey(row.route) }
    // Counted over the whole library first, because whether a row is a family row depends on
    // whether a *second* row shares its name, which is not knowable while walking one row.
    val members = rows.groupBy(familyOf)
    val alreadyFolded = HashSet<String>()
    return rows.mapNotNull { row ->
        val family = familyOf(row)
        val siblings = family?.let { members[it] }.orEmpty()
        if (family == null || siblings.size < 2) {
            RouteLibraryRow(
                title = row.route.name,
                subtitle = routeRowSubtitle(row.route),
                thumbnail = row.thumbnail,
                openRouteId = row.route.id,
                family = null,
                lengthCount = 1,
                route = row.route,
            )
        } else if (!alreadyFolded.add(family)) {
            // A sibling of a family already folded in above it: the family has its row.
            null
        } else {
            val lengths = siblings.map { it.route.distanceMeters }
            RouteLibraryRow(
                title = family,
                subtitle = routeFamilySubtitle(siblings.size, lengths.min(), lengths.max()),
                // The longest sibling's drawing, because it covers the most ground: a family's
                // picture should show the whole of where it goes, and the shortest length is a piece
                // of that. Not "the one the row opens" — the page re-chooses that, so matching it
                // here would be a promise this row cannot keep.
                thumbnail = siblings.maxByOrNull { it.route.distanceMeters }?.thumbnail,
                openRouteId = siblings.minByOrNull { it.route.distanceMeters }!!.route.id,
                family = family,
                lengthCount = siblings.size,
                route = null,
            )
        }
    }
}

/**
 * What a family's row says under its name: how many lengths, and how far they run between.
 *
 * Two places rather than the one the ticket sketched, because every other distance in the app is
 * written to two ([routeDistanceLabel]) and a course's length is the number the runner compares
 * against the ones on the rows around it.
 *
 * A family whose lengths happen to measure the same prints one distance rather than a range from a
 * number to itself.
 */
fun routeFamilySubtitle(lengthCount: Int, shortestMeters: Double, longestMeters: Double): String {
    val lengths = if (lengthCount == 1) "1 length" else "$lengthCount lengths"
    // Both ends written by [routeDistanceLabel] rather than formatted again here, so a change to
    // how a course's length is printed moves the whole range rather than one end of it. The unit is
    // dropped from the first, because "5.00 km–12.00 km" says the same thing twice.
    val shortest = routeDistanceLabel(shortestMeters)
    val longest = routeDistanceLabel(longestMeters)
    // The two *labels* compared, not the two measurements: 5,000.1 m and 5,000.2 m are different
    // numbers that this line prints identically, and "5.00–5.00 km" is a range the runner would
    // read as a fault in the app. A range is only worth printing where its ends read apart.
    val range = if (shortest == longest) {
        shortest
    } else {
        shortest.removeSuffix(" km") + "–" + longest
    }
    return "$lengths · $range"
}

/**
 * How a length is written on a chip: `5k`, `8k`, `12.5k` (#421).
 *
 * Short on purpose — a row of chips has to fit across a phone at 1.3× text, and the two places the
 * rest of the app uses would make `12.00k` of a number the runner only needs to tell apart from its
 * siblings. The honest distance is still printed in full under the map once a chip is tapped.
 *
 * A trailing nought is dropped so a 8.04 km course reads as `8k`, which is what the runner calls it.
 *
 * One length on its own. A *row* of chips must go through [routeLengthChipLabels], which is the
 * only thing that can see whether this rounding leaves two chips saying the same word.
 */
fun routeLengthChipLabel(distanceMeters: Double, decimals: Int = 1): String {
    val km = String.format(Locale.UK, "%.${decimals}f", distanceMeters / 1000.0)
    return km.trimEnd('0').trimEnd('.') + "k"
}

/** How many decimals a chip may grow to before the name is what tells two lengths apart. */
private val CHIP_DECIMALS = listOf(1, 2, 3)

/**
 * The whole row of chips, one label per sibling, in the order given — every one of them different
 * (#421).
 *
 * A chip is the only thing the runner taps to choose a length, so two chips reading `5k` is a
 * choice nobody can make: not by eye, and not by ear, because the label is also all a screen reader
 * gets. Siblings 5.01 km and 5.04 km round to the same word at one decimal, and the rounding is
 * what the short label is *for* — so the row is written at whatever precision separates it,
 * one decimal where that is enough and more where it is not.
 *
 * The whole row moves together rather than only the pair that collided: `5k` sitting beside
 * `5.01k` would read as a course that is exactly five, which is a claim the first chip's rounding
 * never made.
 *
 * Two courses measuring the same to the metre are not separable by any number of decimals, so
 * there the runner's own name for each is appended — the last thing left about a course that can
 * differ. Where even the names match, the chip's own place in the row is appended, which is unique
 * by construction: nothing about the two rows tells them apart any more, so the row itself does.
 *
 * The rule this keeps is one sentence and it is kept here rather than at each step: **the labels
 * this returns are all different**. Each step is only an attempt at a *readable* way of meeting it,
 * and the last step meets it whatever the courses hold — a number cannot be the same as the number
 * beside it.
 */
fun routeLengthChipLabels(siblings: List<RouteHeader>): List<String> {
    val attempts = CHIP_DECIMALS.map { decimals ->
        siblings.map { routeLengthChipLabel(it.distanceMeters, decimals) }
    } + listOf(
        siblings.map { routeLengthChipLabel(it.distanceMeters, CHIP_DECIMALS.last()) + " " + it.name }
    )
    attempts.forEach { labels -> if (labels.distinct().size == labels.size) return labels }
    // Every readable attempt has left two chips saying the same word. Counting the row cannot: the
    // suffix goes on *every* chip, so two labels that were equal now end in different numbers, and
    // a label that only looks like one already suffixed gets a number of its own after it.
    return attempts.last().mapIndexed { index, label -> "$label (${index + 1})" }
}


/** The label on the box that puts a course in a family, and the words under it when it is empty. */
const val ROUTE_FAMILY_FIELD_LABEL = "Family"

/**
 * What the family box says when it is empty.
 *
 * Says what a family is *for* rather than what it is, because a runner meeting the box for the first
 * time has one course and no reason to guess.
 */
const val ROUTE_FAMILY_EMPTY_HINT =
    "Give the same family name to two courses to group them as lengths of one route."

/** What the page calls the row of chips, for a screen reader that has no row to look at. */
const val ROUTE_FAMILY_LENGTHS_LABEL = "Lengths"

/**
 * Which library row shows one course, or -1 where none does (#458).
 *
 * What the Routes screen scrolls to when an import has just added a course. Pure and here rather
 * than in the composable, the bargain every other word on these screens makes: the answer is pinned
 * by a unit test rather than by importing a file on a phone.
 *
 * **Why a lookup rather than "the top".** The library arrives newest first
 * ([com.example.runningapp.data.RouteDao.getLibraryFlow]) and a course imported a moment ago is the
 * newest thing in it, so today the answer is always 0. Written as "scroll to the top" that would be
 * a claim about the query's ORDER BY made in a file that cannot see it, and it would go quietly
 * wrong the day the library is ordered any other way. The index of the row that opens the course is
 * true whatever the order.
 *
 * A course is matched by the row that **opens** it. A freshly imported course carries no family
 * ([com.example.runningapp.routes.asRoute] writes none), so it is a row of its own and this always
 * finds it.
 *
 * **-1 is the class of "no row on this list opens that course", not one cause of it.** Two things
 * put a course in that class, and a caller that reads it as only the first will be wrong at the
 * second. The course may have gone from the library since it was asked for. Or it may be *in* the
 * library and folded: a family is drawn as one row that opens its shortest sibling
 * ([routeLibraryRows]), so every longer sibling is on the list and opened by none of its rows.
 * Either way the caller has nothing to scroll to, and drawing that as a row at 0 would send the
 * runner to a course they did not ask about.
 */
fun routeLibraryRowShowing(rows: List<RouteLibraryRow>, routeId: Long): Int =
    rows.indexOfFirst { it.openRouteId == routeId }
