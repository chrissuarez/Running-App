package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherSummaryTest {

    @Test
    fun `a full snapshot reads as one line, conditions first`() {
        assertEquals(
            "Light rain, 12°C, feels like 10°C, 80% humidity, 15 km/h wind",
            weatherSummaryOf(
                tempC = 11.6,
                feelsLikeC = 9.8,
                humidityPercent = 80,
                windSpeedKmh = 15.2,
                conditionCode = 61,
            )
        )
    }

    @Test
    fun `a snapshot with nothing in it is no line at all`() {
        // Every indoor Run, and every outdoor one recorded before the weather was fetched. An empty
        // string would still be a field the coach reads as a measurement of nothing.
        assertNull(
            weatherSummaryOf(
                tempC = null,
                feelsLikeC = null,
                humidityPercent = null,
                windSpeedKmh = null,
                conditionCode = null,
            )
        )
    }

    @Test
    fun `the parts that were recorded are said and the rest are left out`() {
        assertEquals(
            "8°C, 20 km/h wind",
            weatherSummaryOf(
                tempC = 8.0,
                feelsLikeC = null,
                humidityPercent = null,
                windSpeedKmh = 20.0,
                conditionCode = null,
            )
        )
    }

    @Test
    fun `a condition code nobody has a word for is left unsaid rather than sent as a number`() {
        // WMO leaves gaps, and Open-Meteo may add one. A bare "code 42" is a fact about a standard
        // and nothing the coach could reason from.
        assertEquals(
            "14°C",
            weatherSummaryOf(
                tempC = 14.0,
                feelsLikeC = null,
                humidityPercent = null,
                windSpeedKmh = null,
                conditionCode = 42,
            )
        )
    }

    @Test
    fun `the coach and the runner are told the weather in the same words`() {
        // One map behind both, so a run detail page reading "Heavy showers" can never sit against a
        // prompt that called the same code something else.
        assertEquals("Heavy showers", wmoConditionLabel(82))
        assertEquals("Thunderstorm", wmoConditionLabel(95))
        assertNull(wmoConditionLabel(null))
    }
}
