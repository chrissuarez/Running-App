package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.SortedMap

/**
 * A league table of the runner's own efforts: who placed where, the top ten of them, how many there
 * were, how the best has moved across the calendar, and all of it said out loud (#481).
 *
 * Three pages rank the same runner against themselves — a Record's (#75), a Segment's (#72) and a
 * Run's matched runs (#73) — and each used to build its own table, down to trend words copied "with
 * one word changed". A fix in one copy missed the others. So the table is written once, and a page
 * names only what it ranks: the order ([bestFirst]), the day an entry was run ([day]) and how the
 * page already words it ([dateLabel]), the number the trend plots ([plotted]) and how that number
 * reads ([valueLabel]). The drawing half was already shared ([RankedEffortRow], [TrendLineChart]);
 * this is the data half behind it.
 *
 * Nothing here decides who may compete. Every page hands in the entries it already allows; the table
 * only places them against each other and puts them into words.
 */
class LeagueTable<T>(
    /**
     * Which of two entries is the better, best first — and it must break every tie, the earlier Run
     * keeping the place. The top of the list, the gold disc and each day's point on the trend are one
     * reading of this order, so an order that left two entries tied would place them differently on
     * two reads of the same rows.
     */
    private val bestFirst: Comparator<T>,
    /** The runner's own day the entry was run on, which is where the trend puts it (#304). */
    private val day: (T) -> LocalDate,
    /**
     * That day as the page's own rows print it. Taken from the page rather than formatted here, so a
     * row and the trend's sentence about the same day cannot say it two ways.
     */
    private val dateLabel: (T) -> String,
    /**
     * The number the trend plots for an entry — seconds, metres, a pace — or null where nothing was
     * ever measured. A null is left off the trend and stays in the list: it is not a slow entry, and
     * drawn as a zero it would be a cliff the runner never ran.
     */
    private val plotted: (T) -> Double?,
    /** A plotted number read back as the thing it is, on a point and up the side of the chart. */
    val valueLabel: (Double) -> String,
    /** How the list's order is said in its title: "best" first, "quickest" first. */
    private val orderWord: String,
    /** What the trend is of, as a sentence opens: "Your quickest here". */
    private val trendSubject: String,
) {

    /** The best entry of all, or null where there are none — the same answer as the gold disc. */
    fun best(entries: List<T>): T? = entries.minWithOrNull(bestFirst)

    /**
     * The best entries, best first, cut at [LEAGUE_TOP_COUNT].
     *
     * Deeper than the record book, which keeps three: fourth to tenth is exactly what a runner
     * comparing themselves against themselves came to see.
     */
    fun top(entries: List<T>): List<Placed<T>> = entries
        .sortedWith(bestFirst)
        .take(LEAGUE_TOP_COUNT)
        .mapIndexed { index, entry ->
            Placed(
                place = index + 1,
                // Off the enum itself, the way the record book decides how deep the metals go
                // ([Medal]): a list of three written out here would be a second answer to "how many
                // places are worth a medal".
                medal = Medal.entries.getOrNull(index),
                entry = entry,
            )
        }

    /**
     * What the ranked list is called, which depends on whether it is leaving anything out.
     *
     * A list holding every effort there has ever been must not call itself a top ten: that would tell
     * the runner something was cut when nothing was, and send them hunting for a rest of the list that
     * does not exist. Where efforts really are left out, the count says how many, because "top 10" out
     * of eleven and out of two hundred are very different facts about the same ten.
     */
    fun topTitle(total: Int): String =
        if (total <= LEAGUE_TOP_COUNT) "Every effort, $orderWord first"
        else "Top $LEAGUE_TOP_COUNT of ${countLabel(total)}"

    /**
     * How many efforts there have ever been — the other half of what a best means. "4:32" on its own
     * says nothing about whether it was the best of two or of fifty.
     */
    fun countLabel(total: Int): String = if (total == 1) "1 effort" else "$total efforts"

    /**
     * How the best has moved across the calendar, oldest first — one point per day, at that day's
     * best.
     *
     * Every entry and not only the top ten, which is what makes it a trend rather than a picture of ten
     * good days. **One point per day**, because the x axis is the calendar: two entries on one date
     * have nowhere to sit apart on it, and the best is the one the runner would quote for that day.
     * **Nothing below two days**, because one point is not a line — the page shows its list alone
     * rather than an empty frame that reads as a chart that broke. Placed by the calendar rather than
     * evenly, so a two-year gap is drawn as a two-year gap.
     */
    fun trend(entries: List<T>): List<TrendPoint<T>> {
        val bestPerDay = bestEachDay(entries.filter { plotted(it) != null })
        if (bestPerDay.isEmpty()) return emptyList()

        val firstDay = bestPerDay.firstKey()
        return bestPerDay.map { (date, entry) ->
            val value = plotted(entry)!!
            TrendPoint(
                entry = entry,
                date = date,
                dayOffset = ChronoUnit.DAYS.between(firstDay, date).toInt(),
                value = value,
                dateLabel = dateLabel(entry),
                valueLabel = valueLabel(value),
            )
        }
    }

    /**
     * What the trend is, said in one sentence for a runner who is being read the page.
     *
     * A chart is a picture, and a picture says nothing out loud. The two ends are what the chart is
     * for — the stretch of calendar it covers, and whether what was done at the end beats what was
     * done at the start. Both ends are a day's best rather than a day's last, because that is what the
     * chart plots: naming a slower later attempt on the last day would read out a value the chart
     * never drew.
     */
    fun trendDescription(points: List<TrendPoint<T>>): String? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        return "$trendSubject from ${first.dateLabel} to ${last.dateLabel}: " +
            "${first.valueLabel} on the first day, ${last.valueLabel} on the latest."
    }

    private fun bestEachDay(entries: List<T>): SortedMap<LocalDate, T> {
        val best = entries
            .groupBy(day)
            .mapValues { (_, onTheDay) -> onTheDay.minWith(bestFirst) }
            .toSortedMap()
        return if (best.size < 2) sortedMapOf() else best
    }
}

