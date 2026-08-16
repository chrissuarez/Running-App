package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherSummaryTest {

    private fun runInWeather(
        tempC: Double? = null,
        feelsLikeC: Double? = null,
        humidityPercent: Int? = null,
        windSpeedKmh: Double? = null,
        conditionCode: Int? = null,
    ) = RunnerSession(
        startTime = 0L,
        weatherTempC = tempC,
        weatherFeelsLikeC = feelsLikeC,
        weatherHumidityPercent = humidityPercent,
        weatherWindSpeedKmh = windSpeedKmh,
        weatherConditionCode = conditionCode,
    )

    @Test
    fun `a full snapshot reads as one line, conditions first`() {
        assertEquals(
            "Light rain, 12°C, feels like 10°C, 80% humidity, 15 km/h wind",
            runInWeather(
                tempC = 11.6,
                feelsLikeC = 9.8,
                humidityPercent = 80,
                windSpeedKmh = 15.2,
                conditionCode = 61,
            ).weatherSummary()
        )
    }

    @Test
    fun `a Run with no weather recorded has no line at all`() {
        // Every treadmill Run, and every outdoor one the fetch never reached. An empty string would
        // still be a field the coach reads as a measurement of nothing.
        assertNull(runInWeather().weatherSummary())
    }

    @Test
    fun `the parts that were recorded are said and the rest are left out`() {
        assertEquals(
            "8°C, 20 km/h wind",
            runInWeather(tempC = 8.0, windSpeedKmh = 20.0).weatherSummary()
        )
    }

    @Test
    fun `a condition code nobody has a word for is left unsaid rather than sent as a number`() {
        // WMO leaves gaps, and Open-Meteo may add one. A bare "code 42" is a fact about a standard
        // and nothing the coach could reason from.
        assertEquals(
            "14°C",
            runInWeather(tempC = 14.0, conditionCode = 42).weatherSummary()
        )
    }

    @Test
    fun `just under freezing is a zero, never a minus zero`() {
        // "%.0f" writes -0.4°C as "-0°C". A minus sign against a zero says something about the cold
        // that is not true, and the run detail page and the coach both go through this.
        assertEquals("0°C", celsiusText(-0.4))
        assertEquals("-1°C", celsiusText(-0.6))
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
