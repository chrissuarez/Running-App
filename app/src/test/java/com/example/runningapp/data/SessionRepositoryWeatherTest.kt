package com.example.runningapp.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SessionRepositoryWeatherTest {

    private val snapshot = WeatherSnapshot(
        temperatureC = 18.0,
        feelsLikeC = 17.0,
        humidityPercent = 62,
        windSpeedKmh = 12.0,
        conditionCode = 2
    )

    @Test
    fun `fetchAndSaveWeather writes the snapshot to the session row on success`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        repository.fetchAndSaveWeather(sessionId = 7L, latitude = 51.5, longitude = -0.1, atEpochMillis = 1_000L)

        assertEquals(Triple(51.5, -0.1, 1_000L), fakeClient.lastRequest)
        verify(mockDao).updateWeather(
            sessionId = 7L,
            tempC = 18.0,
            feelsLikeC = 17.0,
            humidityPercent = 62,
            windSpeedKmh = 12.0,
            conditionCode = 2
        )
    }

    @Test
    fun `fetchAndSaveWeather leaves the row untouched when the client returns null`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = null)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        repository.fetchAndSaveWeather(sessionId = 7L, latitude = 51.5, longitude = -0.1, atEpochMillis = 1_000L)

        verify(mockDao, never()).updateWeather(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `fetchAndSaveWeather never throws when the client throws`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(shouldThrow = true)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        // Should complete normally - a failed weather fetch must never propagate and fail the save path.
        repository.fetchAndSaveWeather(sessionId = 7L, latitude = 51.5, longitude = -0.1, atEpochMillis = 1_000L)

        verify(mockDao, never()).updateWeather(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `fetchAndSaveWeather is a no-op when no weather client is configured`() = runTest {
        val mockDao: SessionDao = mock()
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = null)

        repository.fetchAndSaveWeather(sessionId = 7L, latitude = 51.5, longitude = -0.1, atEpochMillis = 1_000L)

        verify(mockDao, never()).updateWeather(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `retryMissingWeather fetches weather for every outdoor session missing it`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        val sessionA = RunnerSession(
            id = 1L, startTime = 1_000L, runMode = "outdoor", startLatitude = 10.0, startLongitude = 20.0
        )
        val sessionB = RunnerSession(
            id = 2L, startTime = 2_000L, runMode = "outdoor", startLatitude = 30.0, startLongitude = 40.0
        )
        whenever(mockDao.getOutdoorSessionsMissingWeather()).thenReturn(listOf(sessionA, sessionB))

        repository.retryMissingWeather()

        assertEquals(2, fakeClient.callCount)
        verify(mockDao).updateWeather(
            sessionId = 1L, tempC = 18.0, feelsLikeC = 17.0, humidityPercent = 62, windSpeedKmh = 12.0, conditionCode = 2
        )
        verify(mockDao).updateWeather(
            sessionId = 2L, tempC = 18.0, feelsLikeC = 17.0, humidityPercent = 62, windSpeedKmh = 12.0, conditionCode = 2
        )
    }

    @Test
    fun `retryMissingWeather skips sessions without a start position`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        val sessionWithoutPosition = RunnerSession(
            id = 1L, startTime = 1_000L, runMode = "outdoor", startLatitude = null, startLongitude = null
        )
        whenever(mockDao.getOutdoorSessionsMissingWeather()).thenReturn(listOf(sessionWithoutPosition))

        repository.retryMissingWeather()

        assertEquals(0, fakeClient.callCount)
    }

    @Test
    fun `retryMissingWeather is a no-op when no weather client is configured`() = runTest {
        val mockDao: SessionDao = mock()
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = null)

        repository.retryMissingWeather()

        verify(mockDao, never()).getOutdoorSessionsMissingWeather()
    }
}
