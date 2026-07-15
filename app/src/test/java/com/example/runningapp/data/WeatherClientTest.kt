package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WeatherClientTest {

    private fun epochMillisFor(dateTime: String): Long {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.parse(dateTime)!!.time
    }

    // Shape captured live from https://api.open-meteo.com/v1/forecast, which the archive
    // endpoint mirrors exactly (same hourly param names and response schema).
    private val sampleResponseJson = """
        {"latitude":52.52,"longitude":13.41,"hourly":{
          "time":["2026-07-13T00:00","2026-07-13T01:00","2026-07-13T02:00"],
          "temperature_2m":[19.8,18.9,18.2],
          "apparent_temperature":[19.7,19.1,18.7],
          "relative_humidity_2m":[68,74,80],
          "wind_speed_10m":[9.0,8.3,8.0],
          "weather_code":[2,0,0]
        }}
    """.trimIndent()

    @Test
    fun `parseOpenMeteoResponse extracts the hour matching the requested timestamp`() {
        val atEpochMillis = epochMillisFor("2026-07-13T01:23")

        val snapshot = parseOpenMeteoResponse(sampleResponseJson, atEpochMillis)

        assertEquals(WeatherSnapshot(18.9, 19.1, 74, 8.3, 0), snapshot)
    }

    @Test
    fun `parseOpenMeteoResponse returns null when the hour is not in the response`() {
        val atEpochMillis = epochMillisFor("2026-07-14T01:00")

        assertNull(parseOpenMeteoResponse(sampleResponseJson, atEpochMillis))
    }

    @Test
    fun `parseOpenMeteoResponse returns null for malformed json`() {
        assertNull(parseOpenMeteoResponse("not json", epochMillisFor("2026-07-13T01:00")))
    }

    @Test
    fun `shouldUseArchiveEndpoint is false for a run that just finished`() {
        val now = epochMillisFor("2026-07-13T12:00")
        val runStart = epochMillisFor("2026-07-13T11:30")

        assertTrue(!shouldUseArchiveEndpoint(runStart, now))
    }

    @Test
    fun `shouldUseArchiveEndpoint is true once a session is more than a day old`() {
        val now = epochMillisFor("2026-07-13T12:00")
        val oldRunStart = epochMillisFor("2026-07-10T12:00")

        assertTrue(shouldUseArchiveEndpoint(oldRunStart, now))
    }

    @Test
    fun `buildOpenMeteoUrl targets the forecast endpoint for a recent run`() {
        val now = epochMillisFor("2026-07-13T12:00")
        val runStart = epochMillisFor("2026-07-13T11:30")

        val url = buildOpenMeteoUrl(51.5, -0.1, runStart, now)

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=51.5"))
        assertTrue(url.contains("longitude=-0.1"))
        assertTrue(url.contains("start_date=2026-07-13&end_date=2026-07-13"))
    }

    @Test
    fun `buildOpenMeteoUrl targets the archive endpoint for an old run`() {
        val oldRunStart = epochMillisFor("2020-01-01T08:00")

        val url = buildOpenMeteoUrl(51.5, -0.1, oldRunStart)

        assertTrue(url.startsWith("https://archive-api.open-meteo.com/v1/archive?"))
        assertTrue(url.contains("start_date=2020-01-01&end_date=2020-01-01"))
    }
}
