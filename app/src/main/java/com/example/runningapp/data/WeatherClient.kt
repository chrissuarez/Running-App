package com.example.runningapp.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WeatherSnapshot(
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val conditionCode: Int
)

interface WeatherClient {
    suspend fun fetchWeather(latitude: Double, longitude: Double, atEpochMillis: Long): WeatherSnapshot?
}

private data class OpenMeteoResponse(val hourly: OpenMeteoHourly?)

private data class OpenMeteoHourly(
    val time: List<String>?,
    @SerializedName("temperature_2m") val temperatureC: List<Double>?,
    @SerializedName("apparent_temperature") val feelsLikeC: List<Double>?,
    @SerializedName("relative_humidity_2m") val humidityPercent: List<Int>?,
    @SerializedName("wind_speed_10m") val windSpeedKmh: List<Double>?,
    @SerializedName("weather_code") val conditionCode: List<Int>?
)

// Open-Meteo's forecast endpoint only keeps a short recent-past window; anything older is asked
// of the historical archive endpoint instead (used for launch-time retries of older sessions).
private const val ARCHIVE_CUTOFF_MILLIS = 26L * 60 * 60 * 1000

class OpenMeteoWeatherClient(private val gson: Gson = Gson()) : WeatherClient {

    override suspend fun fetchWeather(latitude: Double, longitude: Double, atEpochMillis: Long): WeatherSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(buildOpenMeteoUrl(latitude, longitude, atEpochMillis))
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w("Weather", "Open-Meteo returned HTTP ${connection.responseCode}")
                        return@withContext null
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parseOpenMeteoResponse(body, atEpochMillis, gson)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e("Weather", "Weather fetch failed", e)
                null
            }
        }
}

internal fun shouldUseArchiveEndpoint(atEpochMillis: Long, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
    return nowEpochMillis - atEpochMillis > ARCHIVE_CUTOFF_MILLIS
}

internal fun buildOpenMeteoUrl(
    latitude: Double,
    longitude: Double,
    atEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis()
): String {
    val dateKey = epochMillisToHourKey(atEpochMillis).substring(0, 10)
    val basePath = if (shouldUseArchiveEndpoint(atEpochMillis, nowEpochMillis)) {
        "https://archive-api.open-meteo.com/v1/archive"
    } else {
        "https://api.open-meteo.com/v1/forecast"
    }
    return "$basePath?latitude=$latitude&longitude=$longitude" +
        "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
        "&start_date=$dateKey&end_date=$dateKey&timezone=UTC"
}

internal fun epochMillisToHourKey(epochMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return formatter.format(Date(epochMillis))
}

internal fun parseOpenMeteoResponse(json: String, atEpochMillis: Long, gson: Gson = Gson()): WeatherSnapshot? {
    val hourly = try {
        gson.fromJson(json, OpenMeteoResponse::class.java)?.hourly
    } catch (e: Exception) {
        null
    } ?: return null

    val times = hourly.time ?: return null
    val index = times.indexOf(epochMillisToHourKey(atEpochMillis))
    if (index < 0) return null

    val temperatureC = hourly.temperatureC?.getOrNull(index) ?: return null
    val feelsLikeC = hourly.feelsLikeC?.getOrNull(index) ?: return null
    val humidityPercent = hourly.humidityPercent?.getOrNull(index) ?: return null
    val windSpeedKmh = hourly.windSpeedKmh?.getOrNull(index) ?: return null
    val conditionCode = hourly.conditionCode?.getOrNull(index) ?: return null

    return WeatherSnapshot(
        temperatureC = temperatureC,
        feelsLikeC = feelsLikeC,
        humidityPercent = humidityPercent,
        windSpeedKmh = windSpeedKmh,
        conditionCode = conditionCode
    )
}
