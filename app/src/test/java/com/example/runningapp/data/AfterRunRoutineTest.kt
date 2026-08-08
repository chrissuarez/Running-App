package com.example.runningapp.data

import com.example.runningapp.run.RunMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfterRunRoutineTest {

    private val done = mutableListOf<String>()

    private fun routineFor(
        run: RunnerSession?,
        weatherFails: Boolean = false,
        snapshotPublished: Boolean = true,
    ) = AfterRunRoutine(
        readRun = { done += "read"; run },
        fetchWeather = { id, lat, lon, at ->
            done += "weather($id,$lat,$lon,$at)"
            if (weatherFails) throw IllegalStateException("no weather service")
        },
        snapshotHistory = { done += "snapshot"; snapshotPublished },
    )

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
    fun `an outdoor Run's weather is fetched before history is snapshotted`() = runTest {
        val published = routineFor(outdoorRun()).perform(runRowId = 7L)

        assertEquals(listOf("read", "weather(7,51.5,-0.1,1000)", "snapshot"), done)
        assertTrue(published)
    }

    @Test
    fun `a weather look-up that fails still costs history nothing`() = runTest {
        val published = routineFor(outdoorRun(), weatherFails = true).perform(runRowId = 7L)

        assertEquals(listOf("read", "weather(7,51.5,-0.1,1000)", "snapshot"), done)
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
    }
}
