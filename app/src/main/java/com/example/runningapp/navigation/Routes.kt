package com.example.runningapp.navigation

object Routes {
    const val ARG_SESSION_ID = "sessionId"

    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val MANAGE_DEVICES = "manage_devices"
    const val HISTORY = "history"
    const val SESSION_DETAIL = "session_detail/{$ARG_SESSION_ID}"
    const val TRAINING_PLAN = "training_plan"

    fun sessionDetail(sessionId: Long): String = "session_detail/$sessionId"
}
