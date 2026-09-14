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
    /**
     * The seven Records, or null, which is not the same thing as seven empty ones (#75).
     *
     * Null is "the efforts have not come back from Room yet", and it is the state the section opens
     * in. The distinction has to be carried here because [recordSlots] always hands back all seven
     * Records whether they have been contested or not: a list of seven slots is therefore a
     * statement about the runner's history — that they have never run any of these — and a grid
     * built before that history was read would make exactly that statement, wrongly, on every cold
     * open of the Progress screen.
     *
     * The absence of the slots rather than a flag beside them, the same shape [RecordDetailUi.top]
     * carries for the same reason: a flag saying "read yet" would be a second answer to a question
     * the slots already answer, and two answers can disagree. [measuring] is a different fact again
     * — there the read has answered and the answer is deliberately nothing, which is why the two
     * appear together as an empty list and a raised flag.
     */
    val slots: List<RecordSlotUi>? = null,
    val measuring: Boolean = false,
)

/**
 * The Records section the moment it is drawn, before Room has answered (#75).
 *
 * Named rather than written out at the call site for [recordDetailNotReadYet]'s reason: "not read
 * back yet" is one thing and it should have one spelling, so that no screen and no seed has to
 * decide for itself what an absent grid looks like.
 */
fun recordsGridNotReadYet(): RecordsGridUi = RecordsGridUi(slots = null)

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
            best = recordLeague(type).best(atType.map { it.toUi(zone) }),
        )
    }
}

/**
 * What a Record's own page ranks (#75): every Run's claim at it, best first, and its trend.
 *
 * The table is the one every league of the runner's efforts is built by ([LeagueTable]); what is
 * the Record's own is only the order, which is the record book's ([bestFirst]), and the unit a claim
 * is said in. "Best" first rather than "quickest", because the longest run is not a time.
 */
fun recordLeague(type: RecordType): LeagueTable<RecordEffortUi> = LeagueTable(
    bestFirst = bestFirst(type),
    day = { it.date },
    dateLabel = { it.dateLabel },
    plotted = { it.value },
    valueLabel = { recordValueLabel(type, it) },
    orderWord = "best",
    trendSubject = "Your ${type.label}",
)

/**
 * Every claim ever banked at one Record, as the page prints them — the entries [recordLeague] ranks.
 *
 * Every one and not only the best ten: the trend is drawn through all of them, and the count under
 * the best says how many there were. The ranked list cuts at ten itself ([LeagueTable.top]).
 */
fun recordEfforts(
    rows: List<RecordEffortRow>,
    type: RecordType,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordEffortUi> = rows.filter { it.type == type }.map { it.toUi(zone) }

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

/**
 * Which of two claims at one Record is the better one — the record book's own direction
 * ([RecordType.lowerIsBetter]) with the book's own tie-break after it.
 *
 * Handed to the one table that ranks the Record ([recordLeague]), and the grid asks that same table
 * for its best, because the grid's best, the gold disc in the ranked list and each day's point on the
 * trend are the same claim about the same Record and must never be three different rows.
 */
private fun bestFirst(type: RecordType): Comparator<RecordEffortUi> =
    compareBy<RecordEffortUi> { if (type.lowerIsBetter) it.value else -it.value }
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
