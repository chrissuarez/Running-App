package com.example.runningapp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.courseThumbnailOf
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.decoded
import com.example.runningapp.repeatedOn
import com.example.runningapp.routes.RouteImportOutcome
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.routes.asShape
import com.example.runningapp.segments.RunShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
 * **No course's line is ever held here, and never two at once** — the rule and its sizes are at
 * [com.example.runningapp.data.Route.polyline] (#403). The library arrives without its lines
 * ([RouteDao.getLibraryFlow]), and a line is fetched only to be drawn from and is let go as soon as
 * it has been: what stays is the thumbnail, which is a few dozen points whatever the course.
 *
 * Every course's line is still read once, the first time the library is opened — a thumbnail is a
 * drawing of the line, so there is no getting one without it. What the rule asks is that they are
 * not in hand together, and they never are.
 */
class RoutesViewModel(
    private val routeDao: RouteDao,
    private val importer: RouteImporter,
    /**
     * Every finished Run remembered on one course, watched — for that course's own page (#420).
     *
     * A function rather than the whole `SessionDao`, the bargain `onSegmentSaved` makes in
     * [SegmentsViewModel]: what the library wants of `sessions` is one question, and asking for the
     * DAO would hand this class every other one. It is
     * [com.example.runningapp.data.SessionDao.getRunsAlongRouteFlow], and only that.
     *
     * No default, so a wiring that forgot it would not compile rather than quietly show every course
     * an empty history.
     */
    private val runsAlongRoute: (routeId: Long) -> Flow<List<RouteRunRow>>,
    /**
     * When each of a family's lengths was last run, asked once when a page opens (#421).
     *
     * A function rather than the DAO, the bargain [runsAlongRoute] already makes. It is
     * [com.example.runningapp.data.SessionDao.lastRunOnRoutes] and only that, and it settles which
     * length a family's page lands on ([routeFamilyLandingId]).
     *
     * No default, so a wiring that forgot it would not compile rather than quietly land every family
     * on its shortest length.
     */
    private val lastRunOnRoutes: suspend (routeIds: List<Long>) -> List<RouteLastRunRow>,
    /**
     * One course's shape, watched — what its own page recognises Runs on this ground by (#74).
     *
     * A function rather than the DAO, the bargain [runsAlongRoute] makes. It is
     * [com.example.runningapp.data.RouteShapeDao.getCourseShapeFlow] and only that. Never the line
     * itself: a shape is five places, and a line is the one column in the app that can be megabytes
     * ([com.example.runningapp.data.Route.polyline]).
     *
     * Null while a course is still owed its measurement, which a page draws as no recognised Runs
     * rather than as a course with none — the remembered ones are printed either way.
     */
    private val courseShape: (routeId: Long) -> Flow<RouteShapeCandidate?> = { flowOf(null) },
    /**
     * Every finished Run that holds a shape, watched — the field a course recognises its Runs from
     * (#74).
     *
     * A function rather than the DAO, and it is
     * [com.example.runningapp.data.RunShapeDao.getShapedRunsForCoursesFlow] and only that. Defaulted
     * to nothing, which is what a build with no shapes wired shows: the remembered Runs alone, which
     * is what this page showed before #74.
     */
    private val shapedRuns: () -> Flow<List<ShapedRunRow>> = { flowOf(emptyList()) },
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
     * it. That rests on [com.example.runningapp.data.Route.polyline]'s second rule — a line is
     * written once and never rewritten — so an id names one line for as long as the row exists, and
     * a shape looked up by id is that line's shape. Keeping the line here to check against would be
     * keeping every line in the library, which is what its first rule forbids.
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
     * Every family name the library already holds, for the box that offers them (#421).
     *
     * Read off the library rather than asked of the table as a question of its own: the names *are*
     * the library's families, so a separate query would be a second answer that could disagree with
     * the rows on screen.
     *
     * A plain Flow rather than a StateFlow, for [siblings]'s reason: what it must never do is answer
     * "no families yet" before the first read has landed.
     */
    val familyNames: Flow<List<String>> = routeDao.getLibraryFlow().map { routeFamilyNames(it) }

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

    // --- One course's own page (#420) ---
    //
    // Here rather than in a ViewModel of its own, the arrangement [SegmentsViewModel] already makes
    // for the Segments collection and one Segment's page. The two screens are one subject: the page
    // renames a course and the library lists it under its new name, and both read the same table
    // through the same rules about a Route's line. A second ViewModel would be a second place those
    // rules are stated, and it would be built and thrown away with each visit to a page reached from
    // a list this one is already watching.
    //
    // Nothing below is held. Every one of them is asked for by the page, per course — this ViewModel
    // belongs to the activity and there is no "current course" for it to keep.

    /**
     * One course as its page shows it, watched: a rename made on the page reaches its own title, and
     * a delete made in the library empties it.
     *
     * The row without its line ([RouteDao.getRouteHeaderFlow]) — the line comes back on its own from
     * [line], because it never changes and the row does. See [com.example.runningapp.data.Route.polyline].
     */
    fun route(routeId: Long): Flow<RouteHeader?> = routeDao.getRouteHeaderFlow(routeId)

    /**
     * One course's line, drawn — read once, because a Route's line is written once and never
     * rewritten ([com.example.runningapp.data.Route.polyline]).
     *
     * Empty for a row that has gone, which the page draws as no map rather than as an empty course.
     * Decoded off the thread drawing the page: a course kept before #354 holds every point its file
     * held.
     */
    suspend fun line(routeId: Long): List<MapFix> = withContext(courseDispatcher) {
        routeDao.getRoutePolyline(routeId)
            ?.let { RoutePolyline.decode(it).map { point -> MapFix(point.latitude, point.longitude) } }
            .orEmpty()
    }

    /**
     * Every Run remembered on one course, as its page prints them (#420).
     *
     * The course travels with the Runs because the best-time band is measured against the course's
     * own length, so the row and the Runs have to come from one read rather than two taken a moment
     * apart. Empty where the row is gone — a Run on ground the library no longer keeps is nothing
     * this page can rank.
     *
     * Built here rather than in the composable so [repeatedOn] can do its work: a zone change emits
     * the same rows again, which a `remember` keyed on those rows would pass straight over, and the
     * dates are read where the mapping runs ([routeRunsUi]).
     *
     * Since #74 the Runs recognised on this ground arrive here too, which is why the course's own
     * shape and every shaped Run are in the same read: the list and the shape it is filtered by must
     * be one answer, not two taken a moment apart.
     */
    fun runsOnRoute(routeId: Long): Flow<List<RouteRunUi>> =
        combine(
            routeDao.getRouteHeaderFlow(routeId),
            runsAlongRoute(routeId),
            courseShape(routeId),
            shapedRuns(),
        ) { row, remembered, course, shaped ->
            RunsOnOneCourse(row, remembered, course?.decoded(), shaped)
        }
            .repeatedOn(zoneChanges)
            .map { read ->
                if (read.course == null) {
                    emptyList()
                } else {
                    routeRunsUi(
                        runsOnCourse(read.remembered, read.shaped, read.shape),
                        read.course.distanceMeters,
                    )
                }
            }

    /**
     * One read of everything a course's list of Runs is built from.
     *
     * A type rather than a nest of Pairs because there are four of them now, and because they must
     * travel together: the best-time band is measured against the course's own length and the
     * recognising against the course's own shape, so a row taken a moment apart from the Runs could
     * rank them against a course the runner has since renamed, re-measured or deleted.
     */
    private data class RunsOnOneCourse(
        val course: RouteHeader?,
        val remembered: List<RouteRunRow>,
        val shape: RunShape?,
        val shaped: List<ShapedRunRow>,
    )

    /**
     * Every length of one course's family, shortest first — itself alone where it has none (#421).
     *
     * Watched, because the chips move under an open page: a length imported, deleted, or given this
     * very family name on this very page has to appear on the row of chips without the runner
     * leaving and coming back.
     *
     * Read off the library flow rather than asked for by family name, and folded by the same
     * [routeFamilyKey] the library row uses — so the chips and the row settle on one answer to "how
     * many lengths is this" rather than two rules that could drift apart. They are separate reads of
     * one table, so they agree once both have caught up, not within a single frame; nothing here
     * needs them to, because the two are never on screen together.
     *
     * From the table rather than from [routes], which is a StateFlow and so answers with the empty
     * list it was seeded with until its first read lands — a page opened on that answer would draw
     * no chips at all on a course that has three.
     */
    fun siblings(routeId: Long): Flow<List<RouteHeader>> =
        routeDao.getLibraryFlow().map { library -> routeSiblings(library, routeId) }

    /**
     * Which of a family's lengths the page should open on — see [routeFamilyLandingId] (#421).
     *
     * Asked once, when the page opens, rather than watched: it settles where the runner lands, and a
     * page that re-landed every time a Run finished would move the course out from under them.
     *
     * The library is read afresh here rather than taken from [routes], which is a StateFlow that
     * answers with whatever it last held — an empty list, on a page opened before the first read
     * lands, would land every family on nothing.
     *
     * Null is the course having gone from the library, which the page draws as no course.
     */
    suspend fun landingSibling(routeId: Long): Long? {
        val siblings = routeSiblings(routeDao.getLibraryFlow().first(), routeId)
        if (siblings.size < 2) return siblings.firstOrNull()?.id
        return routeFamilyLandingId(siblings, lastRunOnRoutes(siblings.map { it.id }))
    }

    /**
     * Puts a course in a family, or takes it out of one (#421).
     *
     * A blank box is no family, and it is [RouteDao.setRouteFamily] that settles that rather than
     * this — so the rule holds for every caller of the table, not only for this screen.
     *
     * Unlike [rename], an unchanged value is still written. There is nothing to protect: the write
     * is one short column on one row, and comparing first would mean deciding here what "unchanged"
     * means about a value the table trims.
     */
    fun setFamily(route: RouteHeader, family: String?) {
        viewModelScope.launch { routeDao.setRouteFamily(route.id, family) }
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
    private val runsAlongRoute: (routeId: Long) -> Flow<List<RouteRunRow>>,
    private val lastRunOnRoutes: suspend (routeIds: List<Long>) -> List<RouteLastRunRow>,
    private val courseShape: (routeId: Long) -> Flow<RouteShapeCandidate?> = { flowOf(null) },
    private val shapedRuns: () -> Flow<List<ShapedRunRow>> = { flowOf(emptyList()) },
    private val zoneChanges: Flow<Unit> = emptyFlow(),
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RoutesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RoutesViewModel(
                routeDao,
                importer,
                runsAlongRoute,
                lastRunOnRoutes,
                courseShape,
                shapedRuns,
                zoneChanges,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
