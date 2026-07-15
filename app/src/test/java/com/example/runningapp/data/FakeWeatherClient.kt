package com.example.runningapp.data

class FakeWeatherClient(
    private val result: WeatherSnapshot? = null,
    private val shouldThrow: Boolean = false
) : WeatherClient {

    var lastRequest: Triple<Double, Double, Long>? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun fetchWeather(latitude: Double, longitude: Double, atEpochMillis: Long): WeatherSnapshot? {
        callCount += 1
        lastRequest = Triple(latitude, longitude, atEpochMillis)
        if (shouldThrow) throw RuntimeException("Simulated weather fetch failure")
        return result
    }
}
