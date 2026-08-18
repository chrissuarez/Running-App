package com.example.runningapp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.isFinished
import com.example.runningapp.analysis.RunAnalysis
import com.example.runningapp.export.ExportFileStore
import com.example.runningapp.export.ExportFormat
import com.example.runningapp.export.ExportShareFile
import com.example.runningapp.export.FitWriter
import com.example.runningapp.export.GpxWriter
import com.example.runningapp.export.RunExportName
import com.example.runningapp.export.RunFitActivity
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
    private val exportFileStore: ExportFileStore? = null,
    /** Where a run is turned into a file — injectable so tests can hold that work on their own clock. */
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
    private val _exportShareReady = MutableStateFlow<ExportShareFile?>(null)
    val exportShareReady = _exportShareReady.asStateFlow()

    private val _exportShareFailed = MutableStateFlow<Long?>(null)
    val exportShareFailed = _exportShareFailed.asStateFlow()

    /** The share sheet has been opened for the ready file; it is not offered again. */
    fun exportShareHandled() {
        _exportShareReady.value = null
    }

    /** The failure has been shown to the runner. */
    fun exportShareFailureShown() {
        _exportShareFailed.value = null
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

    /** What a treadmill Run has been told it holds, as its page watches it (#282). */
    fun statedBestEfforts(sessionId: Long) = sessionRepository.statedBestEffortsFlow(sessionId)

    /**
     * States the time the console showed for one of the record distances, corrects it, or takes it
     * back (#282).
     *
     * Null [seconds] withdraws it. Which claims are refused, what a correction re-scores and what a
     * withdrawal mends are all the repository's
     * ([com.example.runningapp.data.SessionRepository.stateBestEffort]); this is only the thread it
     * runs on. Nothing is reported back: the claims are watched, so the card redraws of its own
     * accord.
     */
    fun stateBestEffort(sessionId: Long, type: RecordType, seconds: Int?) {
        viewModelScope.launch {
            sessionRepository.stateBestEffort(sessionId, type, seconds)
        }
    }

    /**
     * Says how a Run felt, or changes what was said before (#80).
     *
     * What "nothing" means — an effort withdrawn, a note emptied — is the repository's
     * ([com.example.runningapp.data.SessionRepository.editFeelFeedback]); this is only the thread it
     * runs on. Nothing is reported back: the row is watched, so the card redraws of its own accord.
     */
    fun saveFeelFeedback(sessionId: Long, effort: Int?, note: String?, isWalk: Boolean) {
        viewModelScope.launch {
            sessionRepository.editFeelFeedback(sessionId, effort, note)
            // Its own door, and after the words rather than beside them (#275): a feel and a note
            // are kept alongside a Run, and this changes what the Run is worth to the record book
            // and to the curves. Both refuse a change of nothing, so the dialog closing on an
            // unchanged switch still costs neither a row update nor a walk of the book.
            sessionRepository.markAsWalk(sessionId, isWalk)
        }
    }

    /**
     * Exports a run and announces the file on [exportShareReady] (#84, #218). Anything that leaves
     * the runner with nothing to share — a run that is not finished, no writable file — reports on
     * [exportShareFailed] so the screen can say so, because a share sheet that never opens looks
     * like a broken button.
     *
     * The two formats differ in what they need of a run. GPX needs a GPS track: a trackpoint without
     * a position is not a legal one, so a run with no fixes has nothing to write. FIT needs nothing
     * recorded at all — a Run with neither Strap nor GPS still has a Duration, a Stated Distance and
     * an Effort, and a `session` stating them with no `record` messages under it is a legal file and
     * the case this export was added for (#329). The refusal is checked here and not only where the
     * button is offered: the reads below are separate one-shots, so exporting a run still being
     * recorded would stitch together a file the runner never ran.
     */
    fun shareRun(sessionId: Long, format: ExportFormat) {
        viewModelScope.launch {
            val store = exportFileStore
            if (store == null) {
                _exportShareFailed.value = sessionId
                return@launch
            }
            val session = sessionRepository.getSession(sessionId)
            if (session == null || !session.isFinished()) {
                _exportShareFailed.value = sessionId
                return@launch
            }
            // Track points come through the same #38 accuracy gate as the map, so the file matches
            // the route the runner was shown.
            val trackPoints = sessionRepository.getTrackPointsForMap(sessionId)
            val hrSamples = sessionRepository.getHrSamples(sessionId)
            if (trackPoints.isEmpty() && format == ExportFormat.GPX) {
                _exportShareFailed.value = sessionId
                return@launch
            }
            // Off the main thread: an hour's run is thousands of points, and the runner tapped Share
            // expecting the sheet to open, not the screen to stall. FIT costs a walk of the track on
            // top of the encoding, because its laps are the run's own splits.
            //
            // The name is decided beside the bytes rather than in a second `when` on the same
            // format: they are one decision — what file this is — and split in two they could
            // disagree, which is a `.gpx` full of FIT.
            val fileName = RunExportName.fileName(session, format.extension)
            val contents = withContext(assemblyDispatcher) {
                when (format) {
                    ExportFormat.GPX ->
                        GpxWriter.write(RunGpxTrack.build(session, trackPoints, hrSamples))
                            .toByteArray(Charsets.UTF_8)

                    ExportFormat.FIT -> FitWriter.write(
                        RunFitActivity.build(
                            session = session,
                            trackPoints = trackPoints,
                            hrSamples = hrSamples,
                            analysis = RunAnalysis.of(session, hrSamples, trackPoints),
                        )
                    )
                }
            }
            val uri = try {
                store.write(fileName, contents)
            } catch (e: Exception) {
                Log.e("RunExport", "Failed to write ${format.extension} for sessionId=$sessionId", e)
                null
            }
            if (uri == null) {
                _exportShareFailed.value = sessionId
            } else {
                _exportShareReady.value = ExportShareFile(
                    sessionId = sessionId,
                    uri = uri,
                    fileName = fileName,
                    runName = RunExportName.runName(session),
                    format = format
                )
            }
        }
    }
}

class SessionDetailViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val exportFileStore: ExportFileStore? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SessionDetailViewModel(sessionRepository, exportFileStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
