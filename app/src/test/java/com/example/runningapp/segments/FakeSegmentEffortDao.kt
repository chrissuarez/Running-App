package com.example.runningapp.segments

import com.example.runningapp.data.SegmentEffort
import com.example.runningapp.data.SegmentEffortDao
import com.example.runningapp.data.SegmentEffortRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The segment_efforts table in memory, ordered as the real DAO orders it. */
class FakeSegmentEffortDao : SegmentEffortDao {
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

    override suspend fun deleteEffortsOf(segmentId: Long, sessionId: Long) {
        rows.value = rows.value.filterNot { it.segmentId == segmentId && it.sessionId == sessionId }
    }

    override suspend fun insertEfforts(efforts: List<SegmentEffort>) {
        rows.value = rows.value + efforts.map { it.copy(id = nextId++) }
    }
}
