package com.example.runningapp.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

class SunriseSunsetCalculatorTest {

    @Test
    fun `sun is up at UTC solar noon for a mid-latitude location on any date`() {
        // London sits near 0 degrees longitude, so UTC noon is close to solar noon, and 51.5N
        // never enters polar day/night - the sun is up around noon on every date of the year.
        val noon = LocalDate.of(2024, 12, 21).atTime(12, 0).toInstant(ZoneOffset.UTC)

        assertTrue(
            SunriseSunsetCalculator.isDaytime(latitude = 51.5, longitude = -0.13, epochMillis = noon.toEpochMilli())
        )
    }

    @Test
    fun `sun is down at UTC solar midnight for a mid-latitude location on any date`() {
        val midnight = LocalDate.of(2024, 6, 21).atTime(0, 0).toInstant(ZoneOffset.UTC)

        assertFalse(
            SunriseSunsetCalculator.isDaytime(latitude = 51.5, longitude = -0.13, epochMillis = midnight.toEpochMilli())
        )
    }

    @Test
    fun `the equator has roughly a 12 hour day length even on the summer solstice`() {
        val result = SunriseSunsetCalculator.sunriseSunsetUtc(
            latitude = 0.0,
            longitude = 0.0,
            date = LocalDate.of(2024, 6, 21)
        ) as SunriseSunsetCalculator.SunriseSunset.Times

        val dayLength = Duration.between(result.sunrise, result.sunset)
        assertTrue("day length was $dayLength", dayLength.toMinutes() in (11 * 60 + 30)..(12 * 60 + 30))
    }

    @Test
    fun `a higher latitude has a longer day than the equator on the summer solstice`() {
        val date = LocalDate.of(2024, 6, 21)
        val equator = SunriseSunsetCalculator.sunriseSunsetUtc(0.0, 0.0, date) as SunriseSunsetCalculator.SunriseSunset.Times
        val oslo = SunriseSunsetCalculator.sunriseSunsetUtc(59.9, 0.0, date) as SunriseSunsetCalculator.SunriseSunset.Times

        val equatorDayLength = Duration.between(equator.sunrise, equator.sunset)
        val osloDayLength = Duration.between(oslo.sunrise, oslo.sunset)
        assertTrue(osloDayLength > equatorDayLength)
    }

    @Test
    fun `the sun never sets above the Arctic Circle on the summer solstice`() {
        // Svalbard, deep in polar day territory in June.
        val result = SunriseSunsetCalculator.sunriseSunsetUtc(
            latitude = 78.0,
            longitude = 15.0,
            date = LocalDate.of(2024, 6, 21)
        )

        assertTrue(result is SunriseSunsetCalculator.SunriseSunset.PolarDay)
        val midnight = LocalDate.of(2024, 6, 21).atTime(0, 0).toInstant(ZoneOffset.UTC)
        assertTrue(
            SunriseSunsetCalculator.isDaytime(latitude = 78.0, longitude = 15.0, epochMillis = midnight.toEpochMilli())
        )
    }

    @Test
    fun `the sun never rises above the Arctic Circle on the winter solstice`() {
        val result = SunriseSunsetCalculator.sunriseSunsetUtc(
            latitude = 78.0,
            longitude = 15.0,
            date = LocalDate.of(2024, 12, 21)
        )

        assertTrue(result is SunriseSunsetCalculator.SunriseSunset.PolarNight)
        val noon = LocalDate.of(2024, 12, 21).atTime(12, 0).toInstant(ZoneOffset.UTC)
        assertFalse(
            SunriseSunsetCalculator.isDaytime(latitude = 78.0, longitude = 15.0, epochMillis = noon.toEpochMilli())
        )
    }

    @Test
    fun `sunset falls after sunrise on an ordinary day`() {
        val result = SunriseSunsetCalculator.sunriseSunsetUtc(
            latitude = 40.7,
            longitude = -74.0,
            date = LocalDate.of(2024, 9, 22)
        ) as SunriseSunsetCalculator.SunriseSunset.Times

        assertTrue(result.sunset.isAfter(result.sunrise))
    }
}
