package com.example.runningapp.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
    fun `backfillWeather fetches weather for every run owed it`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        whenever(mockDao.getRunsOwedWeather()).thenReturn(
            listOf(
                WeatherFillTarget(sessionId = 1L, startTime = 1_000L, latitude = 10.0, longitude = 20.0),
                WeatherFillTarget(sessionId = 2L, startTime = 2_000L, latitude = 30.0, longitude = 40.0),
            )
        )

        repository.backfillWeather()

        assertEquals(2, fakeClient.callCount)
        verify(mockDao).updateWeather(
            sessionId = 1L, tempC = 18.0, feelsLikeC = 17.0, humidityPercent = 62, windSpeedKmh = 12.0, conditionCode = 2
        )
        verify(mockDao).updateWeather(
            sessionId = 2L, tempC = 18.0, feelsLikeC = 17.0, humidityPercent = 62, windSpeedKmh = 12.0, conditionCode = 2
        )
    }

    @Test
    fun `backfillWeather asks each run about its own position and its own start time`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = RecordingWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        whenever(mockDao.getRunsOwedWeather()).thenReturn(
            listOf(
                WeatherFillTarget(sessionId = 1L, startTime = 1_000L, latitude = 10.0, longitude = 20.0),
                WeatherFillTarget(sessionId = 2L, startTime = 2_000L, latitude = 30.0, longitude = 40.0),
            )
        )

        repository.backfillWeather()

        assertEquals(
            listOf(Triple(10.0, 20.0, 1_000L), Triple(30.0, 40.0, 2_000L)),
            fakeClient.requests
        )
    }

    @Test
    fun `backfillWeather carries on past a run whose fetch fails`() = runTest {
        // An offline phone, or one Run whose coordinates the service has nothing for: the Run behind
        // it must still be asked about, and must still be filled.
        val mockDao: SessionDao = mock()
        val fakeClient = RecordingWeatherClient(result = snapshot, throwOnRequest = 0)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        whenever(mockDao.getRunsOwedWeather()).thenReturn(
            listOf(
                WeatherFillTarget(sessionId = 1L, startTime = 1_000L, latitude = 10.0, longitude = 20.0),
                WeatherFillTarget(sessionId = 2L, startTime = 2_000L, latitude = 30.0, longitude = 40.0),
            )
        )

        repository.backfillWeather()

        assertEquals(2, fakeClient.requests.size)
        verify(mockDao, never()).updateWeather(
            sessionId = eq(1L), tempC = any(), feelsLikeC = any(), humidityPercent = any(),
            windSpeedKmh = any(), conditionCode = any()
        )
        verify(mockDao).updateWeather(
            sessionId = 2L, tempC = 18.0, feelsLikeC = 17.0, humidityPercent = 62, windSpeedKmh = 12.0, conditionCode = 2
        )
    }

    @Test
    fun `backfillWeather writes nothing when nothing is owed`() = runTest {
        val mockDao: SessionDao = mock()
        val fakeClient = FakeWeatherClient(result = snapshot)
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = fakeClient)

        whenever(mockDao.getRunsOwedWeather()).thenReturn(emptyList())

        repository.backfillWeather()

        assertEquals(0, fakeClient.callCount)
        verify(mockDao, never()).updateWeather(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `backfillWeather is a no-op when no weather client is configured`() = runTest {
        val mockDao: SessionDao = mock()
        val repository = SessionRepository(sessionDao = mockDao, weatherClient = null)

        repository.backfillWeather()

        verify(mockDao, never()).getRunsOwedWeather()
    }

    /** A [WeatherClient] that remembers every question it was asked, in order. */
    private class RecordingWeatherClient(
        private val result: WeatherSnapshot?,
        private val throwOnRequest: Int? = null,
    ) : WeatherClient {
        val requests = mutableListOf<Triple<Double, Double, Long>>()

        override suspend fun fetchWeather(
            latitude: Double,
            longitude: Double,
            atEpochMillis: Long
        ): WeatherSnapshot? {
            val index = requests.size
            requests += Triple(latitude, longitude, atEpochMillis)
            if (index == throwOnRequest) throw RuntimeException("Simulated weather fetch failure")
            return result
        }
    }
}
