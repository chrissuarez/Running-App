package com.example.runningapp.data

import com.example.runningapp.run.RunMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterRunRoutineTest {

    private val done = mutableListOf<String>()

    /**
     * The row as the routine will find it, which is not a fixed thing: a weather look-up that
     * lands changes the answer the second read gets, and that change is what the routine reads.
     */
    private var row: RunnerSession? = null

    private fun routineFor(
        run: RunnerSession?,
        weatherFails: Boolean = false,
        weatherLands: Boolean = true,
        snapshotPublished: Boolean = true,
        secondSnapshotPublished: Boolean = snapshotPublished,
    ): AfterRunRoutine {
        row = run
        var snapshots = 0
        return AfterRunRoutine(
            readRun = { done += "read"; row },
            fetchWeather = { id, lat, lon, at ->
                done += "weather($id,$lat,$lon,$at)"
                if (weatherFails) throw IllegalStateException("no weather service")
                if (weatherLands) row = row?.copy(weatherTempC = 14.0)
            },
            snapshotHistory = {
                done += "snapshot"
                snapshots++
                if (snapshots == 1) snapshotPublished else secondSnapshotPublished
            },
        )
    }

    private fun outdoorRun(
        latitude: Double? = 51.5,
        longitude: Double? = -0.1,
    ) = RunnerSession(
        id = 7L,
        startTime = 1_000L,
        endTime = 2_000L,
        runMode = RunMode.OUTDOOR.settingValue,
        startLatitude = latitude,
        startLongitude = longitude,
    )

    @Test
    fun `history is snapshotted before an outdoor Run's weather is even asked for`() = runTest {
        val published = routineFor(outdoorRun()).perform(runRowId = 7L)

        assertEquals(
            listOf("read", "snapshot", "weather(7,51.5,-0.1,1000)", "read", "snapshot"),
            done,
        )
        assertTrue(published)
    }

    @Test
    fun `a weather look-up that fails costs the snapshot already taken nothing`() = runTest {
        val published = routineFor(outdoorRun(), weatherFails = true).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot", "weather(7,51.5,-0.1,1000)", "read"), done)
        assertTrue(published)
    }

    @Test
    fun `weather that lands is published in a second snapshot`() = runTest {
        routineFor(outdoorRun()).perform(runRowId = 7L)

        assertEquals(2, done.count { it == "snapshot" })
    }

    @Test
    fun `a look-up that saved nothing is not worth a second copy`() = runTest {
        val published = routineFor(outdoorRun(), weatherLands = false).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot", "weather(7,51.5,-0.1,1000)", "read"), done)
        assertTrue(published)
    }

    @Test
    fun `a Run that already has its weather is never asked about again`() = runTest {
        val already = outdoorRun().copy(weatherTempC = 9.5)

        val published = routineFor(already).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot"), done)
        assertTrue(published)
    }

    @Test
    fun `a treadmill Run is snapshotted with no weather look-up`() = runTest {
        val treadmill = outdoorRun().copy(
            runMode = RunMode.TREADMILL.settingValue,
            startLatitude = null,
            startLongitude = null,
        )

        routineFor(treadmill).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot"), done)
    }

    @Test
    fun `an outdoor Run that never got a fix is snapshotted with no weather look-up`() = runTest {
        routineFor(outdoorRun(latitude = null, longitude = null)).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot"), done)
    }

    @Test
    fun `a Run deleted before the work ran is still snapshotted`() = runTest {
        routineFor(run = null).perform(runRowId = 7L)

        assertEquals(listOf("read", "snapshot"), done)
    }

    @Test
    fun `a snapshot that could not be published says so, so it can be tried again`() = runTest {
        val published = routineFor(outdoorRun(), snapshotPublished = false).perform(runRowId = 7L)

        assertFalse(published)
        // Abandoned there: the copy the weather would have joined was never taken, and the whole
        // routine is about to be run again.
        assertEquals(listOf("read", "snapshot"), done)
    }

    @Test
    fun `weather stranded by a failed second snapshot asks for another go`() = runTest {
        val published = routineFor(outdoorRun(), secondSnapshotPublished = false)
            .perform(runRowId = 7L)

        assertFalse(published)
        assertEquals(2, done.count { it == "snapshot" })
    }
}
