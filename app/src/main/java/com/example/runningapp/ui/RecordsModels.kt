package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.analysis.RecordUnit
import com.example.runningapp.data.RecordEffortRow
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.ranOn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * What the Records section of the Progress screen says (#75).
 *
 * Pure and outside the composables, the same bargain the Segments screens make
 * ([segmentEffortsUi]): the words and the order are the feature, and they are worth pinning in a
 * test rather than read off a phone.
 *
 * **Nothing here detects anything.** Every number is a claim
 * [com.example.runningapp.analysis.bestEffortsOf] already measured and the record book already
 * banked ([com.example.runningapp.data.RunEffortRow]) — so who may compete, what a treadmill Run
 * holds and what a Walk holds are settled long before this file sees a row. What is decided here is
 * only how those claims are placed against each other and how they are read out, and even the
 * placing is the book's own rule said again ([bestFirst]) rather than a second opinion.
 */

/** How deep the ranked list on a Record's own page goes. */
const val RECORD_TOP_COUNT: Int = 10

/** One Run's claim at one Record, as the Records section prints it. */
data class RecordEffortUi(
    val sessionId: Long,
    val type: RecordType,
    /** Seconds or metres, as [RecordType.unit] says — kept beside the words it was printed as. */
    val value: Double,
    /** The day the runner ran it, in their own day rather than the phone's (#304). */
    val date: LocalDate,
    val dateLabel: String,
    /** The claim itself: a time at six of the seven Records, a distance at the longest run. */
    val valueLabel: String,
    /**
     * How quickly the ground went by, or null where the Record does not have any.
     *
     * Only the five distances carry one, and it is arithmetic on the claim rather than anything read
     * off the Run: the effort covered exactly that distance in exactly that time, which is the whole
     * of what a pace is. The two totals have no such pair — a Run's longest hour says nothing about
     * how far it went, and its longest distance is not a time at all — and a pace borrowed from the
     * Run's own average would be a different measurement wearing this one's clothes.
     */
    val paceLabel: String?,
)

/** One Record in the grid: what it is, and the best ever done at it. */
data class RecordSlotUi(
    val type: RecordType,
    /** Null where nobody has ever contested it — an empty slot, which is not an error. */
    val best: RecordEffortUi?,
)

/**
 * The Records grid as the Progress screen is handed it (#75): the seven slots, and whether what
 * they were read off is the whole of history yet.
 *
 * The two travel together rather than as two flows the screen collects side by side, because they
 * are one answer: a grid drawn from a slice of history with the flag arriving a frame later is
 * exactly the wrong picture this exists to prevent.
 *
 * [measuring] and a filled [slots] never come back together — see
 * [com.example.runningapp.data.SessionRepository.recordsBeingMeasuredFlow] for what the flag keys
 * on, and [RECORDS_MEASURING_MESSAGE] for what the runner is told while it stands.
 */
data class RecordsGridUi(
    val slots: List<RecordSlotUi> = emptyList(),
    val measuring: Boolean = false,
)

/**
 * What the Records section says while history is still being measured against the book (#75).
 *
 * Said rather than left blank, and said the same way in the grid and on a Record's own page. The
 * one launch after an upgrade that added the deeper rows has to re-measure every stored track
 * before any of these numbers means "all time", which is minutes of work; a runner who opened
 * Progress in the middle of it and found the section gone would think their records had been lost.
 *
 * No count and no bar. What is left to measure is a number of Runs, not a share of the wait, and a
 * bar that could only guess would be a promise about a time nobody knows. This says what is
 * happening and that it finishes on its own, which is the whole of what the runner can act on.
 */
const val RECORDS_MEASURING_MESSAGE: String =
    "Still measuring your runs. Your records will be here once that finishes — it can take a " +
        "few minutes, and it carries on in the background."

/**
 * The whole Records grid: every Record, in the enum's own order, with the all-time best at each.
 *
 * Every Record whether it has been run or not, because the grid is the shape of what there is to
 * aim at. A runner who has never gone ten kilometres should see the ten kilometre slot standing
 * empty rather than a grid that quietly leaves out the distances they have not reached.
 *
 * Read off the enum rather than a list written here, the same way the record book decides what it
 * contests ([RecordType.bestEffortDistances]): an eighth Record added to the enum appears in this
 * grid without anything else being told.
 */
fun recordSlots(
    rows: List<RecordEffortRow>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordSlotUi> {
    val byType = rows.groupBy { it.type }
    return RecordType.entries.map { type ->
        val atType = byType[type].orEmpty()
        RecordSlotUi(
            type = type,
            // The same order the ranked list places by, so the number on the grid and the gold on
            // the Record's own page are one answer rather than two readings a moment apart.
            best = atType.minWithOrNull(bestFirst(type))?.toUi(zone),
        )
    }
}

/** One effort in the ranked list: where it placed, and the row the page prints for it. */
data class RecordRankedEffortUi(
    val place: Int,
    /**
     * The top three, in the three metals the record book and a Run's own page hand out (#49, #71) —
     * a place is a place, and a runner should not have to learn two of them. Below third there is no
     * metal, only the number.
     */
    val medal: Medal?,
    val effort: RecordEffortUi,
)

/**
 * The best efforts ever run at one Record, best first, cut at [RECORD_TOP_COUNT] (#75).
 *
 * Deeper than the record book, which is the whole reason these rows are banked: beyond bronze the
 * book remembers nothing, and fourth to tenth place is exactly what a runner comparing themselves
 * against themselves wants to see.
 *
 * **A tie leaves the place with the earlier Run**, which is the record book's own rule
 * ([com.example.runningapp.analysis.recordBookOf]): a record is that Run's until somebody actually
 * beats it, and matching a time you already ran is not beating it. Said by session id, as the book
 * says it, so the medals in this list and the medals on a Run's own page cannot be broken apart by
 * two different tie-breaks.
 */
fun recordTopEfforts(
    rows: List<RecordEffortRow>,
    type: RecordType,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordRankedEffortUi> = rows
    .filter { it.type == type }
    .sortedWith(bestFirst(type))
    .take(RECORD_TOP_COUNT)
    .mapIndexed { index, row ->
        RecordRankedEffortUi(
            place = index + 1,
            // Off the enum itself, the way the record book decides how deep the metals go
            // ([Medal]): a list of three written out here would be a second answer to "how many
            // places are worth a medal".
            medal = Medal.entries.getOrNull(index),
            effort = row.toUi(zone),
        )
    }

/**
 * What the ranked list is called, which depends on whether it is leaving anything out.
 *
 * A page holding every effort there has ever been must not call itself a top ten: that would tell
 * the runner something was cut when nothing was, and send them hunting for a rest of the list that
 * does not exist. The wording is the Segments page's ([segmentTopTitle]) with one word changed —
 * "best" rather than "quickest", because the longest run is not a time.
 */
fun recordTopTitle(total: Int): String =
    if (total <= RECORD_TOP_COUNT) "Every effort, best first"
    else "Top $RECORD_TOP_COUNT of ${recordEffortCountLabel(total)}"

/** How many Runs have ever contested a Record — the other half of what a best time means. */
fun recordEffortCountLabel(efforts: Int): String =
    if (efforts == 1) "1 effort" else "$efforts efforts"

/**
 * What a Record's own page says where nobody has ever contested it.
 *
 * The longest time is the one Record every finished run contests, because every run has a clock: an
 * empty page there really does mean there are no finished runs yet, and it can say so.
 *
 * **The longest run cannot say the same** (#75). A distance has to be *measured* before it counts
 * ([com.example.runningapp.analysis.bestEffortsOf]) — off a route the run recorded, or off a
 * treadmill distance the runner typed in — so a run whose route was lost, or a treadmill run nobody
 * has told how far it went, is a finished run that contests the longest time and not the longest
 * distance. Telling that runner there are no finished runs yet would be plainly untrue and send
 * them looking for runs the app is showing them elsewhere, so this page names what is missing —
 * a measured distance — rather than guessing at why.
 */
fun recordEmptyMessage(type: RecordType): String = when (type) {
    RecordType.LONGEST_DISTANCE ->
        "No run with a measured distance yet. A run that recorded a route, or a treadmill run you " +
            "have typed a distance into, takes this record."
    RecordType.LONGEST_DURATION ->
        "No finished runs yet. Your first one takes this record."
    else ->
        "You have not covered ${type.label.removePrefix("Fastest ")} in a run yet. Every run you " +
            "save is measured against it."
}

/** One day on a Record's trend: when it was, where it sits on the axis, and what was done. */
data class RecordTrendPoint(
    val sessionId: Long,
    val date: LocalDate,
    /** Days since the first point, which is the x the chart is drawn against. */
    val dayOffset: Int,
    val value: Double,
    val dateLabel: String,
    val valueLabel: String,
)

/**
 * How the runner's best at one Record has moved across the calendar, oldest first — one point per
 * day, at that day's best (#75).
 *
 * Every effort and not only the top ten, which is what makes it a trend rather than a picture of ten
 * good days: a runner getting steadily quicker wants to see the line, and a line drawn through their
 * ten best times would show almost none of it.
 *
 * One point per day, and nothing below two days, is the rule both trend charts already keep
 * ([bestEachDay]). Placed by the calendar rather than evenly, so a two-year gap is drawn as a
 * two-year gap — even spacing would make the chart's own claim a lie about the runner's own history.
 */
fun recordTrendPoints(
    rows: List<RecordEffortRow>,
    type: RecordType,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordTrendPoint> {
    val atType = rows.filter { it.type == type }
    val bestPerDay = bestEachDay(
        atType,
        day = { ranOn(it.startTime, it.ranAtUtcOffsetSeconds, zone) },
        better = bestFirst(type),
    )
    if (bestPerDay.isEmpty()) return emptyList()

    val firstDay = bestPerDay.firstKey()
    return bestPerDay.map { (date, row) ->
        RecordTrendPoint(
            sessionId = row.sessionId,
            date = date,
            dayOffset = ChronoUnit.DAYS.between(firstDay, date).toInt(),
            value = row.value,
            dateLabel = RECORD_DATE_FORMAT.format(date),
            valueLabel = recordValueLabel(type, row.value),
        )
    }
}

/**
 * What a Record's trend chart is, said in one sentence for a runner who is being read the page.
 *
 * A chart is a picture, and a picture says nothing out loud. The two ends are what the chart is for
 * — the stretch of calendar it covers, and whether what is done at the end of it beats what was done
 * at the start.
 *
 * Both ends are a day's best rather than a day's last, because that is what the chart plots
 * ([recordTrendPoints]).
 */
fun recordTrendDescription(type: RecordType, points: List<RecordTrendPoint>): String? {
    if (points.isEmpty()) return null
    val first = points.first()
    val last = points.last()
    return "Your ${type.label} from ${first.dateLabel} to ${last.dateLabel}: " +
        "${first.valueLabel} on the first day, ${last.valueLabel} on the latest."
}

/** The values up the side of a Record's trend chart, read back as the thing they are. */
fun recordTrendValueLabel(type: RecordType, value: Float): String =
    recordValueLabel(type, value.toDouble())

/**
 * Which of two claims at one Record is the better one — the record book's own direction
 * ([RecordType.lowerIsBetter]) with the book's own tie-break after it.
 *
 * A comparator rather than a sort written out at each of the three places that need one, because the
 * grid's best, the gold disc in the ranked list and the first point on the trend are the same claim
 * about the same Record and must never be three different rows.
 */
private fun bestFirst(type: RecordType): Comparator<RecordEffortRow> =
    compareBy<RecordEffortRow> { if (type.lowerIsBetter) it.value else -it.value }
        // The earlier Run keeps the place. Ids and not start times, for the book's own reason: an id
        // is what the medal rows carry, so the two orders cannot part company.
        .thenBy { it.sessionId }

private fun RecordEffortRow.toUi(zone: ZoneId): RecordEffortUi {
    val day = ranOn(startTime, ranAtUtcOffsetSeconds, zone)
    return RecordEffortUi(
        sessionId = sessionId,
        type = type,
        value = value,
        date = day,
        dateLabel = RECORD_DATE_FORMAT.format(day),
        valueLabel = recordValueLabel(type, value),
        paceLabel = recordPaceLabel(type, value),
    )
}

/** The claim itself, said in the Record's own unit. */
private fun recordValueLabel(type: RecordType, value: Double): String = when (type.unit) {
    RecordUnit.SECONDS -> formatDuration(value.roundToLong())
    RecordUnit.METERS -> String.format(Locale.UK, "%.2f km", value / 1_000.0)
}

/**
 * How quickly the distance went by, where the Record is one.
 *
 * Off [RecordType.distanceMeters] rather than off anything the Run recorded: the claim *is* that
 * distance covered in that time, so this is the same number said the other way round.
 */
private fun recordPaceLabel(type: RecordType, value: Double): String? {
    val meters = type.distanceMeters ?: return null
    if (value <= 0.0 || meters <= 0.0) return null
    return "${formatMinutesPerKm((value / 60.0) / (meters / 1_000.0))} /km"
}

private val RECORD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
