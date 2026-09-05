package com.example.runningapp.ui

import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.ThumbPoint
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.runShapeRowOf
import com.example.runningapp.routes.CourseShape
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.routes.routeShapeOf
import com.example.runningapp.segments.RunShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One route, many lengths (#421) — the folding, the words and the landing.
 *
 * Every claim here is one a runner can see: how many rows the library has, what the row says, and
 * which length the page opens on. Nothing about the table.
 */
class RouteFamiliesTest {

    private fun header(
        id: Long,
        name: String = "Route $id",
        distanceMeters: Double = 5_000.0,
        family: String? = null,
        createdAtMillis: Long = id,
    ) = RouteHeader(
        id = id,
        name = name,
        distanceMeters = distanceMeters,
        elevationGainMeters = null,
        createdAtMillis = createdAtMillis,
        source = RouteSource.IMPORTED,
        family = family,
    )

    private fun row(header: RouteHeader, thumbnail: RouteThumbnail? = null) =
        RouteRowUi(route = header, thumbnail = thumbnail)

    private fun drawing(x: Float) = RouteThumbnail(listOf(listOf(ThumbPoint(x, x))))

    // --- The library list ---

    @Test
    fun `three lengths of one route collapse to a single row`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(3, distanceMeters = 12_000.0, family = "Cuckoo Trail")),
                row(header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail")),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail")),
            )
        )

        assertEquals(1, rows.size)
        assertEquals("Cuckoo Trail", rows.single().title)
        assertEquals("3 lengths · 5.00–12.00 km", rows.single().subtitle)
        assertEquals(3, rows.single().lengthCount)
    }

    @Test
    fun `a course in no family keeps its own row exactly as before`() {
        val lone = header(1, name = "Regent's Park loop", distanceMeters = 4_215.0)

        val rows = routeLibraryRows(listOf(row(lone)))

        assertEquals("Regent's Park loop", rows.single().title)
        assertEquals(routeRowSubtitle(lone), rows.single().subtitle)
        assertNull(rows.single().family)
        assertEquals(lone, rows.single().route)
    }

    @Test
    fun `a family with only one length so far is drawn as a plain course`() {
        val only = header(1, name = "Cuckoo Trail 5k", family = "Cuckoo Trail")

        val rows = routeLibraryRows(listOf(row(only)))

        assertEquals("Cuckoo Trail 5k", rows.single().title)
        assertNull(rows.single().family)
        assertEquals(1, rows.single().lengthCount)
    }

    @Test
    fun `a family sits where its newest length sits, and the others leave no gap`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(4, name = "Beachy Head", createdAtMillis = 40)),
                row(header(3, distanceMeters = 12_000.0, family = "Cuckoo Trail", createdAtMillis = 30)),
                row(header(2, name = "Ashdown", createdAtMillis = 20)),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail", createdAtMillis = 10)),
            )
        )

        assertEquals(listOf("Beachy Head", "Cuckoo Trail", "Ashdown"), rows.map { it.title })
    }

    @Test
    fun `two families in one library are two rows`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(4, distanceMeters = 10_000.0, family = "Downs")),
                row(header(3, distanceMeters = 12_000.0, family = "Cuckoo Trail")),
                row(header(2, distanceMeters = 6_000.0, family = "Downs")),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail")),
            )
        )

        assertEquals(listOf("Downs", "Cuckoo Trail"), rows.map { it.title })
        assertEquals(listOf(2, 2), rows.map { it.lengthCount })
    }

    @Test
    fun `the family row is drawn with its longest length's shape`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(2, distanceMeters = 12_000.0, family = "Cuckoo Trail"), drawing(0.9f)),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"), drawing(0.1f)),
            )
        )

        assertEquals(drawing(0.9f), rows.single().thumbnail)
    }

    @Test
    fun `the family row opens on its shortest length`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(2, distanceMeters = 12_000.0, family = "Cuckoo Trail")),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail")),
            )
        )

        assertEquals(1L, rows.single().openRouteId)
    }

    @Test
    fun `lookalike names are not a family`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(2, name = "Cuckoo Trail 12k")),
                row(header(1, name = "Cuckoo Trail 5k")),
            )
        )

        assertEquals(2, rows.size)
    }

    /**
     * The write trims, but it is not the only way a row reaches the table — an import or a restore
     * puts one there without passing through it. Grouped by the trimmed name all the same, so such a
     * row joins its family rather than sitting ungroupable beside it.
     */
    @Test
    fun `a family name with space around it is still that family`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(2, distanceMeters = 12_000.0, family = " Cuckoo Trail ")),
                row(header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail")),
            )
        )

        assertEquals("Cuckoo Trail", rows.single().title)
        assertEquals(2, rows.single().lengthCount)
    }

    @Test
    fun `a family name of nothing but space is no family`() {
        val rows = routeLibraryRows(
            listOf(
                row(header(2, name = "Two", family = "   ")),
                row(header(1, name = "One", family = "   ")),
            )
        )

        assertEquals(listOf("Two", "One"), rows.map { it.title })
    }

    // --- The words ---

    @Test
    fun `a family whose lengths measure the same prints one distance`() {
        assertEquals("2 lengths · 5.00 km", routeFamilySubtitle(2, 5_000.0, 5_000.0))
    }

    @Test
    fun `a family whose lengths print the same prints one distance`() {
        assertEquals("2 lengths · 5.00 km", routeFamilySubtitle(2, 5_000.1, 5_000.2))
    }

    @Test
    fun `a family whose lengths print apart prints a range`() {
        assertEquals("2 lengths · 5.00–12.00 km", routeFamilySubtitle(2, 5_000.0, 12_000.0))
    }

    @Test
    fun `a chip drops a trailing nought`() {
        assertEquals("8k", routeLengthChipLabel(8_040.0))
        assertEquals("5k", routeLengthChipLabel(5_000.0))
        assertEquals("12.5k", routeLengthChipLabel(12_460.0))
    }

    @Test
    fun `a row of chips whose lengths round apart stays short`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, distanceMeters = 5_000.0),
                header(2, distanceMeters = 8_040.0),
                header(3, distanceMeters = 12_460.0),
            )
        )

        assertEquals(listOf("5k", "8k", "12.5k"), labels)
    }

    @Test
    fun `two lengths that round to the same word are told apart by the row`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, distanceMeters = 5_010.0),
                header(2, distanceMeters = 5_040.0),
            )
        )

        assertEquals(listOf("5.01k", "5.04k"), labels)
        assertEquals(labels.distinct(), labels)
    }

    @Test
    fun `the whole row grows together rather than only the pair that collided`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, distanceMeters = 5_010.0),
                header(2, distanceMeters = 5_040.0),
                header(3, distanceMeters = 12_000.0),
            )
        )

        assertEquals(listOf("5.01k", "5.04k", "12k"), labels)
    }

    @Test
    fun `three decimals separate lengths a metre apart`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, distanceMeters = 5_000.0),
                header(2, distanceMeters = 5_001.0),
            )
        )

        assertEquals(listOf("5k", "5.001k"), labels)
    }

    @Test
    fun `two lengths measuring the same fall back to their own names`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, name = "Out and back", distanceMeters = 5_000.0),
                header(2, name = "Loop", distanceMeters = 5_000.0),
            )
        )

        assertEquals(listOf("5k Out and back", "5k Loop"), labels)
    }

    @Test
    fun `two lengths matching in distance and name are told apart by their place in the row`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, name = "Park loop", distanceMeters = 5_000.2),
                header(2, name = "Park loop", distanceMeters = 5_000.2),
            )
        )

        assertEquals(listOf("5k Park loop (1)", "5k Park loop (2)"), labels)
    }

    @Test
    fun `a name that already looks numbered still gets a place of its own`() {
        val labels = routeLengthChipLabels(
            listOf(
                header(1, name = "Park loop", distanceMeters = 5_000.0),
                header(2, name = "Park loop", distanceMeters = 5_000.0),
                header(3, name = "Park loop (1)", distanceMeters = 5_000.0),
            )
        )

        assertEquals(labels.distinct(), labels)
    }

    // --- Siblings and where a page lands ---

    @Test
    fun `siblings come back shortest first`() {
        val library = listOf(
            header(3, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 12_000.0, family = "Cuckoo Trail"),
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
        )

        assertEquals(listOf(1L, 3L, 2L), routeSiblings(library, routeId = 2).map { it.id })
    }

    @Test
    fun `a course with no family is its own only sibling`() {
        val library = listOf(header(1), header(2, family = "Cuckoo Trail"))

        assertEquals(listOf(1L), routeSiblings(library, routeId = 1).map { it.id })
    }

    @Test
    fun `a deleted course has no siblings at all`() {
        assertEquals(emptyList<RouteHeader>(), routeSiblings(listOf(header(1)), routeId = 9))
    }

    @Test
    fun `a family opens on the length run most recently`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
            header(3, distanceMeters = 12_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(
            siblings,
            listOf(RouteLastRunRow(1, 1_000), RouteLastRunRow(2, 9_000)),
        )

        assertEquals(2L, landing)
    }

    @Test
    fun `a family nobody has run opens on the shortest`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        assertEquals(1L, routeFamilyLandingId(siblings, emptyList()))
    }

    @Test
    fun `a Run on a course outside the family cannot decide where the page lands`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(siblings, listOf(RouteLastRunRow(routeId = 99, 9_000)))

        assertEquals(1L, landing)
    }

    @Test
    fun `two lengths last run on the same millisecond fall to the shorter`() {
        val siblings = listOf(
            header(1, distanceMeters = 5_000.0, family = "Cuckoo Trail"),
            header(2, distanceMeters = 8_000.0, family = "Cuckoo Trail"),
        )

        val landing = routeFamilyLandingId(
            siblings,
            listOf(RouteLastRunRow(1, 5_000), RouteLastRunRow(2, 5_000)),
        )

        assertEquals(1L, landing)
    }

    @Test
    fun `a course deleted out from under the page lands nowhere`() {
        assertNull(routeFamilyLandingId(emptyList(), emptyList()))
    }

    // --- The landing counts the Runs a length only recognises (#436) ---

    /** A line long enough to hold a shape, drawn from [at] so two of them are different ground. */
    private fun shapeAt(at: Double): RunShape = routeShapeOf(
        RoutePolyline.decode(
            RoutePolyline.encode(
                listOf(
                    RoutePoint(at, -0.1, elevationMeters = null),
                    RoutePoint(at + 0.02, -0.1, elevationMeters = null),
                )
            )
        )
    )!!

    /** One Run over [shape]'s ground, as the shaped read hands it over. */
    private fun runOver(shape: RunShape, sessionId: Long, startTime: Long) = ShapedRunRow(
        run = RouteRunRow(
            sessionId = sessionId,
            startTime = startTime,
            ranAtUtcOffsetSeconds = 0,
            durationSeconds = 300,
            movingTimeSeconds = 300,
            distanceKm = shape.distanceMeters / 1_000.0,
        ),
        shape = runShapeRowOf(sessionId, shape).shape!!,
        shapeDistanceMeters = shape.distanceMeters,
    )

    @Test
    fun `a length nobody wrote down is still the length run most recently`() {
        val theFiveK = shapeAt(51.5)
        val theEightK = shapeAt(52.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = emptyList(),
            courses = listOf(
                CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK),
                CourseShape(routeId = 2, name = "Cuckoo 8k", shape = theEightK),
            ),
            shaped = listOf(runOver(theEightK, sessionId = 7, startTime = 9_000)),
        )

        assertEquals(listOf(RouteLastRunRow(2, 9_000)), lastRuns)
    }

    @Test
    fun `the later of the two histories is the one a length is judged on`() {
        val theFiveK = shapeAt(51.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = listOf(RouteLastRunRow(1, 9_000)),
            courses = listOf(CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK)),
            shaped = listOf(runOver(theFiveK, sessionId = 7, startTime = 1_000)),
        )

        assertEquals(listOf(RouteLastRunRow(1, 9_000)), lastRuns)
    }

    @Test
    fun `a run over other ground does not move the length it is not on`() {
        val theFiveK = shapeAt(51.5)
        val elsewhere = shapeAt(53.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = emptyList(),
            courses = listOf(CourseShape(routeId = 1, name = "Cuckoo 5k", shape = theFiveK)),
            shaped = listOf(runOver(elsewhere, sessionId = 7, startTime = 9_000)),
        )

        assertEquals(emptyList<RouteLastRunRow>(), lastRuns)
    }

    /** A course still owed its measurement keeps whatever was written down on it. */
    @Test
    fun `a length with no shape yet keeps the runs remembered on it`() {
        val theEightK = shapeAt(52.5)

        val lastRuns = routeFamilyLastRuns(
            remembered = listOf(RouteLastRunRow(1, 5_000)),
            courses = listOf(CourseShape(routeId = 2, name = "Cuckoo 8k", shape = theEightK)),
            shaped = listOf(runOver(theEightK, sessionId = 7, startTime = 1_000)),
        )

        assertEquals(
            setOf(RouteLastRunRow(1, 5_000), RouteLastRunRow(2, 1_000)),
            lastRuns.toSet(),
        )
    }

    /** Nothing to recognise against is the read the app made before #436, unchanged. */
    @Test
    fun `a family with no shapes at all is judged on what was written down`() {
        val remembered = listOf(RouteLastRunRow(1, 5_000))

        assertEquals(
            remembered,
            routeFamilyLastRuns(remembered, courses = emptyList(), shaped = emptyList()),
        )
    }

    // --- The names the box offers ---

    @Test
    fun `the box offers each family name once, in order`() {
        val library = listOf(
            header(1, family = "Downs"),
            header(2, family = "Cuckoo Trail"),
            header(3, family = "Downs"),
            header(4),
        )

        assertEquals(listOf("Cuckoo Trail", "Downs"), routeFamilyNames(library))
    }
}
