package com.example.runningapp

import com.example.runningapp.data.RouteShapeRow
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.routes.RouteShapeStore
import com.example.runningapp.routes.RouteShaping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions

/**
 * #477 — the passes the app pays at launch are one ordered list, started once per process.
 */
class LaunchPassesTest {

    @Test
    fun `the passes are started in the order the list names them`() = runTest {
        val repository = mock<SessionRepository>()
        val routeStore = RecordingRouteStore()
        val launch = LaunchPasses(passesOn(this), launchPassesOver({ repository }, { RouteShaping(routeStore) }, 42L))

        launch.payOnce()
        advanceUntilIdle()

        inOrder(repository) {
            verify(repository).backfillMovingTime()
            verify(repository).rescueInterruptedRuns(42L)
            verify(repository).seedRecordsFromHistory()
            verify(repository).scoreMissedRecords()
            verify(repository).settleStagesMissedAtTheFinish()
            verify(repository).payWalkMarkDebts()
            verify(repository).reconcileCoachingWithHistory()
            verify(repository).backfillEffortScores()
            verify(repository).payWhatSegmentTimingOwes()
            verify(repository).backfillWeather()
            verify(repository).payWhatRunShapesOwe()
        }
        verifyNoMoreInteractions(repository)
        // The one pass not on the repository: the library's shapes.
        assertEquals(1, routeStore.asked)
    }

    @Test
    fun `the list names every pass, in order`() {
        val names = launchPassesOver({ mock() }, { RouteShaping(RecordingRouteStore()) }, 0L).map { it.name }

        assertEquals(
            listOf(
                "moving-time backfill",
                "interrupted-run rescue",
                "record seeding",
                "missed-record scoring",
                "Stage settlement",
                "Walk-mark debt",
                "coaching reconciliation",
                "Effort Score backfill",
                "Segment-timing debt",
                "weather backfill",
                "Run-shape debt",
                "Route-shape debt",
            ),
            names,
        )
    }

    @Test
    fun `a second payment in the same process starts nothing`() = runTest {
        val started = mutableListOf<String>()
        val launch = LaunchPasses(
            passesOn(this),
            listOf(LaunchPass("first") { started += "first" }, LaunchPass("second") { started += "second" }),
        )

        launch.payOnce()
        launch.payOnce()
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), started)
    }

    @Test
    fun `building the list reaches for nothing`() {
        // The repository is behind a lazy that opens the database, and the list is built on the way
        // to the first screen — so only a pass being paid may reach for it.
        launchPassesOver({ error("reached for the repository") }, { error("reached for the shaping") }, 0L)
    }

    private fun passesOn(test: TestScope) =
        BackgroundPasses(CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())) { name, e ->
            throw AssertionError("The $name pass failed", e)
        }

    private class RecordingRouteStore : RouteShapeStore {
        var asked = 0
        override suspend fun coursesMissingShapes(): List<Long> = emptyList<Long>().also { asked++ }
        override suspend fun line(routeId: Long): String? = null
        override suspend fun putShape(row: RouteShapeRow) = Unit
    }
}
