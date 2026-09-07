package com.example.runningapp.ui

import com.example.runningapp.RunType
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.data.RunnerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing History to one Run Type, and what those Runs covered (#447).
 *
 * The Run Type is derived and never stored, so the three cases the derivation has to name are pinned
 * here rather than on a phone: a Run under a Workout the plan still holds, a Run under no Workout at
 * all, and a Run under a Workout id no plan holds any more.
 */
class HistoryRunTypeFilterTest {

    // The ids the shipped plan actually uses — see TrainingPlanProvider.
    private val stage = "base_builder"
    private val longRun = "w1_long"
    private val easyRun = "w1_easy"
    private val qualityRun = "w1_quality"

    private fun row(
        id: Long,
        stageId: String? = stage,
        workoutId: String? = longRun,
        distanceKm: Double = 8.0,
        isWalk: Boolean = false,
        finished: Boolean = true,
    ) = HistoryRow(
        session = RunnerSession(
            id = id,
            startTime = 1_754_300_000_000L + id * 86_400_000L,
            endTime = if (finished) 1_754_300_000_000L + id * 86_400_000L + 3_600_000L else 0L,
            durationSeconds = 3_600,
            distanceKm = distanceKm,
            ranUnderStageId = stageId,
            ranUnderWorkoutId = workoutId,
            isWalk = isWalk,
        ),
        medals = 0,
        thumbnail = null,
    )

    // ---- the Run Type a recorded Run is filed under ----

    @Test
    fun `a run under a workout the plan holds takes that workout's run type`() {
        assertEquals(RecordedRunType.LONG, recordedRunTypeOf(row(1, workoutId = longRun).session))
        assertEquals(RecordedRunType.EASY, recordedRunTypeOf(row(2, workoutId = easyRun).session))
        assertEquals(RecordedRunType.QUALITY, recordedRunTypeOf(row(3, workoutId = qualityRun).session))
    }

    @Test
    fun `an open run is Other, not swept into a run type`() {
        assertEquals(RecordedRunType.OTHER, recordedRunTypeOf(row(1, workoutId = null).session))
        assertEquals(
            RecordedRunType.OTHER,
            recordedRunTypeOf(row(2, stageId = null, workoutId = null).session),
        )
    }

    @Test
    fun `a run under a workout no plan holds any more is Other`() {
        assertEquals(
            RecordedRunType.OTHER,
            recordedRunTypeOf(row(1, workoutId = "a_workout_that_was_deleted").session),
        )
        assertEquals(
            RecordedRunType.OTHER,
            recordedRunTypeOf(row(2, stageId = "a_stage_that_was_deleted", workoutId = longRun).session),
        )
    }

    @Test
    fun `a run made under the Desk Test plan is Other, not the Long its workout says`() {
        // The Desk Test plan is a live member of TrainingPlanProvider.plans, so this lookup does
        // not fail — its Workout resolves and says LONG. Ten seconds of running twice over is a
        // validation of the interval machine, and filing it as a Long day would drag the typical
        // Long distance below every Long Run actually run.
        assertEquals(
            RunType.LONG,
            TrainingPlanProvider.runTypeOfRecordedRun("desk_test_stage", "desk_test_workout"),
        )
        assertEquals(
            RecordedRunType.OTHER,
            recordedRunTypeOf(
                row(1, stageId = "desk_test_stage", workoutId = "desk_test_workout").session
            ),
        )
    }

    @Test
    fun `a Desk Test run is kept out of the Long distances`() {
        val rows = listOf(
            row(1, workoutId = longRun, distanceKm = 10.0),
            row(2, workoutId = longRun, distanceKm = 12.0),
            row(3, workoutId = longRun, distanceKm = 14.0),
            row(4, stageId = "desk_test_stage", workoutId = "desk_test_workout", distanceKm = 0.1),
        )

        val long = historyRowsOfType(rows, RecordedRunType.LONG)

        assertEquals(listOf(1L, 2L, 3L), long.map { it.session.id })
        assertEquals("Usually 12.0 km", historyDistanceHeadline(RecordedRunType.LONG, historyDistanceSummary(long)))
        assertEquals(listOf(4L), historyRowsOfType(rows, RecordedRunType.OTHER).map { it.session.id })
    }

    @Test
    fun `a walk carries no run type however it was started`() {
        assertEquals(
            RecordedRunType.OTHER,
            recordedRunTypeOf(row(1, workoutId = qualityRun, isWalk = true).session),
        )
    }

    // ---- what the list shows ----

    @Test
    fun `no run type selected shows every run, in the order it arrived`() {
        val rows = listOf(row(1, workoutId = longRun), row(2, workoutId = null), row(3, workoutId = easyRun))

        assertEquals(listOf(1L, 2L, 3L), historyRowsOfType(rows, null).map { it.session.id })
    }

    @Test
    fun `a run type selected shows only that run type`() {
        val rows = listOf(
            row(1, workoutId = longRun),
            row(2, workoutId = easyRun),
            row(3, workoutId = longRun),
            row(4, workoutId = null),
        )

        assertEquals(
            listOf(1L, 3L),
            historyRowsOfType(rows, RecordedRunType.LONG).map { it.session.id },
        )
        assertEquals(
            listOf(4L),
            historyRowsOfType(rows, RecordedRunType.OTHER).map { it.session.id },
        )
    }

    @Test
    fun `every run shown unfiltered is shown by exactly one run type`() {
        val rows = listOf(
            row(1, workoutId = longRun),
            row(2, workoutId = easyRun),
            row(3, workoutId = qualityRun),
            row(4, workoutId = null),
            row(5, workoutId = qualityRun, isWalk = true),
            row(6, workoutId = "gone"),
        )

        val filed = RecordedRunType.entries.flatMap { historyRowsOfType(rows, it) }.map { it.session.id }

        assertEquals(rows.size, filed.size)
        assertEquals(rows.map { it.session.id }.toSet(), filed.toSet())
    }

    // ---- the distances those sessions came out at ----

    @Test
    fun `the typical distance is the middle one, not the average`() {
        val rows = listOf(
            row(1, distanceKm = 5.0),
            row(2, distanceKm = 7.0),
            row(3, distanceKm = 30.0),
        )

        val summary = historyDistanceSummary(rows)

        assertEquals(7.0, summary.typicalKm!!, 0.001)
        assertEquals(5.0, summary.shortestKm!!, 0.001)
        assertEquals(30.0, summary.longestKm!!, 0.001)
        assertEquals(3, summary.measuredRuns)
        assertEquals(3, summary.finishedRuns)
    }

    @Test
    fun `an even number of runs takes the middle two`() {
        val rows = listOf(row(1, distanceKm = 4.0), row(2, distanceKm = 6.0), row(3, distanceKm = 7.0), row(4, distanceKm = 9.0))

        assertEquals(6.5, historyDistanceSummary(rows).typicalKm!!, 0.001)
    }

    @Test
    fun `a run with no distance is counted as shown but never measured`() {
        val rows = listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 0.0), row(3, distanceKm = 9.0))

        val summary = historyDistanceSummary(rows)

        assertEquals(3, summary.finishedRuns)
        assertEquals(2, summary.measuredRuns)
        assertEquals(5.0, summary.shortestKm!!, 0.001)
        assertEquals(9.0, summary.longestKm!!, 0.001)
    }

    @Test
    fun `a run still being recorded is in neither count`() {
        val rows = listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 0.4, finished = false))

        val summary = historyDistanceSummary(rows)

        // Not a run with no distance — a run that has not happened yet. So it is never printed as
        // one: the detail line below counts only finished runs that measured nothing.
        assertEquals(1, summary.finishedRuns)
        assertEquals(1, summary.measuredRuns)
        assertEquals(5.0, summary.shortestKm!!, 0.001)
        assertEquals(5.0, summary.longestKm!!, 0.001)
    }

    @Test
    fun `too few measured runs name no typical distance`() {
        val rows = listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 9.0))

        val summary = historyDistanceSummary(rows)

        assertNull(summary.typicalKm)
        assertEquals(2, summary.measuredRuns)
    }

    @Test
    fun `no measured run at all leaves every distance unanswered`() {
        val rows = listOf(row(1, distanceKm = 0.0), row(2, distanceKm = 0.0))

        val summary = historyDistanceSummary(rows)

        assertNull(summary.typicalKm)
        assertNull(summary.shortestKm)
        assertNull(summary.longestKm)
        assertEquals(0, summary.measuredRuns)
    }

    // ---- what the block says ----

    @Test
    fun `the headline names a typical distance only once there are enough runs`() {
        val three = historyDistanceSummary(listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 7.0), row(3, distanceKm = 30.0)))

        assertEquals("Usually 7.0 km", historyDistanceHeadline(RecordedRunType.QUALITY, three))
        assertNull(
            historyDistanceHeadline(RecordedRunType.QUALITY, historyDistanceSummary(listOf(row(1, distanceKm = 5.0)))),
        )
    }

    @Test
    fun `Other never calls a distance usual, however many runs it holds`() {
        val plenty = historyDistanceSummary(
            listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 7.0), row(3, distanceKm = 30.0), row(4, distanceKm = 8.0)),
        )

        assertNull(historyDistanceHeadline(RecordedRunType.OTHER, plenty))
        // The range is still printed: it says what these runs measured, not what the next would be.
        assertEquals("5.0–30.0 km", historyDistanceDetail(plenty))
    }

    @Test
    fun `a run still being recorded is never counted as a run with no distance`() {
        val rows = listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 9.0), row(3, distanceKm = 0.4, finished = false))

        assertEquals("5.0–9.0 km", historyDistanceDetail(historyDistanceSummary(rows)))
    }

    @Test
    fun `the detail gives the range, and names how many runs had no distance`() {
        assertEquals(
            "5.0–9.0 km",
            historyDistanceDetail(historyDistanceSummary(listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 9.0)))),
        )
        assertEquals(
            "5.0–9.0 km · 1 with no distance",
            historyDistanceDetail(
                historyDistanceSummary(listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 9.0), row(3, distanceKm = 0.0))),
            ),
        )
    }

    @Test
    fun `one measured run is written as one distance, not as a range`() {
        assertEquals(
            "5.0 km",
            historyDistanceDetail(historyDistanceSummary(listOf(row(1, distanceKm = 5.0)))),
        )
    }

    @Test
    fun `runs that all came out the same are written once`() {
        assertEquals(
            "5.0 km",
            historyDistanceDetail(historyDistanceSummary(listOf(row(1, distanceKm = 5.0), row(2, distanceKm = 5.0)))),
        )
    }

    @Test
    fun `no measured run says so rather than printing a number`() {
        val detail = historyDistanceDetail(historyDistanceSummary(listOf(row(1, distanceKm = 0.0))))

        assertTrue(detail, detail.contains("No distance"))
    }
}
