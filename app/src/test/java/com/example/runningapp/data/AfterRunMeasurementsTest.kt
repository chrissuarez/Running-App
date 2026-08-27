package com.example.runningapp.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order the two finishes used to spell out for themselves, proved here once instead.
 */
class AfterRunMeasurementsTest {

    private val done = mutableListOf<String>()

    private fun measurementsWith(
        movingTime: Long? = 1_234L,
        movingTimeFails: Boolean = false,
        scoringFails: Boolean = false,
        segmentsFail: Boolean = false,
        shapeFails: Boolean = false,
    ) = AfterRunMeasurements(
        computeMovingTime = {
            done += "movingTime($it)"
            if (movingTimeFails) throw IllegalStateException("no track")
            movingTime
        },
        scoreAndMarkRecords = {
            done += "score($it)"
            if (scoringFails) throw IllegalStateException("book shut")
            emptyList()
        },
        timeRunAgainstSegments = {
            done += "segments($it)"
            if (segmentsFail) throw IllegalStateException("no segments")
        },
        shapeRun = {
            done += "shape($it)"
            if (shapeFails) throw IllegalStateException("no shape")
        },
    )

    @Test
    fun `measures, scores, times the segments and shapes, in that order`() = runTest {
        measurementsWith().perform(7L)

        assertEquals(listOf("movingTime(7)", "score(7)", "segments(7)", "shape(7)"), done)
    }

    @Test
    fun `hands back the moving time it measured`() = runTest {
        assertEquals(1_234L, measurementsWith(movingTime = 1_234L).perform(7L))
    }

    @Test
    fun `a moving time that cannot be measured is null, and the rest still run`() = runTest {
        assertNull(measurementsWith(movingTimeFails = true).perform(7L))

        assertEquals(listOf("movingTime(7)", "score(7)", "segments(7)", "shape(7)"), done)
    }

    @Test
    fun `a pass that throws does not stop the passes behind it`() = runTest {
        measurementsWith(scoringFails = true, segmentsFail = true, shapeFails = true).perform(7L)

        assertEquals(listOf("movingTime(7)", "score(7)", "segments(7)", "shape(7)"), done)
    }

    @Test
    fun `a throw is never raised at the caller, whose Run is already saved`() = runTest {
        val measured = measurementsWith(
            movingTimeFails = true,
            scoringFails = true,
            segmentsFail = true,
            shapeFails = true,
        ).perform(7L)

        assertNull(measured)
    }
}
