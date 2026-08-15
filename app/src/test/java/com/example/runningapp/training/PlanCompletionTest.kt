package com.example.runningapp.training

import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PlanCompletionTest {

    private val sub25 = BestEffortRequirement(RecordType.FASTEST_5K, 1_499)

    private fun completedOn(day: String, seconds: Int) = PlanCompletion(
        planId = "5k_sub_25",
        completedOnEpochDay = LocalDate.parse(day).toEpochDay(),
        seconds = seconds
    )

    @Test
    fun `the line names the day and the time that finished the plan`() {
        assertEquals(
            "Completed 14 August 2026 — you ran 5 km in 24:52.",
            planCompleteLine(completedOn("2026-08-14", 1_492), sub25)
        )
    }

    @Test
    fun `the year is printed however long ago it was`() {
        // Unlike the already-beaten line (#293), which drops this year's year as noise. This is the
        // one moment the plan existed to produce, and "14 August" for a plan finished two summers
        // ago would be the card overstating how recently it happened.
        val line = planCompleteLine(completedOn("2024-06-14", 1_499), sub25)
        assertEquals("Completed 14 June 2024 — you ran 5 km in 24:59.", line)
    }

    @Test
    fun `the distance is the requirement's own, not a number stored beside the time`() {
        val line = planCompleteLine(
            completedOn("2026-08-14", 600),
            BestEffortRequirement(RecordType.FASTEST_1K, 700)
        )
        assertEquals("Completed 14 August 2026 — you ran 1 km in 10:00.", line)
    }
}