/** How deep a league table's ranked list goes. */
const val LEAGUE_TOP_COUNT: Int = 10

/**
 * One entry in the ranked list: where it placed, and the entry itself.
 *
 * [medal] is the top three, in the same three metals the record book and a Run's own page hand out
 * (#49, #71) — a place is a place, and a runner should not have to learn two of them. Below third
 * there is no metal, only the number.
 */
data class Placed<T>(
    val place: Int,
    val medal: Medal?,
    val entry: T,
)

/** One day on a trend: the entry that was that day's best, where it sits, and what it was worth. */
data class TrendPoint<T>(
    val entry: T,
    val date: LocalDate,
    /** Days since the first point, which is the x the chart is drawn against. */
    val dayOffset: Int,
    val value: Double,
    val dateLabel: String,
    val valueLabel: String,
)

/**
 * How times over one piece of ground are placed against each other — quickest first, and a tie kept
 * by whoever ran it first.
 *
 * Written once and read by every page that ranks them: both Segment pages (#70, #71), a Route's own
 * page (#420) and a Run's matched runs (#73). A Segment's page calling one time the PR while the
 * Run's page hands the medal to another would be the same question answered twice.
 *
 * [time] is whatever the ground took — a time over it, or a pace along it. [rowId] is the last word
 * so the order is total: two Runs *can* carry the same start instant, and an order that left them
 * tied would place them differently on two reads of the same rows.
 */
internal fun <T, M : Comparable<M>> quickestFirst(
    time: (T) -> M,
    startedAt: (T) -> Long,
    rowId: (T) -> Long,
): Comparator<T> = compareBy(time).thenBy(startedAt).thenBy(rowId)
