package com.example.runningapp.run

import com.example.runningapp.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which teardowns took a Run with them (#309).
 */
class RunLostToTeardownTest {

    @Test
    fun `a teardown that arrives while the Run is recording loses it`() {
        assertEquals(9133L, runLostToTeardown(SessionStatus.RUNNING, 9133L))
    }

    @Test
    fun `a Run paused when the teardown came is lost too`() {
        // Paused is not over: the runner is standing at a crossing, and nothing will resume a Run
        // whose service has gone.
        assertEquals(9133L, runLostToTeardown(SessionStatus.PAUSED, 9133L))
    }

    @Test
    fun `the teardown that follows an ordinary stop loses nothing`() {
        assertNull(runLostToTeardown(SessionStatus.STOPPED, 9133L))
        assertNull(runLostToTeardown(SessionStatus.IDLE, null))
    }

    @Test
    fun `a Run still waiting on its row id was stopped, not lost`() {
        // STOPPING is the runner's own STOP arriving before the insert did. The finalize is held
        // and goes out when the id lands, on a scope that outlives the service.
        assertNull(runLostToTeardown(SessionStatus.STOPPING, 9133L))
    }

    @Test
    fun `a Run whose row never landed has nothing to lose`() {
        assertNull(runLostToTeardown(SessionStatus.RUNNING, null))
    }
}
