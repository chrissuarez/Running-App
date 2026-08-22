package com.example.runningapp.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.RunSummaryOutcome
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
import com.example.runningapp.repeatedOn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionDetailViewModel(
    private val sessionRepository: SessionRepository,
    // Null wherever no file target is wired (tests): sharing then reports itself unavailable rather
    // than failing silently.
    private val exportFileStore: ExportFileStore? = null,
    /** Where a run is turned into a file — injectable so tests can hold that work on their own clock. */
    private val assemblyDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Fires when the phone's time zone moves (#320). Empty wherever nothing supplies it (tests),
     * which costs only the re-offer — every reader below still answers from the zone in force.
     */
    private val zoneChanges: Flow<Unit> = emptyFlow(),
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

    // --- The Run Summary (#76) ---
    //
    // Two pieces of state, both naming the Run they belong to for the reason the export results do:
    // the writing outlives the screen that asked for it, and an answer landing after the runner has
    // moved on must not put a spinner — or a failure — over whatever Run they are looking at now.
    private val _summaryWriting = MutableStateFlow<Long?>(null)
    val summaryWriting = _summaryWriting.asStateFlow()

    private val _summaryFailed = MutableStateFlow<Long?>(null)
    val summaryFailed = _summaryFailed.asStateFlow()

    private val _summaryRefused = MutableStateFlow<Long?>(null)
    val summaryRefused = _summaryRefused.asStateFlow()

    /**
     * The Runs this ViewModel has already asked about of its own accord.
     *
     * The first open of a Run's page asks for its summary without the runner doing anything, and the
     * ask is made from a `LaunchedEffect` watching state the ask itself moves. Without a record of
     * having tried, a Run whose summary cannot be written — a phone with no signal — would be asked
     * about again the instant the failure cleared the spinner, and again, for as long as the page
     * was open, at the price of a network call each time.
     *
     * Tried once per launch of the app, and after that it is the button or nothing. This ViewModel
     * lives as long as the activity does, so coming back to the same Run in the same session does
     * not ask again — deliberately: a runner who was underground when they first opened it has the
     * button, and everybody else is spared a second attempt at something that just failed.
     */
    private val autoRequested = mutableSetOf<Long>()

    /** What this Run holds now, as its page watches it. Null until something has been written. */
    fun runSummary(sessionId: Long) = sessionRepository.runSummaryFlow(sessionId)

    /**
     * Whether everything the summary would describe has been measured (#76) — see
     * [SessionRepository.runSummaryFactsSettledFlow]. The page asks for no summary until it says so.
     */
    fun runSummaryFactsSettled(sessionId: Long) =
        sessionRepository.runSummaryFactsSettledFlow(sessionId)

    /**
     * Asks for this Run's summary, once, unless it has already been asked for since launch.
     *
     * Called by the page rather than by a finished Run, which is the whole point of the feature: a
     * Run nobody opens is never sent anywhere.
     */
    fun requestRunSummary(sessionId: Long) {
        if (!autoRequested.add(sessionId)) return
        askForRunSummary(sessionId)
    }

    /**
     * Asks again, at the runner's word — after a failure, or because they want different words.
     *
     * Whatever is written now is replaced when the new words land, and left exactly as it is if they
     * never do: a "write it again" that could empty the card would make the button a risk to press.
     */
    fun regenerateRunSummary(sessionId: Long) {
        autoRequested.add(sessionId)
        askForRunSummary(sessionId)
    }

    private fun askForRunSummary(sessionId: Long) {
        if (_summaryWriting.value == sessionId) return
        _summaryWriting.value = sessionId
        _summaryFailed.value = null
        _summaryRefused.value = null
        viewModelScope.launch {
            val outcome = try {
                val prompt = runSummaryPrompt(sessionId)
                if (prompt == null) RunSummaryOutcome.REFUSED
                else sessionRepository.writeRunSummary(sessionId, prompt)
            } catch (e: Exception) {
                Log.e("RunSummary", "Failed to write a summary for sessionId=$sessionId", e)
                RunSummaryOutcome.FAILED
            }
            _summaryWriting.value = null
            when (outcome) {
                RunSummaryOutcome.WRITTEN -> Unit
                // Worth telling the runner about, because trying again is a thing that can work.
                RunSummaryOutcome.FAILED -> _summaryFailed.value = sessionId
                // Said rather than swallowed. A refusal is the app declining to ask — sharing
                // switched off, a Run recorded under an opt-out — and a button that quietly does
                // nothing when pressed is worse than one that says why it cannot.
                RunSummaryOutcome.REFUSED -> _summaryRefused.value = sessionId
            }
        }
    }

    /**
     * What the model will be told about this Run, gathered at the moment of asking (#76).
     *
     * **Read here rather than taken from the screen**, which is the whole reason this is not a
     * parameter. The cards on the page are drawn from watched reads that begin empty and fill in a
     * frame or two later; a prompt built out of those can be built while `achievements` is still the
     * empty list it started as, and these words are written *once and kept*. The Run would be told
     * for ever that it won nothing, on a page that shows the medal it won.
     *
     * So the facts are read fresh, one-shot, after the Run's debts are settled
     * ([SessionRepository.runSummaryFactsSettledFlow]) — which is when there is nothing left to find
     * out about it. Null where the Run itself is gone.
     */
    private suspend fun runSummaryPrompt(sessionId: Long): String? {
        val session = sessionRepository.getSession(sessionId) ?: return null
        return buildRunSummaryPrompt(
            runSummaryFacts(
                session = session,
                achievements = sessionRepository.achievementsForRun(sessionId),
                segmentEfforts = segmentEfforts(sessionId).first(),
                matched = matchedRuns(sessionId).first(),
            )
        )
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
     * The Segments this Run went over and where each crossing placed (#71).
     *
     * The placing is worked out here rather than in SQL, off the rivals the same read carried, so
     * the medal on this card and the PR on the Segment's own page are one rule applied once
     * ([runSegmentEffortsUi]).
     */
    fun segmentEfforts(sessionId: Long) =
        sessionRepository.segmentEffortsForRunFlow(sessionId)
            .map { rows -> runSegmentEffortsUi(rows, sessionId) }

    /**
     * The other Runs over the same route as this one (#73), and where this one stands among them.
     *
     * The grouping is worked out here rather than in SQL, off every shaped Run the same read
     * carried, because the geometry rule that decides it lives in one place
     * ([com.example.runningapp.segments.runsMatch]). Null where there is no group to show.
     *
     * [repeatedOn] because the dates below are read at the moment this map runs: a Run recorded
     * before #304 carries no offset of its own, so its day is the *live* zone's answer, and the
     * per-day trend groups on that day. Without the nudge a phone that flies while this screen is
     * open goes on showing the zone it left until the database happens to change (#320).
     */
    fun matchedRuns(sessionId: Long) =
        sessionRepository.shapedRunsFlow()
            .repeatedOn(zoneChanges)
            .map { shaped -> matchedRunsUi(shaped, sessionId) }

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
     * the runner with nothing to share — a Run that is gone or not yet finished, a format this Run
     * cannot be written as, no writable file — reports on [exportShareFailed] so the screen can say
     * so, because a share sheet that never opens looks like a broken button.
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
            if (format == ExportFormat.GPX && trackPoints.isEmpty()) {
                _exportShareFailed.value = sessionId
                return@launch
            }
            // Where this Run's clock stopped, which only the recorder could have written down (#328).
            // Read below the refusal and only for the format that states them: GPX has no way to.
            val recordedPauses =
                if (format == ExportFormat.FIT) sessionRepository.getPauses(sessionId) else emptyList()
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
                            recordedPauses = recordedPauses,
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
    private val exportFileStore: ExportFileStore? = null,
    private val zoneChanges: Flow<Unit> = emptyFlow(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SessionDetailViewModel(
                sessionRepository,
                exportFileStore,
                zoneChanges = zoneChanges,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
