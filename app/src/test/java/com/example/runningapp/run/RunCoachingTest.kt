package com.example.runningapp.run

import com.example.runningapp.CueCondition
import com.example.runningapp.HrZone
import com.example.runningapp.RunType
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.coachingCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences the coach can say, so a test can tell a cue from an announcement. */
private val COACHING_SENTENCES = CueCondition.entries.mapNotNull { coachingCue(it).spoken }.toSet()

private fun List<RunEffect>.coachCues(): List<String> = spoken().filter { it in COACHING_SENTENCES }

private val EASE_OFF = coachingCue(CueCondition.ABOVE).spoken!!
private val DRIFTING = coachingCue(CueCondition.ABOVE_DRIFTING).spoken!!
private val PICK_IT_UP = coachingCue(CueCondition.BELOW).spoken!!
private val BACK_ON_TARGET = coachingCue(CueCondition.RETURNED).spoken!!

/**
 * When the coach speaks, and what it says.
 *
 * The fixture Workout is a 60-second warm-up and six 180-second runs split by 60-second walks, so
 * the first run Interval covers seconds 60 to 240 of the Run. Every test says which seconds it is
 * talking about rather than leaving the arithmetic to the reader.
 */
class RunCoachingTest {

    /**
     * Seconds 1-60: the warm-up, with no Strap reading on any of them.
     *
     * Deliberately silent on the Strap rather than in target, so that a test about *when* the coach
     * speaks is not also a test of how long the five-second average takes to notice a change. The
     * first reading after this one is the whole average, so leaving target registers at once. Where
     * a test is about the average, it says so and does the arithmetic in the open.
     */
    private fun Driver.throughWarmUp() = advance(60)

