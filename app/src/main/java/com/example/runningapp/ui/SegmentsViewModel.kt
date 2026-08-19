package com.example.runningapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentDao
import com.example.runningapp.data.SegmentEffortDao
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.SegmentCut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Segments screens: the collection, one Segment's page, and cutting a new one (#69).
 *
 * The collection itself is never held here. It is a Room Flow, so a rename or a delete needs no
 * state of its own to keep in step — the table is the one copy of the truth and the screens watch
 * it, exactly as [RoutesViewModel] does with the Route library.
 *
 * Scoped to the Activity rather than to a destination, because saving a Segment is the last thing
 * the creation screen does before it is popped: work launched from the screen's own scope would be
 * cancelled by the very navigation that follows it.
 */
class SegmentsViewModel(
    private val segmentDao: SegmentDao,
    private val segmentEffortDao: SegmentEffortDao,
    /**
     * Told the id of a Segment the moment one is kept, so it can be put to every Run in history
     * (#70) — a Segment is born with its efforts and its PR on it.
     *
     * Handed in rather than done here, and deliberately: the scan is minutes of work and this
     * ViewModel dies with the Activity, while saving a Segment is the last thing the creation screen
     * does before it is popped. It belongs to something that outlives both
     * ([com.example.runningapp.AppContainer.timeSegmentAgainstHistory]).
     */
    private val onSegmentSaved: (Long) -> Unit = {},
    /** The clock a new Segment is stamped with. Injected so a test can pin the stamp. */
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val segments = segmentDao.getAllSegmentsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One Segment's own page, watched so a rename or a delete reaches it. */
    fun segment(segmentId: Long) = segmentDao.getSegmentFlow(segmentId)

    /**
     * Every time the runner has been over one Segment, watched rather than read once (#70).
     *
     * Watched because the list grows behind the page: a Segment cut a moment ago is still being put
     * to history on a scope of its own, and the efforts land one Run at a time.
     */
    fun efforts(segmentId: Long) = segmentEffortDao.getEffortsFlow(segmentId)

    /** What to tell the runner about what just happened, in words — null when there is nothing. */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /**
     * Keeps a stretch of one Run as a named place.
     *
     * Refuses anything that is not ground the Run witnessed, and anything with no name — both are
     * already refused by the screen, and are refused again here because the screen's guard is about
     * which buttons are tappable while this is about what may be written down forever.
     */
    fun saveSegment(cut: SegmentCut, name: String, sourceSessionId: Long) {
        if (cut !is SegmentCut.Cut) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val polyline = RoutePolyline.encode(
            cut.fixes.map { RoutePoint(it.latitude, it.longitude, elevationMeters = null) }
        )
        viewModelScope.launch {
            val segmentId = segmentDao.insertSegment(
                Segment(
                    name = trimmed,
                    polyline = polyline,
                    distanceMeters = cut.distanceMeters,
                    sourceSessionId = sourceSessionId,
                    createdAtMillis = now(),
                )
            )
            _message.value = segmentSavedMessage(trimmed)
            onSegmentSaved(segmentId)
        }
    }

    /** A blank name is no name, so an empty box leaves the Segment called what it was called. */
    fun rename(segment: Segment, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == segment.name) return
        viewModelScope.launch { segmentDao.renameSegment(segment.id, trimmed) }
    }

    /**
     * Forgets a Segment.
     *
     * It takes nothing else with it. The geometry lives on this row, so the Run it was traced from
     * keeps its own recording of where it went — which was never this row (see [Segment]).
     */
    fun delete(segment: Segment) {
        viewModelScope.launch { segmentDao.deleteSegment(segment.id) }
    }

    fun messageShown() {
        _message.value = null
    }
}

class SegmentsViewModelFactory(
    private val segmentDao: SegmentDao,
    private val segmentEffortDao: SegmentEffortDao,
    private val onSegmentSaved: (Long) -> Unit = {},
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SegmentsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SegmentsViewModel(segmentDao, segmentEffortDao, onSegmentSaved) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
