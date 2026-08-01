package com.example.runningapp.analysis

import com.example.runningapp.data.Achievement
import com.example.runningapp.data.FIVE_K_METERS
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.isFinished
import com.example.runningapp.data.measureFastestEffortSeconds
import com.example.runningapp.run.RunMode

/**
 * Something a Run can be the runner's best at (#49).
 *
 * Five distances and two totals, which is the set Strava keeps and the set a runner talks about.
 * The distances are contested as a *best effort* — the fastest continuous stretch anywhere inside
 * the Run — and the totals are simply what the Run was.
 *
 * **These names are written into the database** ([Achievement.type]), so renaming one silently
 * orphans every medal already won at it. Add to the end; do not rename.
 */
enum class RecordType(val label: String, val distanceMeters: Double?, val unit: RecordUnit) {
    FASTEST_1K("Fastest 1 km", 1_000.0, RecordUnit.SECONDS),
    FASTEST_MILE("Fastest mile", 1_609.344, RecordUnit.SECONDS),
    FASTEST_5K("Fastest 5 km", FIVE_K_METERS, RecordUnit.SECONDS),
    FASTEST_10K("Fastest 10 km", 10_000.0, RecordUnit.SECONDS),
    FASTEST_HALF("Fastest half marathon", 21_097.5, RecordUnit.SECONDS),
    LONGEST_DISTANCE("Longest run", null, RecordUnit.METERS),
    LONGEST_DURATION("Longest time", null, RecordUnit.SECONDS);

    /**
     * Whether the smaller number is the better one — true of every record run over a set distance,
     * false of the two that ask how much was done rather than how quickly.
     */
    val lowerIsBetter: Boolean get() = distanceMeters != null
}

/** What the number attached to a record means. */
enum class RecordUnit { SECONDS, METERS }

/**
 * Where an effort placed, all time — and, by there being three of them, how deep the book goes.
 * Beyond bronze nothing is remembered. Persisted by name, like [RecordType] — do not rename.
 */
enum class Medal { GOLD, SILVER, BRONZE }

/**
 * One Run's claim at one record: how fast it covered the distance, or how much of it there was.
 *
 * [value] is seconds or metres according to [RecordType.unit]. One number rather than a field per
 * unit, because everything downstream — ranking, storing, drawing — only ever asks which of two
 * efforts at the *same* record is the better one.
 */
data class BestEffort(val type: RecordType, val value: Double)

/**
 * Everything a finished Run is worth against the record book (#49).
 *
 * The distances are measured with [measureFastestEffortSeconds], the same rolling window a Stage's
 * 5K requirement is judged by (#182): the quickest continuous stretch covering the distance, on the
 * clock. On the clock is the whole point of a record — a walk break inside the stretch is time the
 * runner took, and a best effort that quietly skipped it would be a time nobody ran.
 *
 * Who may compete:
 * - **A treadmill Run contests the longest time and nothing else.** Its distance is a number the
 *   machine reported, never measured against ground, so letting it hold a distance record would put
 *   an unverifiable claim above every measured one.
 * - **A Run with no usable track contests no distance record**, for the same reason. The fastest
 *   stretches need the track by construction — there is no ground to have covered quickly — but the
 *   longest *distance* is asked of the Run's own total, and a Run can carry one with nothing left of
 *   its route: history recorded before the app kept a track, and a Run whose every fix was too vague
 *   to trust. That total was measured against ground nobody can now see, which is as unverifiable as
 *   a treadmill's, so the track is asked for explicitly here.
 * - **A Run still being recorded is worth nothing.** Its totals are not written yet, so it would
 *   compete on a duration and a distance that are only as much of it as has happened so far.
 *
 * There is no walk-only kind of Run for the eligibility rule to exclude: #94 deleted the four
 * session types, so every recorded Run is a run, and treadmill-or-outdoor is the only thing that
 * distinguishes them. Should a walk ever become a kind of Run again, this is the function that has
 * to say so.
 *
 * Pass the same accuracy-filtered points the map and the splits are built from
 * ([com.example.runningapp.data.SessionRepository.getTrackPointsForMap]) — a wild fix left in reads
 * as a sprint, and would put a record on the books nobody ran.
 */