    @Test
    fun `the coach says nothing for thirty seconds out of target, then climbs the ladder`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        // Out of target from second 61. The first rung falls due 30 seconds later, the second 30
        // after that, and nothing in between.
        val firstTwentyNine = driver.advanceWith(seconds = 29, bpm = ABOVE_TARGET)
        val toSixty = driver.advanceWith(seconds = 31, bpm = ABOVE_TARGET)
        val theNextMinute = driver.advanceWith(seconds = 60, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), firstTwentyNine.coachCues())
        assertEquals(listOf(EASE_OFF), toSixty.coachCues())
        assertEquals(listOf(EASE_OFF), theNextMinute.coachCues())
    }

    @Test
    fun `the warm-up is silent however far out of target the runner is`() {
        val driver = Driver()
        driver.start()

        val warmUp = driver.advanceWith(seconds = 59, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), warmUp.coachCues())
    }

    @Test
    fun `a walk Interval is silent`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        // Run Interval 1 in target, ending on second 240; the walk runs to second 300.
        driver.advanceWith(seconds = 180, bpm = IN_TARGET)

        val walk = driver.advanceWith(seconds = 55, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), walk.coachCues())
    }

    @Test
    fun `the cool-down is silent`() {
        val driver = Driver()
        driver.start()
        driver.skipPhase()
        driver.skipPhase()

        val coolDown = driver.advanceWith(seconds = 25, bpm = ABOVE_TARGET)

        assertEquals(RunPhase.COOL_DOWN, driver.state.phase)
        assertEquals(emptyList<String>(), coolDown.coachCues())
    }

    @Test
    fun `an unplanned run is left alone for its first five minutes`() {
        val driver = Driver()
        driver.start(config = config(workout = null))

        val grace = driver.advanceWith(seconds = 300, bpm = ABOVE_TARGET)
        val after = driver.advanceWith(seconds = 31, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), grace.coachCues())
        assertEquals(listOf(EASE_OFF), after.coachCues())
    }

    @Test
    fun `a high heart rate on a run Interval advises easing off and counts no walk break`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        val cued = driver.advanceWith(seconds = 31, bpm = ABOVE_TARGET)

        // The same sentence a Run with no Workout hears: on a Quality Run a stride is over before
        // heart rate responds, and on an easy one the runner can still hold a conversation, so
        // there is no Run Type the walk-break order was ever right for (ADR 0003).
        assertEquals(listOf(EASE_OFF), cued.coachCues())
        assertEquals(0, driver.state.walkBreaks)
    }

    @Test
    fun `a heart rate below target asks for pace, and counts no walk break`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        val cued = driver.advanceWith(seconds = 31, bpm = BELOW_TARGET)

        assertEquals(listOf(PICK_IT_UP), cued.coachCues())
        assertEquals(0, driver.state.walkBreaks)
    }

    @Test
    fun `a heart rate drifting up late in a run is named as drift, not as effort`() {
        val driver = Driver()
        // Zone 3 bands 133-151 at Max HR 190, so 152 is just above target — and within the
        // baseline's 12 beats, which is what makes it drift rather than overexertion.
        driver.start(config = config(workout = null, targetZone = HrZone.TEMPO))
        driver.advanceWith(seconds = 1250, bpm = 140)

        // 140 to 152 is twelve beats, so the five-second average crosses the top of the band six
        // seconds in — on second 1256 — and the first rung falls thirty seconds after that.
        val cued = driver.advanceWith(seconds = 40, bpm = 152)

        assertEquals(140, driver.state.coaching.baselineHr)
        assertEquals(listOf(DRIFTING), cued.coachCues())
    }

    @Test
    fun `coming back on target closes the cue that was spoken`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        driver.advanceWith(seconds = 31, bpm = ABOVE_TARGET)

        // Re-entry is judged at the zone's midpoint, and the average only falls back past it once
        // the whole window is in target — six seconds later.
        val back = driver.advanceWith(seconds = 6, bpm = IN_TARGET)

        assertEquals(listOf(BACK_ON_TARGET), back.coachCues())
    }

    @Test
    fun `a return is not announced from somewhere the runner was never told they had gone`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        // Twenty seconds out of target — never reaches the first rung.
        driver.advanceWith(seconds = 20, bpm = ABOVE_TARGET)

        val back = driver.advanceWith(seconds = 6, bpm = IN_TARGET)

        assertEquals(emptyList<String>(), back.coachCues())
    }

    @Test
    fun `coaching turned off mid-run silences the coach from that moment, and back on resumes it`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        driver.controls(RunControls(coachingEnabled = false))
        val silenced = driver.advanceWith(seconds = 110, bpm = ABOVE_TARGET)

        driver.controls(RunControls(coachingEnabled = true))
        val resumed = driver.advanceWith(seconds = 31, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), silenced.coachCues())
        // The ladder starts from where the runner is now, not from a debt run up while it was off.
        assertEquals(listOf(EASE_OFF), resumed.coachCues())
    }

    @Test
    fun `no spoken cue names a zone`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        val spoken = driver.advanceWith(seconds = 90, bpm = ABOVE_TARGET) +
            driver.advanceWith(seconds = 40, bpm = IN_TARGET) +
            driver.advanceWith(seconds = 40, bpm = BELOW_TARGET)

        val zoneNames = HrZone.entries.map { it.zoneName }
        assertTrue(spoken.coachCues().isNotEmpty())
        spoken.coachCues().forEach { cue ->
            zoneNames.forEach { name -> assertFalse("$cue names $name", cue.contains(name)) }
        }
    }

    @Test
    fun `a strap dropout spanning a whole walk does not let the next Interval reuse the ladder`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        // Run Interval 1 is in target until its last twenty seconds — long enough to have left
        // target, not long enough for the coach to have said anything about it.
        driver.advanceWith(seconds = 160, bpm = IN_TARGET)
        val lateInInterval = driver.advanceWith(seconds = 20, bpm = ABOVE_TARGET)


        // The Strap goes away for the whole of the walk, so no sample arrives to reset the ladder
        // the way an awake-again sample normally would. Run Interval 2 begins on second 300.
        driver.heartRateLost()
        driver.advance(60)

        val nextIntervalOpening = driver.advanceWith(seconds = 20, bpm = ABOVE_TARGET)
        val thirtySecondsIn = driver.advanceWith(seconds = 11, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), lateInInterval.coachCues())
        // Reusing the old ladder would make the runner 80 seconds overdue on their first stride of
        // Interval 2 and fire a catch-up cue immediately (Codex #124).
        assertEquals(emptyList<String>(), nextIntervalOpening.coachCues())
        assertEquals(listOf(EASE_OFF), thirtySecondsIn.coachCues())
    }

    @Test
    fun `the Interval records how long the run was held before the heart rate stopped it`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        // In target to second 90 and out from 91. The five-second average crosses the top of the
        // band on second 93, so the first rung falls on second 123 — sixty-three seconds into the
        // Interval, which opened on second 60.
        driver.advanceWith(seconds = 30, bpm = IN_TARGET)
        driver.advanceWith(seconds = 33, bpm = ABOVE_TARGET)
        // Back past the midpoint on second 129: six seconds of walking it off.
        driver.advanceWith(seconds = 6, bpm = IN_TARGET)
        val completed = driver.advanceWith(seconds = 111, bpm = IN_TARGET)

        val stat = completed.only<RunEffect.SaveIntervalStat>().stat
        assertEquals(1, stat.intervalIndex)
        assertEquals(180, stat.plannedDurationSeconds)
        assertEquals(63, stat.actualRunningDurationBeforeHrTriggerSeconds)
        assertEquals(63, stat.timeIntoIntervalWhenHrExceededCapSeconds)
        assertEquals(1, stat.hrTriggerEvents)
        assertEquals(6, stat.totalTimeSpentWalkingDuringRunIntervalSeconds)
        assertEquals(ABOVE_TARGET.toDouble(), stat.avgHrAtTriggerInInterval!!, 0.001)
        assertEquals(6.0, stat.avgRecoverySecondsAfterTriggerInInterval!!, 0.001)
    }

    @Test
    fun `an Interval the coach never spoke into saves as the clean one it was`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        val completed = driver.advanceWith(seconds = 180, bpm = IN_TARGET)

        val stat = completed.only<RunEffect.SaveIntervalStat>().stat
        assertEquals(180, stat.actualRunningDurationBeforeHrTriggerSeconds)
        assertNull(stat.timeIntoIntervalWhenHrExceededCapSeconds)
        assertEquals(0, stat.hrTriggerEvents)
        assertEquals(0, stat.totalTimeSpentWalkingDuringRunIntervalSeconds)
        assertNull(stat.avgHrAtTriggerInInterval)
        assertNull(stat.avgRecoverySecondsAfterTriggerInInterval)
    }

    @Test
    fun `a high heart rate is still recorded, as a readout rather than a verdict`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        driver.advanceWith(seconds = 30, bpm = IN_TARGET)
        driver.advanceWith(seconds = 33, bpm = ABOVE_TARGET)

        // The moment the line was crossed is what the runner wants to see afterwards, so it is kept
        // exactly as before — it simply no longer decides anything.
        assertTrue(driver.state.trigger.occurred)
        assertEquals(63, driver.state.trigger.atSecond)

        // The run Interval reaches its end on second 240 and hands over to the walk the Workout
        // prescribed — the same walk it would have handed over to at any heart rate.
        driver.advanceWith(seconds = 117, bpm = IN_TARGET)

        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)
        assertEquals(1, driver.state.walkBreaks)
        // And the Trigger belongs to the run Interval that has just ended, not to this walk. Left
        // standing it would follow the runner into the walk, where the live screen reads it as a
        // reason and puts "Safety cue active" over the walk the Workout asked for — heart rate
        // claiming the walk as its own through the screen instead of the voice.
        assertFalse(driver.state.trigger.occurred)
        assertNull(driver.state.trigger.atSecond)
    }

    @Test
    fun `the next Interval does not inherit the last one's high heart rate`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()
        driver.advanceWith(seconds = 30, bpm = IN_TARGET)
        driver.advanceWith(seconds = 33, bpm = ABOVE_TARGET)
        // To the end of the walk on second 300, which opens run Interval 2.
        driver.advanceWith(seconds = 177, bpm = IN_TARGET)

        assertEquals(2, driver.state.intervals?.repeat)
        assertFalse(driver.state.trigger.occurred)
        assertNull(driver.state.trigger.atSecond)
    }

    @Test
    fun `the Run's walk breaks count the Workout's walks, whatever the heart rate does`() {
        val driver = Driver()
        driver.start()
        driver.throughWarmUp()

        // Above target for the whole of run Interval 1, which used to buy a walk break every 30
        // seconds. The Workout prescribes one walk here, so one is what the Run counts.
        driver.advanceWith(seconds = 180, bpm = ABOVE_TARGET)
        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)
        assertEquals(1, driver.state.walkBreaks)

        // Through that walk and the whole of run Interval 2, in target throughout: still one walk
        // per repeat.
        driver.advanceWith(seconds = 240, bpm = IN_TARGET)
        assertEquals(IntervalKind.WALK, driver.state.intervals?.kind)
        assertEquals(2, driver.state.walkBreaks)
    }

    @Test
    fun `a Run with no Workout counts no walk breaks, however high the heart rate goes`() {
        val driver = Driver()
        driver.start(config = config(workout = null))

        driver.advanceWith(seconds = 600, bpm = ABOVE_TARGET)

        assertEquals(0, driver.state.walkBreaks)
    }

    /** Stage 1's Quality Run, as the plan actually declares it (#173). */
    private val qualityRun = TrainingPlanProvider
        .resolveStageWorkouts("5k_sub_25", "base_builder")
        .single { it.runType == RunType.QUALITY }

    /**
     * The whole main set, less the second the first Interval opens on — which is the warm-up's
     * last, as it is for every Workout.
     */
    private val strideSetSeconds = with(qualityRun) {
        (runDurationSeconds + walkDurationSeconds) * totalRepeats - 1
    }

    @Test
    fun `the Quality Run's easy stretch is its warm-up, and the coach is silent through it`() {
        val driver = Driver()
        driver.start(config = config(workout = qualityRun))

        // Twenty minutes of easy running, held above target the whole way.
        val easyStretch = driver.advanceWith(seconds = qualityRun.warmUpSeconds, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), easyStretch.coachCues())
    }

    @Test
    fun `the strides and their recoveries are silent, because no stride lasts a rung`() {
        val driver = Driver()
        driver.start(config = config(workout = qualityRun))
        driver.advanceWith(seconds = qualityRun.warmUpSeconds, bpm = ABOVE_TARGET)
        // The first stride opens on the warm-up's last second, as every Workout's first does.
        assertEquals(1, driver.state.intervals?.repeat)

        // Six 20s strides and their 90s recoveries. A recovery is silent by rule; a stride is
        // silent because the ladder starts again on every run Interval and 20 seconds never
        // reaches its first rung.
        val strides = driver.advanceWith(seconds = strideSetSeconds, bpm = ABOVE_TARGET)

        assertEquals(emptyList<String>(), strides.coachCues())
        assertTrue(driver.state.intervalsFinished)
    }
}
