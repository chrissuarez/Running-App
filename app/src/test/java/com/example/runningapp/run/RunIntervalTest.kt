package com.example.runningapp.run

import com.example.runningapp.RunType
import com.example.runningapp.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Workout's Intervals, through the Run's one entry point.
 *
 * A Workout is a small number with a lot of arithmetic hanging off it — when the first Interval
 * begins, which one is next, what the runner has got through — and every one of those used to be
 * computed in a different place from the one that told the runner about it. Here they are all one
 * value, so a test that pins the state pins the notification too.
 *
 * The fixtures and the [Driver] are in `RunTestHarness.kt`.
 */

/** A Workout small enough to run to completion in a test: 4s run, 2s walk, twice, after a 3s warm-up. */
private val SHORT_WORKOUT = WorkoutTemplate(
    id = "w_short",
    title = "Short",
    targetZone = 2,
    runDurationSeconds = 4,
    walkDurationSeconds = 2,
    totalRepeats = 2,
    warmUpSeconds = 3,
    coolDownSeconds = 5,
    runType = RunType.LONG,
)

/**
 * Warm-up, then two repeats of run and walk — fourteen seconds rather than fifteen, because the
 * first Interval opens on the warm-up's *last* second rather than the one after it. That is what
 * the app does today, and every count below is measured from it.
 */
private const val WHOLE_WORKOUT_SECONDS = 3 + 4 + 2 + 4 + 2 - 1

private fun List<RunEffect>.savedStats(): List<IntervalStat> =
    filterIsInstance<RunEffect.SaveIntervalStat>().map { it.stat }

class RunIntervalStartTest {

    @Test
    fun `the first interval begins the second the warm-up ends`() {
        val driver = Driver()
        driver.start()

        assertNull("no interval while the warm-up runs", driver.state.intervals)
        driver.advance(59)
        assertNull("still none on the warm-up's last second", driver.state.intervals)

        val effects = driver.advance(1)

        assertEquals(IntervalKind.RUN, driver.state.intervals?.kind)
        assertEquals(1, driver.state.intervals?.repeat)
        assertTrue(effects.spoken().contains("Start running, interval 1 of 6."))
    }

    @Test
    fun `a skipped warm-up starts the first interval at once`() {
        val driver = Driver()
        driver.start()

        val skip = driver.skipPhase()
        assertNull("skipping only moves the phase; the second does the rest", driver.state.intervals)
        assertFalse(skip.spoken().contains("Start running, interval 1 of 6."))

        val effects = driver.advance(1)

        assertEquals(1, driver.state.intervals?.repeat)
        assertEquals(IntervalKind.RUN, driver.state.intervals?.kind)
        assertTrue(effects.spoken().contains("Start running, interval 1 of 6."))
    }

    @Test
    fun `skipping the warm-up speaks the first interval cue once, and not before the skip`() {
        val driver = Driver()
        driver.start()

        val skip = driver.skipPhase()
        val tick = driver.advance(1)
        val spoken = skip.spoken() + tick.spoken()

        // "Warm up skipped. Starting workout." on the tap; "Start running..." only on the next
        // pulse, exactly once, so the skip does not stack two cues nor open an Interval early (#149).
        assertEquals(1, spoken.count { it == "Start running, interval 1 of 6." })
        assertTrue(
            "the skip is announced before the interval cue",
            spoken.indexOf("Warm up skipped. Starting workout.") <
                spoken.indexOf("Start running, interval 1 of 6."),
        )
    }

    @Test
    fun `the first interval's first second is counted, not spent announcing it`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        driver.advance(3)

        assertEquals(3, driver.state.intervals?.secondsRemaining)
    }

    @Test
    fun `a run with no workout has no intervals at all`() {
        val driver = Driver()
        driver.start(config(workout = null))

        val effects = driver.advance(120)

        assertNull(driver.state.intervals)
        assertEquals(0, effects.count<RunEffect.SaveIntervalStat>())
        // The nought-second warm-up hands over out loud, as it does today, and nothing else is said.
        assertEquals(listOf("Starting main workout"), effects.spoken())
    }

    @Test
    fun `a workout prescribing no repeats has no intervals either`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT.copy(totalRepeats = 0)))

        val effects = driver.advance(30)

        assertNull(driver.state.intervals)
        assertEquals(0, effects.count<RunEffect.SaveIntervalStat>())
    }
}

class RunIntervalSequenceTest {

