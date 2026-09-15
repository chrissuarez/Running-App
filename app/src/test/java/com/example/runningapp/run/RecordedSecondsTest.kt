package com.example.runningapp.run

import com.example.runningapp.RunType
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.plannedSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [recordedSeconds] against the Run itself (#452): the length it states is the length a Run of that
 * Workout, followed to its own end, is saved with.
 *
 * Asked of the Run rather than worked out by hand, because the answer is not the plan's sum. The
 * first Interval begins on the warm-up's last second, and a phase of no length still takes one — so
 * a door that reads the planned length can pass a Workout whose Runs are all a second short of it.
 */
class RecordedSecondsTest {

    private fun workout(warmUp: Int, run: Int, walk: Int, repeats: Int, coolDown: Int) =
        WorkoutTemplate(
            id = "w",
            title = "w",
            targetZone = 2,
            runDurationSeconds = run,
            walkDurationSeconds = walk,
            totalRepeats = repeats,
            warmUpSeconds = warmUp,
            coolDownSeconds = coolDown,
            runType = RunType.LONG,
        )

    /** The duration the Run saves when it ends itself, driven one second at a time. */
    private fun savedDuration(workout: WorkoutTemplate): Long {
        val driver = Driver()
        driver.start(config(workout = workout))
        repeat(10_000) {
            driver.advance(1).filterIsInstance<RunEffect.FinalizeRun>().firstOrNull()?.let {
                return it.totals.durationSeconds
            }
        }
        error("$workout never ended on its own")
    }

    @Test
    fun `the length it states is the length the run saves`() {
        for (warmUp in listOf(0, 1, 2)) for (run in listOf(1, 2)) for (walk in listOf(0, 1, 2))
            for (repeats in listOf(1, 2)) for (coolDown in listOf(0, 1, 3)) {
                val w = workout(warmUp, run, walk, repeats, coolDown)
                assertEquals(w.toString(), savedDuration(w), w.recordedSeconds)
            }
    }

    @Test
    fun `a one-second warm-up and cool-down around 119 seconds is saved as 120, not 121`() {
        // Codex's case on PR #497: planned 121, so a gate reading the plan passes it, while every
        // Run of it is saved at a length the evidence filter (`> 120`) drops.
        val w = workout(warmUp = 1, run = 119, walk = 0, repeats = 1, coolDown = 1)

        assertEquals(121L, w.plannedSeconds)
        assertEquals(120L, w.recordedSeconds)
        assertEquals(savedDuration(w), w.recordedSeconds)
    }
}
