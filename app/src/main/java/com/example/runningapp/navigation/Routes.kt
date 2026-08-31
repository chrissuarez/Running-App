package com.example.runningapp.navigation

import com.example.runningapp.analysis.RecordType

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

    /**
     * Every Run over one route, addressed by the Run the runner opened it from (#73): a group is
     * always somebody's group, so there is no such screen without a Run to match against.
     */
    const val MATCHED_RUNS = "matched_runs/{$ARG_SESSION_ID}"

    /**
     * One Record's own page (#75) — its all-time top ten and the trend behind it, addressed by the
     * Record itself.
     *
     * The [com.example.runningapp.analysis.RecordType]'s own name, which is what the database
     * already keys medals by, so the address and the rows are spelled the same thing.
     */
    const val ARG_RECORD_TYPE = "recordType"
    const val RECORD_DETAIL = "record_detail/{$ARG_RECORD_TYPE}"

    fun sessionDetail(sessionId: Long): String = "session_detail/$sessionId"

    fun matchedRuns(sessionId: Long): String = "matched_runs/$sessionId"

    fun segmentDetail(segmentId: Long): String = "segment_detail/$segmentId"

    fun segmentCreate(sessionId: Long): String = "segment_create/$sessionId"

    fun recordDetail(type: RecordType): String = recordDetail(type.name)

    /**
     * The same address spelled from a Record's name alone, for the one caller that holds the name
     * but no [RecordType] to go with it: a page opened for a Record this app can no longer name has
     * to be able to close itself, and it closes by the address that opened it (#412).
     */
    fun recordDetail(typeName: String): String = "record_detail/$typeName"
}