    @Test
    fun `run and walk alternate as the workout prescribes`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)

        // Interval 1, running: four seconds, the first already spent.
        assertEquals(IntervalKind.RUN to 1, driver.state.intervals?.kind to driver.state.intervals?.repeat)
        val toWalk = driver.advance(3)
        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)
        assertEquals(1, driver.state.intervals?.repeat)
        assertEquals("the walk opens with its whole length ahead of it", 2, driver.state.intervals?.secondsRemaining)
        assertTrue(toWalk.spoken().contains("Transition to walking, 2 seconds."))

        // The walk hands over to the second repeat.
        val toRun = driver.advance(2)
        assertEquals(IntervalKind.RUN, driver.state.intervals?.kind)
        assertEquals(2, driver.state.intervals?.repeat)
        assertTrue(toRun.spoken().contains("Start running, interval 2 of 2."))
    }

    @Test
    fun `a workout with no walk goes straight from one run to the next`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT.copy(walkDurationSeconds = 0)))
        driver.advance(3)

        val effects = driver.advance(4)

        assertEquals(IntervalKind.RUN, driver.state.intervals?.kind)
        assertEquals(2, driver.state.intervals?.repeat)
        assertFalse(effects.spoken().any { it.startsWith("Transition to walking") })
    }

    @Test
    fun `the last interval completing ends the workout without starting another`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        val effects = driver.advance(WHOLE_WORKOUT_SECONDS)

        assertNull("no seventh interval", driver.state.intervals)
        assertTrue(driver.state.intervalsFinished)
        assertTrue(effects.spoken().contains("Main workout complete, beginning cool down."))
        assertEquals(2, effects.count<RunEffect.SaveIntervalStat>())
    }

    @Test
    fun `a finished workout does not start itself again on the next second`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(WHOLE_WORKOUT_SECONDS)

        val effects = driver.advance(60)

        assertNull(driver.state.intervals)
        assertEquals(emptyList<String>(), effects.spoken())
        assertEquals(0, effects.count<RunEffect.SaveIntervalStat>())
    }

    @Test
    fun `an interval boundary inside a catch-up is honoured at the second it belongs to`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        val effects = driver.advanceInOneTick(3 + 4 + 2 + 1)

        assertEquals(
            listOf(
                "Starting main workout",
                "Start running, interval 1 of 2.",
                "Transition to walking, 2 seconds.",
                "Start running, interval 2 of 2.",
            ),
            effects.spoken(),
        )
        assertEquals(2, driver.state.intervals?.repeat)
        assertEquals(2, driver.state.intervals?.secondsRemaining)
    }

    @Test
    fun `intervals do not advance while the run is paused`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)
        val beforePause = driver.state.intervals

        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(30)

        assertEquals(beforePause, driver.state.intervals)
    }
}

class RunIntervalStatTest {

    @Test
    fun `a completed interval saves its numbers against the run's row`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)

        val effects = driver.advance(3)

        val save = effects.only<RunEffect.SaveIntervalStat>()
        assertEquals(7L, save.runRowId)
        assertEquals(1, save.stat.intervalIndex)
        assertEquals(4, save.stat.plannedDurationSeconds)
        assertEquals(4, save.stat.actualRunningDurationBeforeHrTriggerSeconds)
        assertEquals(0, save.stat.hrTriggerEvents)
        assertEquals(0, save.stat.totalTimeSpentWalkingDuringRunIntervalSeconds)
        assertNull(save.stat.timeIntoIntervalWhenHrExceededCapSeconds)
        assertNull(save.stat.avgHrAtTriggerInInterval)
        assertNull(save.stat.avgRecoverySecondsAfterTriggerInInterval)
    }

    @Test
    fun `each repeat saves its own interval, numbered in order`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        val effects = driver.advance(WHOLE_WORKOUT_SECONDS)

        assertEquals(listOf(1, 2), effects.savedStats().map { it.intervalIndex })
    }

    @Test
    fun `a walk interval saves nothing of its own`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 4)

        val effects = driver.advance(2)

        assertEquals(
            "the walk's end starts the next run, and saves nothing",
            0,
            effects.count<RunEffect.SaveIntervalStat>(),
        )
    }

    @Test
    fun `stopping mid-interval still banks the seconds the runner ran`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 2)

        val effects = driver.stop()

        val save = effects.only<RunEffect.SaveIntervalStat>()
        assertEquals(1, save.stat.intervalIndex)
        assertEquals(4, save.stat.plannedDurationSeconds)
        assertEquals(3, save.stat.actualRunningDurationBeforeHrTriggerSeconds)
    }

    @Test
    fun `the interval is banked before the run's own totals`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 2)

        val effects = driver.stop()

        val saveIndex = effects.indexOfFirst { it is RunEffect.SaveIntervalStat }
        val finalizeIndex = effects.indexOfFirst { it is RunEffect.FinalizeRun }
        assertTrue("expected the interval before the totals in $effects", saveIndex < finalizeIndex)
    }

    @Test
    fun `skipping the main phase banks the interval it interrupted`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 2)

        val effects = driver.skipPhase()

        assertEquals(3, effects.only<RunEffect.SaveIntervalStat>().stat.actualRunningDurationBeforeHrTriggerSeconds)
        assertNull(driver.state.intervals)
        assertTrue(driver.state.intervalsFinished)
        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
    }

    @Test
    fun `skipping out of a walk banks nothing, because a walk measures nothing`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 4)
        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)

        val effects = driver.skipPhase()

        assertEquals(0, effects.count<RunEffect.SaveIntervalStat>())
        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
    }

    @Test
    fun `an interval finishing before the row id arrives is held, not dropped`() {
        val driver = Driver()
        // No warm-up, so the first interval is over before the row id could plausibly land.
        driver.start(
            config(workout = SHORT_WORKOUT.copy(warmUpSeconds = 0)),
            withRow = false,
        )

        val whileWaiting = driver.advance(4)
        assertEquals(
            "nothing can be written against a row that does not exist yet",
            0,
            whileWaiting.count<RunEffect.SaveIntervalStat>(),
        )

        val onArrival = driver.rowCreated(runRowId = 42L)

        val save = onArrival.only<RunEffect.SaveIntervalStat>()
        assertEquals(42L, save.runRowId)
        assertEquals(1, save.stat.intervalIndex)
        assertEquals(4, save.stat.actualRunningDurationBeforeHrTriggerSeconds)
    }

    @Test
    fun `held intervals flush in the order they were run, ahead of the run's totals`() {
        val driver = Driver()
        driver.start(
            config(workout = SHORT_WORKOUT.copy(warmUpSeconds = 0)),
            withRow = false,
        )
        driver.advance(4 + 2 + 4)
        driver.stop()

        val onArrival = driver.rowCreated(runRowId = 42L)

        assertEquals(listOf(1, 2), onArrival.savedStats().map { it.intervalIndex })
        val lastSave = onArrival.indexOfLast { it is RunEffect.SaveIntervalStat }
        val finalize = onArrival.indexOfFirst { it is RunEffect.FinalizeRun }
        assertTrue("expected the intervals before the totals in $onArrival", lastSave < finalize)
    }
}

