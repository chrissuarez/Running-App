package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.isFinished
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.run.RunMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One run as the History list shows it (#51): the recording, what it won, and where it went.
 *
 * [thumbnail] is null for a treadmill run, for a run with no route worth drawing, and — briefly —
 * for an outdoor run whose route is still being read. The row draws nothing in all three cases, so
 * a route arriving a moment after the list does is a drawing appearing rather than a row moving.
 */
data class HistoryRow(
    val session: RunnerSession,
    val medals: Int,
    val thumbnail: RouteThumbnail?,
)

class HistoryViewModel(
    private val sessionRepository: SessionRepository,
    /** Where a route's shape is worked out — anywhere but the thread drawing the list. */
    private val routeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _selectedSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSessionIds = _selectedSessionIds.asStateFlow()

    /**
     * The routes worked out so far, kept for as long as the list is.
     *
     * Held here rather than re-read per emission because the list re-emits for reasons that have
     * nothing to do with routes — a medal scored, a run deleted, a selection made — and a run's
     * route cannot change once it is finished.
     *
     * A run with nothing to draw is kept as a null rather than left out, so "asked and there is no
     * route" is not read back as "not asked yet" and re-read for the life of the screen.
     */
    private val thumbnails = MutableStateFlow<Map<Long, RouteThumbnail?>>(emptyMap())

    /**
     * The runs the list shows, read from the database once however many things here want them —
     * the rows themselves, and the pass that works their routes out.
     */
    private val sessions: StateFlow<List<RunnerSession>> = sessionRepository.recentSessionsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val rows: StateFlow<List<HistoryRow>> = combine(
        sessions,
        sessionRepository.medalCountsFlow(),
        thumbnails,
    ) { sessions, medals, drawn ->
        sessions.map { session ->
            HistoryRow(
                session = session,
                medals = medals[session.id] ?: 0,
                thumbnail = drawn[session.id],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the pass below has been set going, so opening History twice does not start two. */
    private var drawing = false

    /**
     * Start working the routes out — called when History is opened, and not before.
     *
     * This view model belongs to the activity rather than to the screen, so it exists from the
     * moment the app launches whether or not the runner ever opens History. Reading and simplifying
     * twenty tracks is thousands of stored fixes apiece; done at launch it is that much database
     * and arithmetic competing with the thing the runner did open the app to do, which is often to
     * start a Run. So the pass waits to be asked for.
     *
     * Safe to call on every visit: routes already worked out are kept ([thumbnails]), and the
     * collector is only ever started once.
     */
    fun drawRoutesWhileHistoryIsOpen() {
        if (drawing) return
        drawing = true
        drawRoutesAsRunsArrive()
    }

    /**
     * Works out the shape of each outdoor run's route, newest first, one at a time.
     *
     * Off the list's path entirely: the rows are handed over the moment the database has them, and
     * each route appears in its row as it is worked out — on [routeDispatcher], never on the thread
     * drawing the list, and never twice for the same run.
     */
    private fun drawRoutesAsRunsArrive() {
        viewModelScope.launch {
            sessions.collect { sessions ->
                sessions.filter { it.needsDrawing }
                    .forEach { session ->
                        val drawn = withContext(routeDispatcher) {
                            sessionRepository.getRouteThumbnail(session.id)
                        }
                        thumbnails.value += session.id to drawn
                    }
            }
        }
    }

    /**
     * Whether this run's route is worth working out now.
     *
     * A run still being recorded is not: its row appears in History the moment it starts, and its
     * track is a handful of fixes that will be a whole route by the time it is stopped. Answering
     * for it would bank that first minute as the shape of the run and never look again, because
     * nothing here asks twice. It is drawn when it finishes, which is when it has a shape.
     */
    private val RunnerSession.needsDrawing: Boolean
        get() = runMode == RunMode.OUTDOOR.settingValue &&
            isFinished() &&
            !thumbnails.value.containsKey(id)

    fun toggleSelection(sessionId: Long) {
        _selectedSessionIds.value = if (_selectedSessionIds.value.contains(sessionId)) {
            _selectedSessionIds.value - sessionId
        } else {
            _selectedSessionIds.value + sessionId
        }
    }

    fun clearSelection() {
        _selectedSessionIds.value = emptySet()
    }

    fun deleteSelectedSessions() {
        val ids = _selectedSessionIds.value.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            sessionRepository.deleteSessions(ids)
            clearSelection()
        }
    }

    fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return

        viewModelScope.launch {
            sessionRepository.deleteSessions(sessionIds)
            _selectedSessionIds.value = _selectedSessionIds.value - sessionIds.toSet()
        }
    }
}

class HistoryViewModelFactory(
    private val sessionRepository: SessionRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(sessionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
