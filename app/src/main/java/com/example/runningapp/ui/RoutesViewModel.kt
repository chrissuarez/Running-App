package com.example.runningapp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.repeatedOn
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RouteLibrary
import com.example.runningapp.routes.RouteOutcome
import com.example.runningapp.routes.addedRouteId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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
 * A course the library has been asked to put in front of the runner, and which ask it is (#458).
 *
 * [ask] counts the asks one [RoutesViewModel] has made, from 1. It is here because "whose request
 * is this" cannot be answered by the course alone: the view model belongs to the Activity and
 * outlives the screen, so a request can be **made while no library screen is watching** — the
 * runner presses Back while the file is still being read, and the import lands afterwards. The id
 * on its own cannot tell that request from one made for the screen now on show, and the next visit
 * to the library would be scrolled for an import the runner walked away from.
 *
 * A screen answers that by remembering the count it arrived on: an ask at or below it was made for
 * a screen that has gone ([courseWorthShowing]). Counting rather than clearing, because clearing
 * only reaches a request that already exists, and this one does not exist yet when the runner
 * leaves.
 */
data class CourseToShow(val routeId: Long, val ask: Long)

/**
 * The request a library screen should act on, or null — [request] unless it was already standing
 * when that screen arrived (#458).
 *
 * [asksBefore] is the count the screen saw on arrival, and 0 for a screen that arrived with no
 * request standing. Pure and out of the composable, this screen's rule: what moves the list is
 * pinned by a unit test rather than by importing a file on a phone.
 */
fun courseWorthShowing(request: CourseToShow?, asksBefore: Long): CourseToShow? =
    request?.takeIf { it.ask > asksBefore }

/**
 * Drives the Route library screens (#54): the library, one course's page, and the import.
 *
 * A ViewModel rather than work launched from the screen for the same reason as [RestoreViewModel]:
 * an import reads a whole file across another app's content provider, and the runner may leave the
 * screen while it happens — including by way of the file picker, which is another app's screen and
 * can take this process down with it.
 *
 * **Screen state only** (#480). The library's rules — families, where a page lands, what a rename,
 * a flip or a delete writes, how a course is drawn — are [RouteLibrary]'s, and this class asks it.
 * What is held here is what only a screen has: the drawings worked out so far, whether a file is
 * being read, the words to show, and which course the list was asked to reach.
 *
 * The library itself is never held here. It is a Room Flow, so a rename or a delete needs no state
 * of its own to keep in step: the table is the one copy of the truth and the screen watches it.
 */
class RoutesViewModel(
    private val library: RouteLibrary,
    private val importer: RouteImporter,
    /**
     * Ticks whenever the phone's time zone changes
     * ([com.example.runningapp.AppContainer.zoneChanges]).
     *
     * A Run on a course is dated, and a Run recorded before #304 carries no offset of its own, so
     * its day is whatever the *live* zone says. Without this a phone that flies while a course's
     * page is open goes on showing the zone it left until the sessions table happens to change
     * (#320, #343) — the same tick [SegmentsViewModel] takes for the same reason.
     */
    private val zoneChanges: Flow<Unit> = emptyFlow(),
    /** Where the file is read. Injected so a test can watch an import finish on its own scheduler. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * The library as it is stored, minus the lines, watched by the pre-run picker (#56) and by
     * everything below.
     *
     * Read from the database once however many things here want it — the picker, the rows the
     * library screen shows, and the pass that draws the courses.
     */
    val routes = library.routes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The drawings worked out so far, kept for as long as this view model is.
     *
     * Held here rather than worked out per emission because the library re-emits for reasons that
     * have nothing to do with shapes — a rename, a delete, an import — and redrawing every course in
     * the library on each of those is arithmetic the runner is waiting on.
     *
     * Keyed by the Route's id alone, and the line it was drawn from is deliberately not kept beside
     * it. That rests on [com.example.runningapp.data.Route.polyline]'s second rule — a line is
     * written once and never rewritten — so an id names one line for as long as the row exists, and
     * a drawing looked up by id is that line's drawing. Keeping the line here to check against would
     * be keeping every line in the library, which is what its first rule forbids.
     *
     * A course with nothing to draw is kept as a null against its id rather than left out, so
     * "asked, and there is no shape" is not read back as "not asked yet" and re-asked for the life
     * of the screen.
     */
    private val thumbnails = MutableStateFlow<Map<Long, RouteThumbnail?>>(emptyMap())

    /**
     * The rows the library shows: what is stored, with each course's drawing as it is worked out.
     *
     * Watched from the moment this view model exists rather than from the moment the library is
     * opened, because the screen says "No routes yet" when this list is empty, and that is a claim
     * about the table. Started when the screen is, it would be empty for the first frame or two of
     * every visit and a runner with a library would be told they have none. Drawing the courses is
     * the expensive part and is still not done until asked ([drawCoursesWhileLibraryIsOpen]); this
     * is one small query.
     */
    val rows: StateFlow<List<RouteRowUi>> = combine(routes, thumbnails) { routes, drawn ->
        routes.map { route -> RouteRowUi(route = route, thumbnail = drawn[route.id]) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The library as the screen lists it: one row per family, the rest a row each (#421).
     *
     * Folded here rather than in the composable so the folding is a pure function with a test on it
     * ([routeLibraryRows]) — the same bargain [rows] and every other word on these screens make.
     *
     * Eagerly, for [rows]'s reason: the screen says "No routes yet" when this is empty, and that is
     * a claim about the table rather than about how long the read has had.
     */
    val libraryRows: StateFlow<List<RouteLibraryRow>> = rows
        .map { routeLibraryRows(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every family name the library already holds, for the box that offers them (#421) — see
     * [RouteLibrary.familyNames].
     *
     * A plain Flow rather than a StateFlow: what it must never do is answer "no families yet" before
     * the first read has landed.
     */
    val familyNames: Flow<List<String>> = library.familyNames

    /** Whether the pass below has been set going, so opening the library twice does not start two. */
    private var drawing = false

    /**
     * Start drawing the courses — called when the library is opened, and not before.
     *
     * This view model belongs to the activity rather than to the screen, so it exists from the
     * moment the app launches whether or not the runner ever opens their routes. Safe to call on
     * every visit: courses already drawn are kept ([thumbnails]), and the collector is only ever
     * started once.
     *
     * Once started it runs for the life of the view model rather than the life of the screen, which
     * is what the name is worth: what it promises is that nothing is drawn *before* the library has
     * been opened, not that anything is torn down after it is closed. Stopping it would only mean
     * drawing the same courses again on the next visit.
     */
    fun drawCoursesWhileLibraryIsOpen() {
        if (drawing) return
        drawing = true
        viewModelScope.launch {
            routes.collect { courses ->
                // Courses already drawn are dropped as their Routes are, so a library emptied and
                // filled again does not carry the old drawings about for the life of the screen.
                thumbnails.value = thumbnails.value.filterKeys { id -> courses.any { it.id == id } }
                val pending = courses.map { it.id }.filter { it !in thumbnails.value }
                if (pending.isEmpty()) return@collect
                // Drawn in one pass and published once, rather than a row at a time. Every publish
                // rebuilds the whole row list on the thread drawing it, so publishing per route
                // would rebuild it once per route in the library while the runner is already
                // scrolling. The cost of holding them back is a pause before the first drawing
                // appears; each course is bounded work ([RouteLibrary.thumbnailsOf]).
                thumbnails.value += library.thumbnailsOf(pending)
            }
        }
    }

    // --- One course's own page (#420) ---
    //
    // Here rather than in a ViewModel of its own, the arrangement [SegmentsViewModel] already makes
    // for the Segments collection and one Segment's page. The two screens are one subject: the page
    // renames a course and the library lists it under its new name. Nothing below is held — this
    // ViewModel belongs to the activity and there is no "current course" for it to keep.

    /** One course as its page shows it, watched — see [RouteLibrary.route]. */
    fun route(routeId: Long): Flow<RouteHeader?> = library.route(routeId)

    /** One course's line, drawn — see [RouteLibrary.line]. */
    suspend fun line(routeId: Long): List<MapFix> = library.line(routeId)

    /**
     * One course's drawing, for the pre-run card that names it (#496) — the library's drawing if it
     * has been made, and otherwise drawn for this course alone.
     */
    suspend fun thumbnailOf(routeId: Long): RouteThumbnail? =
        if (routeId in thumbnails.value) thumbnails.value[routeId]
        else library.thumbnailsOf(listOf(routeId))[routeId]

    /**
     * Every Run on one course, as its page prints them (#420, #74) — empty where the course is gone.
     *
     * Built here rather than in the composable so [repeatedOn] can do its work: a zone change emits
     * the same read again, which a `remember` keyed on it would pass straight over, and the dates are
     * read where the mapping runs ([routeRunsUi]). Which Runs are on the course, and against which
     * length they are ranked, are one read from [RouteLibrary.runsOn].
     */
    fun runsOnRoute(routeId: Long): Flow<List<RouteRunUi>> =
        library.runsOn(routeId)
            .repeatedOn(zoneChanges)
            .map { read -> read?.let { routeRunsUi(it.runs, it.course.distanceMeters) }.orEmpty() }

    /** Every length of one course's family, shortest first — see [RouteLibrary.siblings]. */
    fun siblings(routeId: Long): Flow<List<RouteHeader>> = library.siblings(routeId)

    /** Which of a family's lengths the page should open on — see [RouteLibrary.landingSibling]. */
    suspend fun landingSibling(routeId: Long): Long? = library.landingSibling(routeId)

    /** Puts a course in a family, or takes it out of one — see [RouteLibrary.setFamily]. */
    fun setFamily(route: RouteHeader, family: String?) {
        viewModelScope.launch { library.setFamily(route.id, family) }
    }

    /** Turns a course round for good, or back again — see [RouteLibrary.flip]. */
    fun flip(routeId: Long) {
        viewModelScope.launch { library.flip(routeId) }
    }

    /** Renames a course — see [RouteLibrary.rename] for what an empty box does. */
    fun rename(route: RouteHeader, name: String) {
        viewModelScope.launch { library.rename(route, name) }
    }

    /** Forgets a course — see [RouteLibrary.delete] for what it leaves alone. */
    fun delete(route: RouteHeader) {
        viewModelScope.launch { library.delete(route.id) }
    }

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    /** What to tell the runner about what just happened, in words — null when there is nothing. */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /**
     * The course an import has just **added**, for the library to put in front of the runner (#458).
     *
     * The library is a Room Flow and it does re-emit the instant the row is written — that much was
     * measured on the phone, not assumed. What the runner did not see was the row, because the list
     * keeps its place by the key of whatever was at the top of the screen
     * ([com.example.runningapp.ui.RoutesScreen]), and a course written newest-first lands *above*
     * that, off the top. So the list was right and the view of it was wrong, and what is missing is
     * not a re-read but somewhere to look.
     *
     * **Only an import that added a row sets it** ([RouteOutcome.addedRouteId]). A file the library
     * already held, a re-measure, and a refusal each leave the list exactly as it was, and moving the
     * screen for one of them would be the app answering a question nobody asked. The runner is told
     * what happened in words either way ([message]).
     *
     * Stamped with which ask it is, and that is the whole of how a request is kept to the screen it
     * was made for — see [CourseToShow], where the case that forces it is argued.
     *
     * Cleared by [courseShown] once the row has been reached. A request rather than a promise: an
     * id the library has no row for is left standing rather than acted on late. The reasons a
     * library has no row for a course it may still hold are counted at [routeLibraryRowShowing],
     * and this makes no claim about which one applies. Standing is inert either way — no screen
     * that arrives after it will act on it, and the next import replaces it.
     */
    private val _courseToShow = MutableStateFlow<CourseToShow?>(null)
    val courseToShow = _courseToShow.asStateFlow()

    /** How many asks this view model has made, so each one can be told from the last. */
    private var asks = 0L

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
            // Set before the words, so the screen cannot be told "saved" by one collector and left
            // looking at the old top of the list by the other for a frame in between.
            outcome.addedRouteId?.let { _courseToShow.value = CourseToShow(it, ++asks) }
            _message.value = routeOutcomeMessage(outcome, RouteDoor.FILE)
            _importing.value = false
        }
    }

    fun messageShown() {
        _message.value = null
    }

    /**
     * The request numbered [ask] is spent — the library reached its row.
     *
     * **Compare-and-clear, not clear**, the bargain
     * [com.example.runningapp.segments.RunShapeStore.putShapeUnlessTheRunMoved] makes and for its
     * reason: a screen that reached one course hands the request back after a second import may
     * already have made another, and naming the ask it is giving up means it cannot take away a
     * request that is not the one it held.
     */
    fun courseShown(ask: Long) {
        if (_courseToShow.value?.ask == ask) _courseToShow.value = null
    }
}

class RoutesViewModelFactory(
    private val library: RouteLibrary,
    private val importer: RouteImporter,
    private val zoneChanges: Flow<Unit> = emptyFlow(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RoutesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RoutesViewModel(library, importer, zoneChanges) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