class RunIntervalProgressTest {

    @Test
    fun `the state says which interval is next and how long it lasts`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)

        assertEquals(IntervalKind.WALK, driver.state.intervals?.nextKind)
        assertEquals(2, driver.state.intervals?.nextSeconds)

        driver.advance(3)

        assertEquals(IntervalKind.RUN, driver.state.intervals?.nextKind)
        assertEquals(4, driver.state.intervals?.nextSeconds)
    }

    @Test
    fun `the last interval of the workout has nothing after it`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3 + 4 + 2 + 4)

        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)
        assertEquals(2, driver.state.intervals?.repeat)
        assertNull(driver.state.intervals?.nextKind)
        assertEquals(0, driver.state.intervals?.nextSeconds)
    }

    @Test
    fun `progress counts intervals, so a walk step is worth as much of the bar as a run step`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        driver.advance(3)
        assertEquals("a quarter of one of four intervals", 6, driver.state.intervals?.progressPercent)

        driver.advance(3)
        assertEquals("one interval of four done", 25, driver.state.intervals?.progressPercent)

        driver.advance(2)
        assertEquals("two of four done", 50, driver.state.intervals?.progressPercent)
    }

    @Test
    fun `the interval's elapsed seconds are what the screen counts up`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)

        assertEquals(1, driver.state.intervals?.secondsElapsed)

        driver.advance(2)

        assertEquals(3, driver.state.intervals?.secondsElapsed)
        assertEquals(4, driver.state.intervals?.plannedSeconds)
    }
}

class RunIntervalNotificationTest {

    @Test
    fun `the notification names the interval, its kind and what is left of it`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        val effects = driver.advance(3)

        assertEquals(
            "Int 1/2 • RUN • 00:03 left",
            effects.filterIsInstance<RunEffect.Notify>().last().text,
        )
    }

    @Test
    fun `the notification follows the run into its walk`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(3)

        val effects = driver.advance(3)

        assertEquals(
            "Int 1/2 • WALK • 00:02 left",
            effects.filterIsInstance<RunEffect.Notify>().last().text,
        )
    }

    @Test
    fun `a finished workout hands the notification into the cool-down`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))
        driver.advance(WHOLE_WORKOUT_SECONDS)

        val effects = driver.advance(1)

        // The last Interval hands the Run into its cool-down rather than leaving it climbing in the
        // main Phase, so the notification counts the cool-down down (#150).
        assertEquals("Cooldown • 00:04 left", effects.only<RunEffect.Notify>().text)
    }

    @Test
    fun `the warm-up notification is untouched by the workout waiting behind it`() {
        val driver = Driver()
        driver.start(config(workout = SHORT_WORKOUT))

        val effects = driver.advance(1)

        assertEquals("Warm-up • 00:02 left", effects.only<RunEffect.Notify>().text)
    }
}
