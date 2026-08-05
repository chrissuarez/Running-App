package com.example.runningapp.training

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.zoneLowerBpm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden tests #61 pins the Effort math to. One second per beat, exactly as the recorder writes
 * them, so a script's length is its duration.
 */
class EffortTest {

    private val profile = HrProfile(maxHr = 190)

    private fun bpmAtPercent(percent: Int): Int = (190 * percent) / 100

    @Test
    fun `sixty minutes at 75 percent of max scores 180`() {
        val bpm = bpmAtPercent(75)
        val score = effortScoreOf(List(60 * 60) { bpm }, profile)

        // Zone 3 all the way, weight 3: sixty minutes times three.
        assertEquals(180, score)
    }

    @Test
    fun `every zone scores its own weight per minute`() {
        HrZone.entries.forEach { zone ->
            val bpm = zoneLowerBpm(zone, profile)
            assertEquals(
                "one hour in ${zone.zoneName}",
                60 * zone.number,
                effortScoreOf(List(60 * 60) { bpm }, profile)
            )
        }
    }

    @Test
    fun `time below zone 1 contributes nothing`() {
        val idling = zoneLowerBpm(HrZone.ENDURANCE, profile) - 1

        assertEquals(0, effortScoreOf(List(60 * 60) { idling }, profile))

        // And it does not dilute the work either: an hour of idling bolted onto an hour of Zone 3
        // leaves the Zone 3 hour scoring exactly what it scored alone.
        val working = List(60 * 60) { bpmAtPercent(75) }
        assertEquals(180, effortScoreOf(working + List(60 * 60) { idling }, profile))
    }

    @Test
    fun `a run walk script outscores the same run collapsed to its average`() {
        // Six minutes running hard, four walking easy, repeated six times — an hour.
        val running = zoneLowerBpm(HrZone.THRESHOLD, profile)
        val walking = zoneLowerBpm(HrZone.ENDURANCE, profile)
        val script = buildList {
            repeat(6) {
                repeat(6 * 60) { add(running) }
                repeat(4 * 60) { add(walking) }
            }
        }
        val average = script.sum() / script.size
        val collapsed = List(script.size) { average }

        val perSample = effortScoreOf(script, profile)!!
        val fromAverage = effortScoreOf(collapsed, profile)!!

        assertTrue(
            "per-sample $perSample should beat avg-BPM $fromAverage",
            perSample > fromAverage
        )
    }

    @Test
    fun `a run with no beats has no score rather than a zero`() {
        assertNull(effortScoreOf(emptyList(), profile))
    }

    @Test
    fun `a run of nothing but idling still has a score`() {
        // Zero, not nothing: the Run had a Strap and it read. Only a Run with no beats at all has
        // no score to show.
        assertEquals(0, effortScoreOf(listOf(40, 40, 40), profile))
    }

    @Test
    fun `a stated resting heart rate moves the weights with the zones`() {
        val reserve = HrProfile(maxHr = 190, restingHr = 50)
        val bpm = bpmAtPercent(75)

        // 142 bpm is Zone 3 of Max HR alone, but only Zone 2 of a reserve that starts at 50.
        assertEquals(3, effortWeightOf(bpm, profile))
        assertEquals(2, effortWeightOf(bpm, reserve))
    }

    @Test
    fun `a second with no reading weighs nothing`() {
        assertEquals(0, effortWeightOf(0, profile))
        assertEquals(0, effortWeightOf(-1, profile))
    }

    @Test
    fun `the score rounds rather than truncating`() {
        // 90 seconds of Zone 1 is 1.5 minutes at weight 1.
        assertEquals(2, effortScoreOfWeightedSeconds(90))
        // 89 rounds the other way.
        assertEquals(1, effortScoreOfWeightedSeconds(89))
    }

    @Test
    fun `banking a second at a time agrees with scoring the whole script`() {
        val script = (0 until 900).map { 90 + (it % 100) }

        val banked = script.fold(0L) { total, bpm -> total + effortWeightOf(bpm, profile) }

        assertEquals(effortScoreOf(script, profile), effortScoreOfWeightedSeconds(banked))
    }
}
