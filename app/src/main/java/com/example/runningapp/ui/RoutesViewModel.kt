package com.example.runningapp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
import com.example.runningapp.routes.RouteImportOutcome
import com.example.runningapp.routes.RouteImporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Route library (#54).
 *
 * A ViewModel rather than work launched from the screen for the same reason as [RestoreViewModel]:
 * an import reads a whole file across another app's content provider, and the runner may leave the
 * screen while it happens — including by way of the file picker, which is another app's screen and
 * can take this process down with it.
 *
 * The library itself is never held here. It is a Room Flow, so a rename or a delete needs no state
 * of its own to keep in step: the table is the one copy of the truth and the screen watches it.
 */
class RoutesViewModel(
    private val routeDao: RouteDao,
    private val importer: RouteImporter,
    /** Where the file is read. Injected so a test can watch an import finish on its own scheduler. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val routes = routeDao.getAllRoutesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** What to tell the runner about what just happened, in words — null when there is nothing. */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /**
     * A file has been handed over, by the picker or by another app's "Open with".
     *
     * Null is the runner backing out of the picker, which is not a failure and says nothing. A
     * second file arriving while one is still being read is ignored rather than queued: the two
     * would race to be the newest Route, and the runner has one screen to watch either way.
     */
    fun fileChosen(uri: Uri?) {
        if (uri == null || _importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val outcome = withContext(io) { importer.import(uri) }
            _message.value = when (outcome) {
                is RouteImportOutcome.Imported -> routeImportedMessage(outcome.name)
                is RouteImportOutcome.AlreadySaved -> routeAlreadySavedMessage(outcome.name)
                is RouteImportOutcome.Remeasured -> routeRemeasuredMessage(outcome.name)
                is RouteImportOutcome.Refused -> gpxRefusalMessage(outcome.reason)
            }
            _importing.value = false
        }
    }

    /** A blank name is no name, so an empty box leaves the Route called what it was called. */
    fun rename(route: Route, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == route.name) return
        viewModelScope.launch { routeDao.renameRoute(route.id, trimmed) }
    }

    /**
     * Forgets a Route.
     *
     * It takes nothing else with it. A Route has no key into `sessions` and none out of it, so a Run
     * that followed this course keeps its own recording of where it went, which was never this row.
     */
    fun delete(route: Route) {
        viewModelScope.launch { routeDao.deleteRoute(route.id) }
    }

    fun messageShown() {
        _message.value = null
    }
}

class RoutesViewModelFactory(
    private val routeDao: RouteDao,
    private val importer: RouteImporter,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RoutesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RoutesViewModel(routeDao, importer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
