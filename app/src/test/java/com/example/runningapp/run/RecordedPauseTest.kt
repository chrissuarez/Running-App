package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a Run's clock stopped, written down (#328).
 *
 * The Run is the only thing that knows it. GPS is torn down for the length of a Pause, so the track
 * can only ever hold the fixes either side of one — and a Run with no GPS has no fixes at all, which
 * is why a treadmill Run's Pauses used to be recorded nowhere.
 */
class RecordedPauseTest {

    @Test
    fun `a Pause is written down when it ends, at the instants the Run's clock stopped and started`() {
        val driver = Driver()
        driver.start()
        driver.advance(10)
        val pausedAt = driver.nowMillis
        driver.on(RunEvent.PauseToggled(pausedAt))
        driver.advance(30)
        val resumedAt = driver.nowMillis

        val resumed = driver.on(RunEvent.PauseToggled(resumedAt))

        val saved = resumed.only<RunEffect.SavePause>()
        assertEquals(7L, saved.runRowId)
        assertEquals(pausedAt, saved.pause.startedAtMillis)
        assertEquals(resumedAt, saved.pause.endedAtMillis)
    }

    @Test
    fun `nothing is written while the Run is still paused`() {
        // The far side of a Pause is not known until the runner comes back to it.
        val driver = Driver()
        driver.start()

        val paused = driver.on(RunEvent.PauseToggled(driver.nowMillis)) + driver.advance(30)

        assertEquals(0, paused.count<RunEffect.SavePause>())
    }

    @Test
    fun `a Run stopped while paused ends its Pause at the STOP`() {
        // The runner never came back. The Pause is still a Pause, and its far side is the end of the
        // Run — leaving it open would lose it altogether.
        val driver = Driver()
        driver.start()
        driver.advance(10)
        val pausedAt = driver.nowMillis
        driver.on(RunEvent.PauseToggled(pausedAt))
        driver.advance(20)

        val stopped = driver.stop()

        val saved = stopped.only<RunEffect.SavePause>()
        assertEquals(pausedAt, saved.pause.startedAtMillis)
        assertEquals(driver.nowMillis, saved.pause.endedAtMillis)
    }

    @Test
    fun `a Run stopped while running writes no Pause`() {
        val driver = Driver()
        driver.start()
        driver.advance(10)

        val stopped = driver.stop()

        assertEquals(0, stopped.count<RunEffect.SavePause>())
    }

    @Test
    fun `an auto-pause is a Pause like any other`() {
        // The Run's clock stops for a standstill exactly as it does for the button (#39), so the
        // file has the same reason to say where it stopped.
        val driver = Driver()
        driver.start()
        driver.advance(10)
        val pausedAt = driver.nowMillis
        driver.on(RunEvent.AutoPauseRequested(pausedAt))
        driver.advance(45)

        val moving = driver.on(RunEvent.AutoResumeRequested(driver.nowMillis))

        val saved = moving.only<RunEffect.SavePause>()
        assertEquals(pausedAt, saved.pause.startedAtMillis)
        assertEquals(driver.nowMillis, saved.pause.endedAtMillis)
    }

    @Test
    fun `every Pause of a Run is written down, in the order it took them`() {
        val driver = Driver()
        driver.start()
        driver.advance(10)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(5)
        val first = driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(60)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(15)
        val second = driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val saved = (first + second).filterIsInstance<RunEffect.SavePause>()
        assertEquals(2, saved.size)
        assertTrue(saved[0].pause.endedAtMillis < saved[1].pause.startedAtMillis)
    }

    @Test
    fun `a Pause taken before the row id arrives is held, not lost`() {
        // The row is created asynchronously, and a Pause taken in that window is a Pause like any
        // other — the same rule as the seconds of heart rate recorded there.
        val driver = Driver()
        driver.start(withRow = false)
        val pausedAt = driver.nowMillis
        driver.on(RunEvent.PauseToggled(pausedAt))
        driver.advance(20)
        val resumedAt = driver.nowMillis
        val resumed = driver.on(RunEvent.PauseToggled(resumedAt))

        val landed = driver.rowCreated()

        assertEquals("nowhere to write it yet", 0, resumed.count<RunEffect.SavePause>())
        val saved = landed.only<RunEffect.SavePause>()
        assertEquals(7L, saved.runRowId)
        assertEquals(pausedAt, saved.pause.startedAtMillis)
        assertEquals(resumedAt, saved.pause.endedAtMillis)
    }

    @Test
    fun `a stale Resume from the shade writes no second Pause`() {
        // The shade lags the Run: a Resume can arrive for a Run that is already running, and it must
        // record nothing rather than close a Pause that was closed already.
        val driver = Driver()
        driver.start()
        driver.advance(10)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))
        driver.advance(10)
        driver.on(RunEvent.PauseToggled(driver.nowMillis))

        val stale = driver.on(RunEvent.ResumeRequested(driver.nowMillis))

        assertEquals(0, stale.count<RunEffect.SavePause>())
    }
}
