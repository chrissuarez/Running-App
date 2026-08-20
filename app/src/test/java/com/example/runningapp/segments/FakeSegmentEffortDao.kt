package com.example.runningapp.segments

import com.example.runningapp.data.RunSegmentEffortRow
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffort
import com.example.runningapp.data.SegmentEffortDao
import com.example.runningapp.data.SegmentEffortRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The segment_efforts table in memory, ordered as the real DAO orders it.
 *
 * [segments] stands in for the join a Run's card reads through — the real query has the names and
 * the lengths off the segments table, and a fake with no idea what a Segment is called could not
 * answer it at all. The medal rule itself is not checked here: it is put to a real SQLite database
 * in [com.example.runningapp.data.RunSegmentEffortsQueryTest].
 */
class FakeSegmentEffortDao(private val segments: () -> List<Segment> = { emptyList() }) : SegmentEffortDao {
    private val rows = MutableStateFlow<List<SegmentEffort>>(emptyList())
    private var nextId = 1L

    val stored: List<SegmentEffort> get() = rows.value

    override fun getEffortsFlow(segmentId: Long): Flow<List<SegmentEffortRow>> = rows.map { list ->
        list.filter { it.segmentId == segmentId }
            .sortedByDescending { it.startedAtMillis }
            .map {
                SegmentEffortRow(
                    effortId = it.id,
                    sessionId = it.sessionId,
                    startedAtMillis = it.startedAtMillis,
                    elapsedMillis = it.finishedAtMillis - it.startedAtMillis,
                    ranAtUtcOffsetSeconds = null,
                )
            }
    }

    override fun getEffortsForRunFlow(sessionId: Long): Flow<List<RunSegmentEffortRow>> = rows.map { list ->
        val crossed = list.filter { it.sessionId == sessionId }.map { it.segmentId }.toSet()
        val bySegment = segments().associateBy { it.id }
        list.filter { it.segmentId in crossed }
            .mapNotNull { effort ->
                val segment = bySegment[effort.segmentId] ?: return@mapNotNull null
                RunSegmentEffortRow(
                    effortId = effort.id,
                    segmentId = effort.segmentId,
                    segmentName = segment.name,
                    distanceMeters = segment.distanceMeters,
                    sessionId = effort.sessionId,
                    startedAtMillis = effort.startedAtMillis,
                    elapsedMillis = effort.finishedAtMillis - effort.startedAtMillis,
                )
            }
    }

    override suspend fun deleteEffortsOf(segmentId: Long, sessionId: Long) {
        rows.value = rows.value.filterNot { it.segmentId == segmentId && it.sessionId == sessionId }
    }

    override suspend fun insertEfforts(efforts: List<SegmentEffort>) {
        rows.value = rows.value + efforts.map { it.copy(id = nextId++) }
    }
}
