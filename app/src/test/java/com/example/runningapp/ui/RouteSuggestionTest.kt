package com.example.runningapp.ui

import com.example.runningapp.RunType
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunPaceRow
import com.example.runningapp.plannedSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which course fits today's session (#422) — the number, the words and the order.
 *
 * Every claim here is one a runner can see on the start line: whether a hint appears, what it says,
 * and which course the picker offers first. Nothing about the table.
 *
 * The Runs are described by the Stage and Workout ids a real Run is stamped with, because that is
 * the only thing a stored Run says about what kind of session it was — see
 * [TrainingPlanProvider.runTypeOfRecordedRun].
 */
class RouteSuggestionTest {

    private val stage = "base_builder"
    private val longRun = "w1_long"
    private val easyRun = "w1_easy"
    private val qualityRun = "w1_quality"

    private fun workout(id: String): WorkoutTemplate =
        TrainingPlanProvider.stageById(stage)!!.workouts.first { it.id == id }

    /** A Run that covered [distanceKm] in [minutes], of the kind [workoutId] names. */
    private fun run(
        workoutId: String?,
        minutes: Double,
        distanceKm: Double,
        stageId: String? = stage,
    ) = RunPaceRow(
        ranUnderStageId = stageId,
        ranUnderWorkoutId = workoutId,
        durationSeconds = (minutes * 60.0).toLong(),
        distanceKm = distanceKm,
    )

    private fun header(
        id: Long,
        distanceMeters: Double,
        family: String? = null,
        createdAtMillis: Long = id,
    ) = RouteHeader(
        id = id,
        name = "Route $id",
        distanceMeters = distanceMeters,
        elevationGainMeters = null,
        createdAtMillis = createdAtMillis,
        source = RouteSource.IMPORTED,
        family = family,
    )

    // ---- the target ----

    @Test
    fun `three runs of today's kind give a distance`() {
        // 6:00 /km on every one of them, so the median is 6:00 whichever way it is taken.
        val runs = List(3) { run(easyRun, minutes = 30.0, distanceKm = 5.0) }
        val easy = workout(easyRun)

        val target = suggestedRouteDistanceMeters(easy, runs)!!

        // The planned session at six minutes a kilometre.
        val expectedKm = (easy.plannedSeconds / 60.0) / 6.0
        assertEquals(expectedKm * 1000.0, target, 0.001)
    }

    @Test
    fun `two runs of today's kind suggest nothing`() {
        val runs = List(2) { run(easyRun, minutes = 30.0, distanceKm = 5.0) }

        assertNull(suggestedRouteDistanceMeters(workout(easyRun), runs))
    }

    @Test
    fun `a run of another kind is not counted towards today's`() {
        // Three Runs, but only two of them are Easy Runs.
        val runs = listOf(
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
            run(qualityRun, minutes = 30.0, distanceKm = 6.0),
        )

        assertNull(suggestedRouteDistanceMeters(workout(easyRun), runs))
    }

    @Test
    fun `a run with no workout recorded is not counted`() {
        val runs = listOf(
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
            run(workoutId = null, minutes = 30.0, distanceKm = 5.0),
        )

        assertNull(suggestedRouteDistanceMeters(workout(easyRun), runs))
    }

    @Test
    fun `one slow day does not drag the suggestion down`() {
        // Two Runs at 6:00 /km and one at 12:00 /km. A mean would answer 8:00; the median answers
        // 6:00, which is what this runner actually runs.
        val runs = listOf(
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
            run(easyRun, minutes = 60.0, distanceKm = 5.0),
            run(easyRun, minutes = 30.0, distanceKm = 5.0),
        )
        val easy = workout(easyRun)

        val target = suggestedRouteDistanceMeters(easy, runs)!!

        assertEquals((easy.plannedSeconds / 60.0) / 6.0 * 1000.0, target, 0.001)
    }

    @Test
    fun `only the newest ten runs of the kind are read`() {
        // The newest ten are all 6:00 /km; the eleventh is a crawl and must not reach the median.
        val runs = List(10) { run(easyRun, minutes = 30.0, distanceKm = 5.0) } +
            List(9) { run(easyRun, minutes = 120.0, distanceKm = 5.0) }
        val easy = workout(easyRun)

        val target = suggestedRouteDistanceMeters(easy, runs)!!

        assertEquals((easy.plannedSeconds / 60.0) / 6.0 * 1000.0, target, 0.001)
    }

    @Test
    fun `an open run suggests nothing`() {
        val runs = List(5) { run(easyRun, minutes = 30.0, distanceKm = 5.0) }

        assertNull(suggestedRouteDistanceMeters(workout = null, recentRuns = runs))
    }

    @Test
    fun `a long day suggests further than an easy one`() {
        // Same pace on both kinds, so the two targets can differ only by the planned seconds.
        val runs = List(3) { run(easyRun, minutes = 30.0, distanceKm = 5.0) } +
            List(3) { run(longRun, minutes = 30.0, distanceKm = 5.0) }

        val easy = suggestedRouteDistanceMeters(workout(easyRun), runs)!!
        val long = suggestedRouteDistanceMeters(workout(longRun), runs)!!

        assertTrue(workout(longRun).plannedSeconds > workout(easyRun).plannedSeconds)
        assertTrue(long > easy)
    }

