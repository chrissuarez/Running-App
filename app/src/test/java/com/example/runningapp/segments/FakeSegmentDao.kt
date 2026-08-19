package com.example.runningapp.segments

import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The segments table in memory, newest first, exactly as the real DAO orders it. */
class FakeSegmentDao : SegmentDao {
    private val rows = MutableStateFlow<List<Segment>>(emptyList())
    private var nextId = 1L

    val stored: List<Segment> get() = rows.value

    override fun getAllSegmentsFlow(): Flow<List<Segment>> = rows.map { list ->
        list.sortedWith(
            compareByDescending<Segment> { it.createdAtMillis }.thenByDescending { it.id }
        )
    }

    override fun getSegmentFlow(segmentId: Long): Flow<Segment?> =
        rows.map { list -> list.firstOrNull { it.id == segmentId } }

    override suspend fun getAllSegments(): List<Segment> = rows.value

    override suspend fun getSegment(segmentId: Long): Segment? = rows.value.firstOrNull { it.id == segmentId }

    override suspend fun getSegmentsMissingHistory(): List<Segment> =
        rows.value.filterNot { it.historyTimed }.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))

    override suspend fun setHistoryTimed(segmentId: Long) {
        rows.value = rows.value.map { if (it.id == segmentId) it.copy(historyTimed = true) else it }
    }

    override suspend fun insertSegment(segment: Segment): Long {
        val id = nextId++
        rows.value = rows.value + segment.copy(id = id)
        return id
    }

    override suspend fun renameSegment(segmentId: Long, name: String) {
        rows.value = rows.value.map { if (it.id == segmentId) it.copy(name = name) else it }
    }

    override suspend fun deleteSegment(segmentId: Long) {
        rows.value = rows.value.filterNot { it.id == segmentId }
    }
}
