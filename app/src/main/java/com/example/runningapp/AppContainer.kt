package com.example.runningapp

import android.content.Context
import android.util.Log
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.OpenMeteoWeatherClient
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.WeatherClient
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    init {
        // Public token only (#40) - kept out of git via local.properties, same pattern as
        // GEMINI_API_KEY. Logged rather than thrown: this init runs on every app launch, so a
        // missing token should disable the map card, not crash the whole app.
        val mapboxAccessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (mapboxAccessToken.isBlank()) {
            Log.w("AppContainer", "MAPBOX_ACCESS_TOKEN is missing - the live map card will not render")
        }
        MapboxOptions.accessToken = mapboxAccessToken
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val database: AppDatabase by lazy {
        // The v12 -> v13 zone recompute needs Max HR, which lives in DataStore rather than the
        // database. Room only invokes this from inside the migration, on its own background
        // thread, so the blocking read never lands on the main thread.
        AppDatabase.getDatabase(appContext) {
            runBlocking { settingsRepository.userSettingsFlow.first().maxHr }
        }
    }

    val aiCoachClient: AiCoachClient by lazy {
        AiCoachClient()
    }

    val weatherClient: WeatherClient by lazy {
        OpenMeteoWeatherClient()
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.sessionDao(),
            runWalkIntervalStatDao = database.runWalkIntervalStatDao(),
            trackPointDao = database.trackPointDao(),
            settingsRepository = settingsRepository,
            aiCoachClient = aiCoachClient,
            weatherClient = weatherClient
        )
    }
}

private var appContainerInstance: AppContainer? = null

fun Context.runningAppContainer(): AppContainer {
    return appContainerInstance ?: synchronized(AppContainer::class.java) {
        appContainerInstance ?: AppContainer(applicationContext).also { appContainerInstance = it }
    }
}