    // ---- the day the plan states the distance ----

    @Test
    fun `a 5K test suggests five kilometres, not what today's pace would cover`() {
        // The bar is 25 minutes and this runner's Quality Runs are 6:00 /km, so multiplying would
        // advertise about 4.2 km — a course the prescribed Test cannot be finished on.
        val peakStage = TrainingPlanProvider.stageById("sub_25_peak")!!
        val test = peakStage.workouts.first { it.isTest }
        val runs = List(3) {
            run(workoutId = "w3_s1", minutes = 30.0, distanceKm = 5.0, stageId = "sub_25_peak")
        }
        val fixed = peakStage.bestEffortRequirement!!.record.distanceMeters!!

        val target = suggestedRouteDistanceMeters(test, runs, fixed)!!

        assertEquals(5_000.0, target, 0.001)
        assertTrue(target > suggestedRouteDistanceMeters(test, runs)!!)
    }

    @Test
    fun `a 5K test suggests five kilometres on a phone with no history at all`() {
        val peakStage = TrainingPlanProvider.stageById("sub_25_peak")!!
        val test = peakStage.workouts.first { it.isTest }
        val fixed = peakStage.bestEffortRequirement!!.record.distanceMeters!!

        val target = suggestedRouteDistanceMeters(test, recentRuns = emptyList(), fixedDistanceMeters = fixed)

        assertEquals(5_000.0, target!!, 0.001)
    }

    @Test
    fun `an ordinary day is still worked out from the runner's own pace`() {
        // The same call, with no stated distance: nothing about the Test day changes this one.
        val runs = List(3) { run(easyRun, minutes = 30.0, distanceKm = 5.0) }
        val easy = workout(easyRun)

        val target = suggestedRouteDistanceMeters(easy, runs, fixedDistanceMeters = null)!!

        assertEquals((easy.plannedSeconds / 60.0) / 6.0 * 1000.0, target, 0.001)
    }

    @Test
    fun `a stated distance is written without the about sign`() {
        assertEquals("Today 5 km", routeSuggestionHint(5_000.0, targetIsFixed = true))
    }

    @Test
    fun `a stated distance the line has to round keeps the about sign`() {
        // A mile is 1609.344 m and prints as 1.6 km, which is not the distance whatever states it.
        assertEquals("Today ≈ 1.6 km", routeSuggestionHint(1_609.344, targetIsFixed = true))
    }

    @Test
    fun `the window reaches ninety days back`() {
        val now = 1_000_000_000_000L
        assertEquals(now - 90L * 24 * 60 * 60 * 1000, routeSuggestionSinceMillis(now))
    }

    // ---- the words ----

    @Test
    fun `a round target is written without a decimal`() {
        assertEquals("Today ≈ 7 km", routeSuggestionHint(7_000.0))
    }

    @Test
    fun `a target is written to one place, not two`() {
        assertEquals("Today ≈ 7.2 km", routeSuggestionHint(7_243.0))
    }

    // ---- the order ----

    @Test
    fun `the nearest course is offered first`() {
        val routes = listOf(
            header(id = 1, distanceMeters = 12_000.0),
            header(id = 2, distanceMeters = 5_000.0),
            header(id = 3, distanceMeters = 7_500.0),
        )

        val offered = routesNearestFirst(routes, targetMeters = 7_000.0)

        assertEquals(listOf(3L, 2L, 1L), offered.map { it.id })
    }

    @Test
    fun `no target leaves the library's own order alone`() {
        val routes = listOf(
            header(id = 1, distanceMeters = 12_000.0),
            header(id = 2, distanceMeters = 5_000.0),
        )

        assertEquals(routes, routesNearestFirst(routes, targetMeters = null))
    }

    @Test
    fun `two courses the same distance from today keep the order they came in`() {
        val routes = listOf(
            header(id = 1, distanceMeters = 6_000.0),
            header(id = 2, distanceMeters = 8_000.0),
        )

        assertEquals(listOf(1L, 2L), routesNearestFirst(routes, targetMeters = 7_000.0).map { it.id })
    }

    @Test
    fun `a family's closest length comes first, and the rest of it follows`() {
        // Three lengths of one course, plus a stranger. The picker lists lengths one by one, so the
        // sibling nearest today is simply the row at the top — no folding to reach through.
        val routes = listOf(
            header(id = 1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(id = 2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
            header(id = 3, distanceMeters = 12_000.0, family = "Cuckoo Trail"),
            header(id = 4, distanceMeters = 20_000.0),
        )

        val offered = routesNearestFirst(routes, targetMeters = 7_500.0)

        assertEquals(2L, offered.first().id)
    }

    // ---- the runs a kind is recovered from ----

    @Test
    fun `a run recorded under no stage has no kind`() {
        assertNull(TrainingPlanProvider.runTypeOfRecordedRun(stageId = null, workoutId = easyRun))
    }

    @Test
    fun `a run's kind is its workout's`() {
        assertEquals(RunType.EASY, TrainingPlanProvider.runTypeOfRecordedRun(stage, easyRun))
        assertEquals(RunType.LONG, TrainingPlanProvider.runTypeOfRecordedRun(stage, longRun))
    }
}
