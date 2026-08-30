package com.example.runningapp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.courseThumbnailOf
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.routes.RouteImportOutcome
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.routes.asShape
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
 * One Route as the library shows it (#59): the row without its line, and the shape of the course.
 *
 * [thumbnail] is null for a course with no shape worth drawing, and — briefly — for one whose shape
 * is still being worked out. The row keeps its empty square in both cases, so a drawing arriving a
 * moment after the list does is a drawing appearing rather than a row moving.
 */
data class RouteRowUi(
    val route: RouteHeader,
    val thumbnail: RouteThumbnail?,
)

/**
 * Drives the Route library (#54).
 *
 * A ViewModel rather than work launched from the screen for the same reason as [RestoreViewModel]:
 * an import reads a whole file across another app's content provider, and the runner may leave the
 * screen while it happens — including by way of the file picker, which is another app's screen and
 * can take this process down with it.
 *
 * The library itself is never held here. It is a Room Flow, so a rename or a delete needs no state
 * of its own to keep in step: the table is the one copy of the truth and the screen watches it. The
 * one thing held is the shape of each course (#59), which is worked out from the table rather than
 * stored in it.
 *
 * **No course's line is ever held here, and never two at once** (#403). The library arrives without
 * its lines ([RouteDao.getLibraryFlow]), and a line is fetched only to be drawn from and is let go
 * as soon as it has been: what stays is the thumbnail, which is a few dozen points whatever the
 * course. Holding the lines would mean holding tens of megabytes for as long as the Routes screen is
 * open, for a library of many high-detail courses — the same total that would put the upgrade out of
 * memory, moved from launch to the moment the runner opens Routes.
 */
class RoutesViewModel(
    private val routeDao: RouteDao,
    private val importer: RouteImporter,
    /** Where the file is read. Injected so a test can watch an import finish on its own scheduler. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where a course's shape is worked out — anywhere but the thread drawing the list. */
    private val courseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * The library as it is stored, minus the lines, watched by the pre-run picker (#56) and by
     * everything below.
     *
     * Read from the database once however many things here want it — the picker, the rows the
     * library screen shows, and the pass that works the shapes out.
     */
    val routes = routeDao.getLibraryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The shapes worked out so far, kept for as long as this view model is.
     *
     * Held here rather than worked out per emission because the library re-emits for reasons that
     * have nothing to do with shapes — a rename, a delete, an import — and redrawing every course in
     * the library on each of those is arithmetic the runner is waiting on.
     *
     * Keyed by the Route's id alone, and the line it was drawn from is deliberately not kept beside
     * it. A Route's line is written once, when the row is inserted, and nothing in the app rewrites
     * it (the rule is stated at [com.example.runningapp.data.Route.polyline]) — so an id names one
     * line for as long as the row exists, and a shape looked up by id is that line's shape. Keeping
     * the line here to check against would be keeping every line in the library, which is the one
     * thing this view model must not do.
     *
     * A course with nothing to draw is kept as a null against its id rather than left out, so
     * "asked, and there is no shape" is not read back as "not asked yet" and re-asked for the life
     * of the screen.
     */
    private val thumbnails = MutableStateFlow<Map<Long, RouteThumbnail?>>(emptyMap())

    /**
     * The rows the library shows: what is stored, with each course's shape as it is worked out.
     *
     * Watched from the moment this view model exists rather than from the moment the library is
     * opened, because the screen says "No routes yet" when this list is empty, and that is a claim
     * about the table. Started when the screen is, it would be empty for the first frame or two of
     * every visit and a runner with a library would be told they have none. Working the shapes out
     * is the expensive part and is still not done until asked
     * ([drawCoursesWhileLibraryIsOpen]); this is one small query.
     */
    val rows: StateFlow<List<RouteRowUi>> = combine(routes, thumbnails) { routes, drawn ->
        routes.map { route -> RouteRowUi(route = route, thumbnail = drawn[route.id]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the pass below has been set going, so opening the library twice does not start two. */
    private var drawing = false

    /**
     * Start working the courses out — called when the library is opened, and not before.
     *
     * This view model belongs to the activity rather than to the screen, so it exists from the
     * moment the app launches whether or not the runner ever opens their routes. Safe to call on
     * every visit: courses already drawn are kept ([thumbnails]), and the collector is only ever
     * started once.
     *
     * Once started it runs for the life of the view model rather than the life of the screen, which
     * is what the name is worth: what it promises is that nothing is worked out *before* the
     * library has been opened, not that anything is torn down after it is closed. Stopping it would
     * only mean drawing the same courses again on the next visit.
     */
    fun drawCoursesWhileLibraryIsOpen() {
        if (drawing) return
        drawing = true
        viewModelScope.launch {
            routes.collect { library ->
                // Courses already drawn are dropped as their Routes are, so a library emptied and
                // filled again does not carry the old shapes about for the life of the screen.
                thumbnails.value = thumbnails.value.filterKeys { id -> library.any { it.id == id } }
                val pending = library.map { it.id }.filter { it !in thumbnails.value }
                if (pending.isEmpty()) return@collect
                // Worked out in one pass and published once, rather than a row at a time. Every
                // publish rebuilds the whole row list on the thread drawing it, so publishing per
                // route would rebuild it once per route in the library while the runner is already
                // scrolling. The cost of holding them back is a pause before the first drawing
                // appears, and each course is bounded work: an imported course is stored exactly
                // as its file drew it, but the drawing samples any line down before it thins it,
                // so what one costs is bounded whatever the file held (`courseThumbnailOf`).
                //
                // One line at a time, fetched here rather than carried in on the list (#403): the
                // line is asked for, drawn from, and let go before the next id is reached, so the
                // pass holds one course's text however many the library has. What it keeps is the
                // thumbnail.
                val drawn = withContext(courseDispatcher) {
                    pending.associateWith { id ->
                        // Null is the row having been deleted since the list arrived, which the
                        // sweep above will drop on the next emission — there is nothing to draw.
                        routeDao.getRoutePolyline(id)?.let { polyline ->
                            courseThumbnailOf(RoutePolyline.decode(polyline).asShape())
                        }
                    }
                }
                thumbnails.value += drawn
            }
        }
    }

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
                is RouteImportOutcome.RemeasuredKeepingClimb ->
                    routeRemeasuredKeepingClimbMessage(outcome.name)
                is RouteImportOutcome.Refused -> gpxRefusalMessage(outcome.reason)
            }
            _importing.value = false
        }
    }

    /** A blank name is no name, so an empty box leaves the Route called what it was called. */
    fun rename(route: RouteHeader, name: String) {
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
    fun delete(route: RouteHeader) {
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
