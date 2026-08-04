package com.example.runningapp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.isFinished
import com.example.runningapp.export.GpxFileStore
import com.example.runningapp.export.GpxShareFile
import com.example.runningapp.export.GpxWriter
import com.example.runningapp.export.RunGpxTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionDetailViewModel(
    private val sessionRepository: SessionRepository,
    // Null wherever no file target is wired (tests): sharing then reports itself unavailable rather
    // than failing silently.
    private val gpxFileStore: GpxFileStore? = null,
    /** Where a run is turned into XML — injectable so tests can hold that work on their own clock. */
    private val assemblyDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _deleteCompleted = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val deleteCompleted = _deleteCompleted.asSharedFlow()

    // Held as state rather than announced once: an export outlives the screen that asked for it,
    // and if the activity is being recreated when the file is ready there is nobody listening. A
    // result that is kept until the screen acknowledges it cannot be missed that way — the runner
    // would otherwise tap Share and watch nothing happen at all.
    //
    // Each result names the run that asked for it, because held state says only that *something*
    // finished: an export that lands after the runner has moved on would otherwise open a chooser —
    // or report a failure — over whatever run they are looking at now.
    private val _gpxShareReady = MutableStateFlow<GpxShareFile?>(null)
    val gpxShareReady = _gpxShareReady.asStateFlow()

    private val _gpxShareFailed = MutableStateFlow<Long?>(null)
    val gpxShareFailed = _gpxShareFailed.asStateFlow()

    /** The share sheet has been opened for the ready file; it is not offered again. */
    fun gpxShareHandled() {
        _gpxShareReady.value = null
    }

    /** The failure has been shown to the runner. */
    fun gpxShareFailureShown() {
        _gpxShareFailed.value = null
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
            _deleteCompleted.emit(sessionId)
        }
    }

    /**
     * States how far a treadmill Run went, or corrects what was stated before (#231).
     *
     * Null withdraws it. Everything that follows from the number — the pace, the record book, the
     * backup, and what a correction downward owes — is the repository's
     * ([com.example.runningapp.data.SessionRepository.stateDistance]); this is only the thread it
     * runs on. Nothing is reported back: the row is watched, so the card redraws with the new
     * number of its own accord.
     */
    fun stateDistance(sessionId: Long, distanceKm: Double?) {
        viewModelScope.launch {
            sessionRepository.stateDistance(sessionId, distanceKm)
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
                _gpxShareFailed.value = sessionId
                return@launch
            }
            val session = sessionRepository.getSession(sessionId)
            // Track points come through the same #38 accuracy gate as the map, so the file matches
            // the route the runner was shown.
            val trackPoints = session?.let { sessionRepository.getTrackPointsForMap(sessionId) }.orEmpty()
            // Checked again here, not just where the button is offered: the reads below are separate
            // one-shots, so exporting a run still being recorded would stitch together a file the
            // runner never ran.
            if (session == null || !session.isFinished() || trackPoints.isEmpty()) {
                _gpxShareFailed.value = sessionId
                return@launch
            }
            val hrSamples = sessionRepository.getHrSamples(sessionId)
            // Off the main thread: an hour's run is thousands of points, each formatted into XML,
            // and the runner tapped Share expecting the sheet to open, not the screen to stall.
            val (track, contents) = withContext(assemblyDispatcher) {
                val built = RunGpxTrack.build(
                    session = session,
                    trackPoints = trackPoints,
                    hrSamples = hrSamples
                )
                built to GpxWriter.write(built)
            }
            val fileName = RunGpxTrack.fileName(session)
            val uri = try {
                store.write(fileName, contents)
            } catch (e: Exception) {
                Log.e("GpxExport", "Failed to write GPX for sessionId=$sessionId", e)
                null
            }
            if (uri == null) {
                _gpxShareFailed.value = sessionId
            } else {
                _gpxShareReady.value = GpxShareFile(
                    sessionId = sessionId,
                    uri = uri,
                    fileName = fileName,
                    runName = track.name
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
