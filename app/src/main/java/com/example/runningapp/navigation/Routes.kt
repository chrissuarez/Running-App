package com.example.runningapp.navigation

object Routes {
    const val ARG_SESSION_ID = "sessionId"
    const val ARG_SEGMENT_ID = "segmentId"

    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val MANAGE_DEVICES = "manage_devices"
    const val HISTORY = "history"
    const val SESSION_DETAIL = "session_detail/{$ARG_SESSION_ID}"
    const val TRAINING_PLAN = "training_plan"
    const val PROGRESS = "progress"
    const val MAP = "map"

    /** The Route library (#54) — the courses the runner keeps, not one of these screen addresses. */
    const val ROUTE_LIBRARY = "route_library"

    /** The Segments collection (#69) — the stretches of ground the runner has named. */
    const val SEGMENTS = "segments"
    const val SEGMENT_DETAIL = "segment_detail/{$ARG_SEGMENT_ID}"

    /**
     * Cutting a new Segment out of one Run, addressed by that Run: the track is what the screen is
     * for, so there is no such screen without a Run to read it off.
     */
    const val SEGMENT_CREATE = "segment_create/{$ARG_SESSION_ID}"

    fun sessionDetail(sessionId: Long): String = "session_detail/$sessionId"

    fun segmentDetail(segmentId: Long): String = "segment_detail/$segmentId"

    fun segmentCreate(sessionId: Long): String = "segment_create/$sessionId"
}
