package com.example.runningapp

import android.content.Context
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.SessionRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(appContext)
    }

    val aiCoachClient: AiCoachClient by lazy {
        AiCoachClient()
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            sessionDao = database.sessionDao(),
            runWalkIntervalStatDao = database.runWalkIntervalStatDao(),
            settingsRepository = settingsRepository,
            aiCoachClient = aiCoachClient
        )
    }
}

private var appContainerInstance: AppContainer? = null

fun Context.runningAppContainer(): AppContainer {
    return appContainerInstance ?: synchronized(AppContainer::class.java) {
        appContainerInstance ?: AppContainer(applicationContext).also { appContainerInstance = it }
    }
}