fun bestEffortsOf(run: RunnerSession, track: List<TrackPoint>): List<BestEffort> {
    if (!run.isFinished()) return emptyList()
    // Two fixes is the least a route can be: one fix says only where the Run started.
    val onTheGround = RunMode.ofSettingValue(run.runMode) != RunMode.TREADMILL && track.size >= 2
    return RecordType.entries.mapNotNull { type ->
        val value = when {
            type == RecordType.LONGEST_DURATION -> run.durationSeconds.takeIf { it > 0 }?.toDouble()
            !onTheGround -> null
            type == RecordType.LONGEST_DISTANCE ->
                (run.distanceKm * 1_000.0).takeIf { run.distanceKm > 0.0 }
            else -> measureFastestEffortSeconds(track, type.distanceMeters!!)?.toDouble()
        }
        value?.let { BestEffort(type, it) }
    }
}

/**
 * The record book after a Run's [efforts] have been put to it: the top three at every record the Run
 * contested, in medal order, with the Run's own effort in its place among them (#49).
 *
 * The whole standings rather than only what the Run won, because a Run taking the gold moves
 * everyone below it: what the caller has to store is the three places, and which of them belong to
 * this Run is a question the list answers.
 *
 * Returns rows only for the types [efforts] touched — the caller rewrites exactly those and leaves
 * the rest of the book alone, so a treadmill Run can never disturb the distance records.
 *
 * The Run's own standing rows are dropped before it is ranked, so scoring a Run twice — a rescue
 * that finishes it again, a re-score — leaves it holding one medal rather than racing itself.
 *
 * **Matching a standing record does not take it.** A tie leaves the earlier Run where it is, because
 * the record is that Run's until somebody actually beats it.
 */
fun standingsAfter(
    book: List<Achievement>,
    sessionId: Long,
    efforts: List<BestEffort>,
): List<Achievement> =
    efforts.flatMap { effort ->
        val standing = book
            .filter { it.type == effort.type && it.sessionId != sessionId }
            .sortedBy { it.medal.ordinal }
            .map { it.sessionId to it.value }
        // The challenger goes on the end, so a stable sort leaves it behind anything it only equals.
        val ranked = (standing + (sessionId to effort.value))
            .sortedWith(compareBy { (_, value) -> if (effort.type.lowerIsBetter) value else -value })
        placed(effort.type, ranked)
    }

/** What one Run was worth at every record it contested — [bestEffortsOf], with its Run attached. */
data class RunEfforts(val sessionId: Long, val efforts: List<BestEffort>)

/**
 * The whole record book, built from the whole history at once (#50).
 *
 * The other way of arriving at a book than [standingsAfter]: that one puts a single Run to a book
 * that already exists, this one asks what the book *is*, given every effort ever run. It is how
 * history first gets on the books, and how the book is repaired after a medal-holding Run is
 * deleted — in both cases there is no standing order worth trusting, so nothing is carried over
 * from the old rows.
 *
 * Covers only the records the passed-in efforts contested, like [standingsAfter], so a caller
 * rebuilding two records leaves the other five alone.
 *
 * **A tie is kept by the lower session id**, which is the earlier Run: a record is that Run's until
 * somebody actually beats it, and rebuilding the book must not quietly hand it to whoever ran the
 * same time later. Ids are the ordering rather than start times because they are what the medal
 * rows carry, so the rule can be checked against a book without reading history back.
 */
fun recordBookOf(runs: List<RunEfforts>): List<Achievement> =
    runs
        .flatMap { run -> run.efforts.map { run.sessionId to it } }
        .groupBy { (_, effort) -> effort.type }
        .flatMap { (type, claims) ->
            val ranked = claims
                .map { (sessionId, effort) -> sessionId to effort.value }
                .sortedWith(
                    compareBy<Pair<Long, Double>> { (_, value) -> if (type.lowerIsBetter) value else -value }
                        .thenBy { (sessionId, _) -> sessionId }
                )
            placed(type, ranked)
        }

/** The top of [ranked] — best first — written out as the medal rows that stand at [type]. */
private fun placed(type: RecordType, ranked: List<Pair<Long, Double>>): List<Achievement> =
    ranked.take(Medal.entries.size).mapIndexed { place, (holder, value) ->
        Achievement(
            sessionId = holder,
            type = type,
            medal = Medal.entries[place],
            value = value,
        )
    }
