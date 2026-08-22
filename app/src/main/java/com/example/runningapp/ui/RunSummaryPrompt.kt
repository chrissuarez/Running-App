package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.analysis.RecordUnit
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.data.isTreadmill
import com.example.runningapp.ranOn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the app tells the model about one Run, and the words it asks for back (#76).
 *
 * Pure, and outside anything that talks to the network, for the reason [matchedRunsUi] and
 * [segmentEffortsUi] are pure: the interesting part of an AI feature is what it is *told*, and a
 * prompt assembled inside a client can only be checked by making the call. Everything here is a
 * function of rows already stored, so the whole of it is pinned in tests and no test ever reaches
 * Gemini.
 *
 * **Only facts that exist are sent.** A Run that recorded no heart rate has no heart rate line, a
 * Run that crossed no Segment has no Segments line, and a Run nobody has repeated has no route line
 * — rather than a line saying "none". A model shown "records earned: none" writes a sentence about
 * not earning any, which is a sentence about nothing; a model shown no such line writes about what
 * the Run did hold. It is the same rule the coach's prompt keeps for a Walk and an Open Run: say
 * what is true, and stay silent about what is not there.
 */

/** One Run as the model is told about it. */
data class RunSummaryFacts(
    /** "Run", "Walk", "Treadmill run", "Treadmill walk" — what kind of outing this was. */
    val kind: String,
    /** The day the runner would say they ran it (#304). */
    val dateLabel: String,
    val durationLabel: String,
    /**
     * The clock with the Breaks taken out, where it is known and differs from the whole of it.
     * Null on a Run nothing measured a moving time for, and on one that never stopped.
     */
    val movingTimeLabel: String? = null,
    /** How far it went — measured, or stated off a treadmill console. Null where there is none. */
    val distanceLabel: String? = null,
    /** Null where there is no distance, or no clock, to make a pace out of. */
    val paceLabel: String? = null,
    /** Null on a Run that wore no Strap. */
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    /** Null on a Run that recorded no heart rate at all, which is what an Effort Score needs. */
    val effortScore: Int? = null,
    /** The medals this Run took in the record book, in the book's own order. */
    val records: List<String> = emptyList(),
    /** What it was worth at the named ground it went over, in the order it went over it. */
    val segments: List<String> = emptyList(),
    /**
     * Where it stands among the other Runs that went over the same ground. Null where there is no
     * group.
     *
     * Named for the group and not for a route, because a **Route** in this app is the kept course of
     * the library and nothing about a matched group is kept (CONTEXT.md, **Matched Runs**). The word
     * "route" in the prompt's own prose is the runner's word and is the honest one for what they are
     * shown; it is only ever a name in here.
     */
    val matched: MatchedRunsFacts? = null,
)

/** This Run set against every other time the runner has been the same way (#73). */
data class MatchedRunsFacts(
    val position: Int,
    val count: Int,
    val paceLabel: String,
    /** The middle of the other Runs' paces — what "usual on this route" means here. */
    val usualPaceLabel: String,
    /** How this Run compares with that middle, said in seconds per kilometre. */
    val comparison: String,
)

/**
 * Everything the model is told, gathered from rows that are already stored.
 *
 * [achievements], [segmentEfforts] and [matched] are the same three readings the Run's own page
 * draws its cards from, handed in rather than re-read: the summary must describe the page the runner
 * is looking at, and a second reading taken a moment later is a second answer.
 */
fun runSummaryFacts(
    session: RunnerSession,
    achievements: List<Achievement> = emptyList(),
    segmentEfforts: List<RunSegmentEffortUi> = emptyList(),
    matched: MatchedRunsUi? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): RunSummaryFacts {
    val outing = if (session.isWalk) "walk" else "run"
    val kind = if (session.isTreadmill()) "Treadmill $outing" else outing.replaceFirstChar { it.uppercase() }
    val movingTime = session.movingTimeSeconds
    return RunSummaryFacts(
        kind = kind,
        dateLabel = RUN_SUMMARY_DATE_FORMAT.format(session.ranOn(zone)),
        durationLabel = formatDuration(session.durationSeconds),
        movingTimeLabel = movingTime
            ?.takeIf { it > 0 && it != session.durationSeconds }
            ?.let { formatDuration(it) },
        distanceLabel = session.distanceKm
            .takeIf { it > 0.0 }
            ?.let { String.format(Locale.UK, "%.2f km", it) },
        paceLabel = session.avgPaceMinPerKm
            .takeIf { it > 0.0 }
            ?.let { "${formatMinutesPerKm(it)} /km" },
        avgBpm = session.avgBpm.takeIf { it > 0 },
        maxBpm = session.maxBpm.takeIf { it > 0 },
        effortScore = session.effortScore,
        records = achievements
            .sortedBy { it.type.ordinal }
            .map { "${it.medal.spoken} at ${it.type.label} (${recordValueLabel(it.type, it.value)})" },
        segments = segmentEfforts.map { effort ->
            val placing = effort.medal?.let { ", ${it.spoken} there" }.orEmpty()
            "${effort.segmentName} in ${effort.timeLabel} at ${effort.paceLabel}$placing"
        },
        matched = matched?.let(::matchedRunsFacts),
    )
}

/**
 * Where this Run stands on its route, or null where the group says nothing worth sending.
 *
 * "Usual" is the middle of the *other* Runs rather than of all of them, because this Run is the one
 * being described: measured against a set it is itself in, a runner's only run of the week comes out
 * exactly average by arithmetic, and the sentence would say nothing every time the group is small.
 *
 * Null where none of the others measured a pace — history from before the app measured one. The
 * count and the place are still true then, and are still sent; there is simply nothing to compare.
 */
private fun matchedRunsFacts(matched: MatchedRunsUi): MatchedRunsFacts? {
    val thisRun = matched.runs.firstOrNull { it.isThisRun } ?: return null
    val thisPace = thisRun.paceMinPerKm
    val otherPaces = matched.runs.filterNot { it.isThisRun }.mapNotNull { it.paceMinPerKm }.sorted()
    if (thisPace == null || otherPaces.isEmpty()) return null

    val usual = median(otherPaces)
    // Seconds per kilometre, because that is the unit a runner compares two paces in — a decimal
    // fraction of a minute is a number nobody says out loud.
    val differenceSeconds = ((thisPace - usual) * 60.0).roundToInt()
    val comparison = when {
        differenceSeconds < 0 -> "${abs(differenceSeconds)} s/km faster than usual on this route"
        differenceSeconds > 0 -> "$differenceSeconds s/km slower than usual on this route"
        else -> "the same pace as usual on this route"
    }
    return MatchedRunsFacts(
        position = matched.position,
        count = matched.count,
        paceLabel = "${formatMinutesPerKm(thisPace)} /km",
        usualPaceLabel = "${formatMinutesPerKm(usual)} /km",
        comparison = comparison,
    )
}

/**
 * The prompt, built from facts alone.
 *
 * Plain prose is asked for rather than JSON, because the whole of the answer is the words: there is
 * no field to pull out of it and nothing downstream reads it but the runner. The length is capped in
 * the asking as well as trimmed in the storing — a card at the top of a page is a paragraph, not an
 * essay.
 */
fun buildRunSummaryPrompt(facts: RunSummaryFacts): String = buildString {
    appendLine("You are the runner's own coach, writing a short note about one run they have just opened.")
    appendLine(
        "Write 2 to 4 sentences of plain English, addressed to the runner as \"you\". Say what stood " +
            "out about this run. No headings, no bullet points, no markdown, no emoji."
    )
    // The rule that keeps this a summary rather than a second coach. A Prescription is the coach's
    // ([ADR 0006]) and is written from the whole of a runner's training state; this is shown on one
    // Run's page and is told about that Run alone, so advice from here would be advice given without
    // the evidence that makes advice worth anything.
    appendLine(
        "Describe this run only. Do not prescribe the next workout, do not give training advice, and " +
            "do not guess at anything you are not told below."
    )
    appendLine(
        "Every fact you have is listed below. If something is not listed, it was not recorded — say " +
            "nothing about it at all, and never say it is missing."
    )
    appendLine()
    appendLine("THE RUN")
    appendLine("- Kind: ${facts.kind}")
    appendLine("- Date: ${facts.dateLabel}")
    appendLine("- Duration: ${facts.durationLabel}")
    facts.movingTimeLabel?.let { appendLine("- Moving time, with pauses and lost signal taken out: $it") }
    facts.distanceLabel?.let { appendLine("- Distance: $it") }
    facts.paceLabel?.let { appendLine("- Average pace: $it") }
    facts.avgBpm?.let { appendLine("- Average heart rate: $it bpm") }
    facts.maxBpm?.let { appendLine("- Highest heart rate: $it bpm") }
    facts.effortScore?.let {
        appendLine(
            "- Effort Score: $it. This is what the run cost, every second weighted by the heart-rate " +
                "zone it was spent in. A higher number is a harder run."
        )
    }

    if (facts.records.isNotEmpty()) {
        appendLine()
        appendLine("RECORDS THIS RUN TOOK (all-time, across the runner's whole history)")
        facts.records.forEach { appendLine("- $it") }
    }
    if (facts.segments.isNotEmpty()) {
        appendLine()
        appendLine("NAMED STRETCHES OF GROUND THIS RUN WENT OVER, in the order it went over them")
        facts.segments.forEach { appendLine("- $it") }
    }
    facts.matched?.let { group ->
        appendLine()
        appendLine("THIS ROUTE")
        appendLine("- This is run ${group.position} of ${group.count} the runner has done over the same route.")
        appendLine("- Pace this time: ${group.paceLabel}")
        appendLine("- Their usual pace over it: ${group.usualPaceLabel}")
        appendLine("- So this run was ${group.comparison}.")
    }
}.trimEnd()

/**
 * What the Medal reads as in a sentence, rather than as a shouted enum name.
 *
 * The same three words the medal discs are labelled with ([face]), so the model is told the thing
 * the runner can see on the same page. Written out here rather than read off that, because [face]
 * carries a colour and a place with it and this file is deliberately free of anything drawn.
 */
private val Medal.spoken: String
    get() = when (this) {
        Medal.GOLD -> "Gold"
        Medal.SILVER -> "Silver"
        Medal.BRONZE -> "Bronze"
    }

/** A record's stored value in the unit that record is kept in. */
private fun recordValueLabel(type: RecordType, value: Double): String = when (type.unit) {
    RecordUnit.SECONDS -> formatDuration(value.roundToInt().toLong())
    RecordUnit.METERS -> String.format(Locale.UK, "%.2f km", value / 1000.0)
}

/** The middle of a sorted list, averaging the middle pair where there is no single middle. */
private fun median(sorted: List<Double>): Double {
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private val RUN_SUMMARY_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
