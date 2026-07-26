package com.example.runningapp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.export.GpxFileStore
import com.example.runningapp.export.GpxShareFile
import com.example.runningapp.export.GpxWriter
import com.example.runningapp.export.RunGpxTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    private val sessionRepository: SessionRepository,
    // Null wherever no file target is wired (tests): sharing then reports itself unavailable rather
    // than failing silently.
    private val gpxFileStore: GpxFileStore? = null
) : ViewModel() {

    private val _deleteCompleted = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val deleteCompleted = _deleteCompleted.asSharedFlow()

    private val _gpxShareReady = MutableSharedFlow<GpxShareFile>(extraBufferCapacity = 1)
    val gpxShareReady = _gpxShareReady.asSharedFlow()

    private val _gpxShareFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gpxShareFailed = _gpxShareFailed.asSharedFlow()

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
            _deleteCompleted.emit(sessionId)
        }
    }

    /**
     * Exports a run as GPX and announces the file on [gpxShareReady] (#84). Anything that leaves the
     * runner with nothing to share — no GPS track, no writable file — reports on [gpxShareFailed] so
     * the screen can say so, because a share sheet that never opens looks like a broken button.
     */
    fun shareGpx(sessionId: Long) {
        viewModelScope.launch {
            val store = gpxFileStore
            if (store == null) {
                _gpxShareFailed.emit(Unit)
                return@launch
            }
            val session = sessionRepository.getSession(sessionId)
            // Track points come through the same #38 accuracy gate as the map, so the file matches
            // the route the runner was shown.
            val trackPoints = session?.let { sessionRepository.getTrackPointsForMap(sessionId) }.orEmpty()
            if (session == null || trackPoints.isEmpty()) {
                _gpxShareFailed.emit(Unit)
                return@launch
            }
            val track = RunGpxTrack.build(
                session = session,
                trackPoints = trackPoints,
                hrSamples = sessionRepository.getHrSamples(sessionId)
            )
            val fileName = RunGpxTrack.fileName(session)
            val uri = try {
                store.write(fileName, GpxWriter.write(track))
            } catch (e: Exception) {
                Log.e("GpxExport", "Failed to write GPX for sessionId=$sessionId", e)
                null
            }
            if (uri == null) {
                _gpxShareFailed.emit(Unit)
            } else {
                _gpxShareReady.emit(
                    GpxShareFile(
                        uri = uri,
                        fileName = fileName,
                        runName = track.name
                    )
                )
            }
        }
    }
}

class SessionDetailViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val gpxFileStore: GpxFileStore? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SessionDetailViewModel(sessionRepository, gpxFileStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
