package com.example.runningapp.export

import com.garmin.fit.ActivityMesg
import com.garmin.fit.Decode
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.Factory
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FIT writer, held to two things (#218): the exact bytes it produces, and the fact that the FIT
 * SDK's own decoder reads them back and finds the numbers the run was stated to have.
 *
 * Both, not either. The golden file catches a change nobody meant to make; the round trip catches a
 * file that is different *and wrong*, which a golden file on its own would happily bless.
 */
class FitWriterTest {

    /** 2025-07-26T03:20:00Z, the same fixed moment the GPX goldens are written at. */
    private val startMillis = 1_753_500_000_000L

    @Test
    fun `a scripted run encodes to the golden file`() {
        assertArrayEquals(golden("scripted-run.fit"), FitWriter.write(scriptedRun()))
    }

    @Test
    fun `the SDK's own decoder reads the file back`() {
        val bytes = FitWriter.write(scriptedRun())

        assertTrue("the file is not FIT", Decode().isFileFit(ByteArrayInputStream(bytes)))
        assertTrue("the CRCs do not check out", Decode().checkFileIntegrity(ByteArrayInputStream(bytes)))
        // No exception thrown is the assertion: Decode throws FitRuntimeException on a malformed
        // definition, an unknown field size or a broken CRC.
        assertTrue(decode(bytes).isNotEmpty())
    }

    @Test
    fun `the file says it is one activity, made by this app, stamped when the run started`() {
        val messages = decode(FitWriter.write(scriptedRun()))

        val fileId = messages.filterIsInstance<FileIdMesg>().single()
        assertEquals(com.garmin.fit.File.ACTIVITY, fileId.type)
        assertEquals(com.garmin.fit.Manufacturer.DEVELOPMENT, fileId.manufacturer)
        assertEquals(startMillis / 1000, fileId.timeCreated.date.time / 1000)

        val activity = messages.filterIsInstance<ActivityMesg>().single()
        assertEquals(1, activity.numSessions)
    }

    @Test
    fun `the session carries the app's own distance and moving time, not a re-derivation`() {
        val run = scriptedRun()
        val session = decode(FitWriter.write(run)).filterIsInstance<SessionMesg>().single()

        assertEquals(Sport.RUNNING, session.sport)
        // GENERIC, not ROAD: the app records no surface, so a road is a claim it cannot make.
        assertEquals(SubSport.GENERIC, session.subSport)
        // The whole point of the export: 600 s of Duration, 540 s moving, and the app's own 2400 m —
        // none of which a reader would arrive at from the four fixes below. Garmin shows
        // `total_timer_time` as an activity's and a lap's "Time", so the Moving time goes there.
        assertEquals(600.0f, session.totalElapsedTime, 0.001f)
        assertEquals(540.0f, session.totalTimerTime, 0.001f)
        assertEquals(540.0f, session.totalMovingTime, 0.001f)
        assertEquals(2400.0f, session.totalDistance, 0.01f)
        assertEquals(2400.0f / 540.0f, session.avgSpeed!!, 0.001f)
        assertEquals(142, session.avgHeartRate!!.toInt())
        assertEquals(171, session.maxHeartRate!!.toInt())
        assertEquals(2, session.numLaps)
        assertEquals(18, session.totalAscent!!.toInt())
    }

    @Test
    fun `the laps are the app's own splits, in order, each on its own clock`() {
        val laps = decode(FitWriter.write(scriptedRun())).filterIsInstance<LapMesg>()

        assertEquals(2, laps.size)
        assertEquals(listOf(0, 1), laps.map { it.messageIndex })
        assertEquals(listOf(1000.0f, 1400.0f), laps.map { it.totalDistance })
        // A lap's wall clock holds its rest; its timer time is the moving time the app quotes the
        // split's pace against, which is the number Garmin prints in the lap list's Time column.
        assertEquals(300.0f, laps[0].totalElapsedTime, 0.001f)
        assertEquals(240.0f, laps[0].totalTimerTime, 0.001f)
        assertEquals(240.0f, laps[0].totalMovingTime, 0.001f)
        // A kilometre ends the first; the run itself ends the last.
        assertEquals(LapTrigger.DISTANCE, laps[0].lapTrigger)
        assertEquals(LapTrigger.SESSION_END, laps[1].lapTrigger)
        // Laid end to end with nothing falling between them.
        assertEquals(laps[0].timestamp.timestamp, laps[1].startTime.timestamp)
    }

    @Test
    fun `the records carry position, height and heart rate against the wall clock`() {
        val records = decode(FitWriter.write(scriptedRun())).filterIsInstance<RecordMesg>()

        assertEquals(4, records.size)
        assertEquals(startMillis / 1000, records.first().timestamp.date.time / 1000)
        assertEquals(120, records.first().heartRate!!.toInt())
        // The format's own grid, not a rounding this code chose: FIT stores a height as fifths of a
        // metre above a 500 m floor, so 12.3 m is written down and read back as 12.4 m.
        assertEquals(12.4f, records.first().enhancedAltitude!!, 0.001f)
        // Semicircles: 51.5074 degrees of the 2^31/180 the format counts them in, rounded to the
        // nearest rather than truncated — a truncation would lose up to 1.9 cm on every fix, always
        // towards the equator.
        assertEquals(614507218, records.first().positionLat)
        assertEquals(-1524713, records.first().positionLong)
        // No distance on a record: it would be a second claim about how far the Run went.
        records.forEach { assertNull(it.distance) }
    }

    @Test
    fun `the timer is started before the first record and stopped after the last`() {
        val messages = decode(FitWriter.write(scriptedRun()))
        val events = messages.filterIsInstance<EventMesg>()

        assertEquals(listOf(EventType.START, EventType.STOP_ALL), events.map { it.eventType })
        assertTrue(messages.indexOf(events.first()) < messages.indexOfFirst { it is RecordMesg })
        assertTrue(messages.indexOf(events.last()) > messages.indexOfLast { it is RecordMesg })
    }

    @Test
    fun `a lap states the split's own time, because that is the number Garmin prints`() {
        // Measured against Garmin Connect on 2026-08-17 with the Aug 16 Run: Garmin reads
        // `total_timer_time` as the "Time" of the activity and of every lap, and recomputes moving
        // time itself whatever `total_moving_time` says. So the app's split times survive the trip
        // only by being the timer time — see [FitActivity].
        val laps = decode(FitWriter.write(pausedRun())).filterIsInstance<LapMesg>()
        val session = decode(FitWriter.write(pausedRun())).filterIsInstance<SessionMesg>().single()

        assertEquals(pausedRun().laps.map { it.movingMillis / 1000.0f }, laps.map { it.totalTimerTime })
        assertEquals(session.totalTimerTime, laps.map { it.totalTimerTime }.sum(), 0.001f)
        // And the Run's Duration is what Garmin labels Elapsed Time.
        assertEquals(600.0f, session.totalElapsedTime, 0.001f)
    }

    @Test
    fun `a Pause stops the timer where it happened and starts it again on the resume`() {
        // Without these a reader has only the run's two totals to tell it a Pause happened at all,
        // and joins the fix before it to the fix after as ground covered in time that counted.
        val paused = pausedRun()

        val messages = decode(FitWriter.write(paused))
        val events = messages.filterIsInstance<EventMesg>()

        assertEquals(
            listOf(EventType.START, EventType.STOP, EventType.START, EventType.STOP_ALL),
            events.map { it.eventType },
        )
        assertEquals((startMillis + 240_000) / 1000, events[1].timestamp.date.time / 1000)
        assertEquals((startMillis + 300_000) / 1000, events[2].timestamp.date.time / 1000)
    }

    @Test
    fun `a Pause leaves the file in one time order`() {
        // The stop is stamped on the last fix before the Pause, which is already written by then: a
        // merge that appended the events instead would stamp it before records that precede it.
        val paused = pausedRun()

        val stamps = decode(FitWriter.write(paused)).mapNotNull { message ->
            when (message) {
                is EventMesg -> message.timestamp.timestamp
                is RecordMesg -> message.timestamp.timestamp
                else -> null
            }
        }

        assertEquals(stamps.sorted(), stamps)
        // The resume's own fix is inside the running timer, not outside it.
        val messages = decode(FitWriter.write(paused))
        val resumed = messages.filterIsInstance<EventMesg>()[2]
        val resumeFix = messages.filterIsInstance<RecordMesg>()
            .first { it.timestamp.date.time / 1000 == (startMillis + 300_000) / 1000 }
        assertTrue(messages.indexOf(resumed) < messages.indexOf(resumeFix))
    }

    @Test
    fun `a run with no Pauses is written exactly as it was before they could be stated`() {
        // The golden file is a pause-free run, so the merge must not reorder anything on one.
        assertArrayEquals(FitWriter.write(scriptedRun()), FitWriter.write(scriptedRun().copy(pauses = emptyList())))
    }

    // -- The run FIT can carry and GPX cannot ---------------------------------------------------

    @Test
    fun `a run with no GPS at all keeps its heart-rate trace`() {
        val treadmill = FitActivity(
            startTimeMillis = startMillis,
            endTimeMillis = startMillis + 300_000,
            elapsedMillis = 300_000,
            movingMillis = 300_000,
            distanceMeters = 1500.0,
            sport = FitSport.TREADMILL_RUN,
            records = (0..4).map { FitRecord(timeMillis = startMillis + it * 60_000L, heartRateBpm = 130 + it) },
            laps = listOf(
                FitLap(
                    startTimeMillis = startMillis,
                    endTimeMillis = startMillis + 300_000,
                    movingMillis = 300_000,
                    distanceMeters = 1500.0,
                )
            ),
            averageBpm = 132,
            maxBpm = 134,
        )

        val messages = decode(FitWriter.write(treadmill))
        val records = messages.filterIsInstance<RecordMesg>()

        assertEquals(5, records.size)
        assertEquals(listOf(130, 131, 132, 133, 134), records.map { it.heartRate!!.toInt() })
        // A trackpoint without a position is not a legal GPX one; a FIT record without one is fine.
        records.forEach {
            assertNull(it.positionLat)
            assertNull(it.positionLong)
        }
        val session = messages.filterIsInstance<SessionMesg>().single()
        assertEquals(SubSport.TREADMILL, session.subSport)
        assertNull(session.startPositionLat)
    }

    @Test
    fun `a Run the runner called a walk is written as a walk`() {
        val session = decode(FitWriter.write(scriptedRun(sport = FitSport.WALK)))
            .filterIsInstance<SessionMesg>()
            .single()

        assertEquals(Sport.WALKING, session.sport)
    }

    @Test
    fun `a run that never moved states no speed rather than an infinity`() {
        val stood = scriptedRun().copy(
            movingMillis = 0L,
            laps = scriptedRun().laps.map { it.copy(movingMillis = 0L) },
        )

        val messages = decode(FitWriter.write(stood))

        assertNull(messages.filterIsInstance<SessionMesg>().single().avgSpeed)
        messages.filterIsInstance<LapMesg>().forEach { assertNull(it.avgSpeed) }
    }

    // -- The run every test above is written against ---------------------------------------------

    /**
     * Four fixes, two laps and a pause: 2400 m covered over ten minutes of wall clock of which nine
     * were moving. Deliberately too sparse for a reader to arrive at those numbers on its own, so
     * every assertion about the summary is an assertion that the file *stated* it.
     */
    /**
     * The same run with a 60 s Pause in it, and a clock that accounts for it: 11 minutes on the wall,
     * 10 of them on the timer, 9 of them moving. A Pause bolted onto [scriptedRun] without stretching
     * its wall clock would be a run whose own three numbers disagree, which is not a run this export
     * can be asked about.
     */
    private fun pausedRun(): FitActivity {
        val scripted = scriptedRun()
        return scripted.copy(
            endTimeMillis = startMillis + 660_000,
            pauses = listOf(
                FitPause(startTimeMillis = startMillis + 240_000, endTimeMillis = startMillis + 300_000),
            ),
            laps = listOf(
                // The Pause falls inside the first lap, so that lap's timer is 60 s short of its wall clock.
                scripted.laps[0].copy(endTimeMillis = startMillis + 360_000),
                scripted.laps[1].copy(
                    startTimeMillis = startMillis + 360_000,
                    endTimeMillis = startMillis + 660_000,
                ),
            ),
        )
    }

    private fun scriptedRun(sport: FitSport = FitSport.RUN) = FitActivity(
        startTimeMillis = startMillis,
        endTimeMillis = startMillis + 600_000,
        elapsedMillis = 600_000,
        movingMillis = 540_000,
        distanceMeters = 2400.0,
        sport = sport,
        records = listOf(
            FitRecord(startMillis, 51.5074, -0.1278, 12.3, 120),
            FitRecord(startMillis + 240_000, 51.5164, -0.1278, 18.0, 155),
            FitRecord(startMillis + 300_000, 51.5164, -0.1278, 18.0, 138),
            FitRecord(startMillis + 600_000, 51.5290, -0.1278, 30.5, 171),
        ),
        laps = listOf(
            FitLap(
                startTimeMillis = startMillis,
                endTimeMillis = startMillis + 300_000,
                movingMillis = 240_000,
                distanceMeters = 1000.0,
                averageBpm = 137,
                ascentMeters = 5.7,
            ),
            FitLap(
                startTimeMillis = startMillis + 300_000,
                endTimeMillis = startMillis + 600_000,
                movingMillis = 300_000,
                distanceMeters = 1400.0,
                averageBpm = 154,
                ascentMeters = 12.5,
            ),
        ),
        averageBpm = 142,
        maxBpm = 171,
        ascentMeters = 18.2,
    )

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fit/$name")) { "Missing golden file fit/$name" }
            .use { it.readBytes() }

    /**
     * Every message in the file, in the order it was written, as the typed message it is.
     *
     * [Factory.createMesg] is what turns the decoder's generic message into a [RecordMesg] or a
     * [SessionMesg], which is what lets a test ask for a field by name instead of by number.
     */
    private fun decode(bytes: ByteArray): List<Mesg> {
        val messages = mutableListOf<Mesg>()
        Decode().read(ByteArrayInputStream(bytes), MesgListener { messages += Factory.createMesg(it) })
        return messages
    }
}
