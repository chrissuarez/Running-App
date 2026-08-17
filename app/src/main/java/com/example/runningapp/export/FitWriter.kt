package com.example.runningapp.export

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionTrigger
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One moment of a run, as a FIT `record` message holds it (#218).
 *
 * Everything but the time is optional, and that is the point of the format: a treadmill run with a
 * heart-rate strap and nothing else is a legal FIT file, where a GPX trackpoint without a latitude
 * and longitude is not one.
 *
 * A record states no distance. FIT has a field for one, and filling it would be a second claim about
 * how far the Run went, measured differently from the summary's — see [RunFitActivity].
 */
data class FitRecord(
    val timeMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val heartRateBpm: Int? = null,
)

/**
 * One lap of a run — a kilometre of it, as the app's own splits table cut it (#45).
 *
 * Both clocks are carried. [endTimeMillis] less [startTimeMillis] is the wall clock, which a pause
 * or a rest sits inside; [movingMillis] is the clock the app quotes the lap's pace against, which
 * they sit outside. A reader that has both shows the app's own pace rather than one it worked out
 * for itself.
 */
data class FitLap(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val movingMillis: Long,
    val distanceMeters: Double,
    val averageBpm: Int? = null,
    val ascentMeters: Double? = null,
)

/**
 * A Pause inside a run: the stretch between the last fix before it and the fix that resumed.
 *
 * Only a Pause belongs here, never an Outage. A Pause stopped the Run's clock and covered no ground,
 * so the timer stopped with it; an Outage is a leg the Run counted — its seconds are Moving time and
 * its straight line is distance ([ADR 0012](docs/adr/0012-an-outage-is-a-leg-like-any-other.md)) —
 * so a timer that stopped for one would contradict the Moving time stated a few lines below it.
 *
 * This is why the rule here is not [RunGpxTrack]'s, which breaks its route at both kinds. That asks
 * a different question — where a reader must draw no line — and both kinds of Break answer it.
 */
data class FitPause(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
)

/** What the app calls this run, in the terms FIT has for it. */
enum class FitSport {
    /** An outdoor run. */
    RUN,

    /** A run on a treadmill: the same sport, on ground that is not going anywhere. */
    TREADMILL_RUN,

    /** A Run the runner said was a walk (#275). */
    WALK,
}

/**
 * A finished run, ready to be encoded (#218).
 *
 * The summary is stated rather than left to be re-derived: [distanceMeters], [elapsedMillis],
 * [movingMillis] and the heart rates are the numbers the run's own page shows, and writing them into
 * the `session` message is the whole reason this export exists. Garmin recomputes distance and time
 * from the raw fixes when it is given a GPX file, so its numbers and the app's disagree — including
 * the app's rest window, which Garmin knows nothing about.
 *
 * **The clocks.** The app keeps two — the run's own Duration, and the Moving time inside it — and FIT
 * has three, which are a question each rather than three names for one number:
 *
 *  - `total_elapsed_time` — the wall clock, start to finish, Pauses and all. The app shows no such
 *    number, and it is the one FIT's own timer events already state by where they fall, so it is
 *    written as measured: [endTimeMillis] less [startTimeMillis].
 *  - `total_timer_time` — the time the timer was running, which is the wall clock less the Pauses
 *    that stopped it. That is exactly the Run's Duration ([elapsedMillis]).
 *  - `total_moving_time` — the clock a pace is quoted against ([movingMillis]), which a rest window
 *    sits outside as well as a Pause.
 *
 * So both of the app's numbers survive the trip — its Duration as the timer, its Moving time as the
 * moving clock — and the file agrees with itself: the stretches its timer events leave running add up
 * to the timer time it states. Writing the Moving time as the timer time instead, as this once did,
 * left a file whose own events said the timer had run for longer than its summary claimed, and a
 * reader that checks is entitled to believe the events.
 *
 * The laps answer the same three questions over their own windows, so they add up: their wall clocks
 * total the session's elapsed time, their timer times its timer time, and their moving times its
 * moving time.
 */
data class FitActivity(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val elapsedMillis: Long,
    val movingMillis: Long,
    val distanceMeters: Double,
    val sport: FitSport,
    val records: List<FitRecord>,
    val laps: List<FitLap>,
    /** The Run's Pauses, so the file states where its clock stopped and not only for how long. */
    val pauses: List<FitPause> = emptyList(),
    val averageBpm: Int? = null,
    val maxBpm: Int? = null,
    val ascentMeters: Double? = null,
)

/**
 * Writes a run as a Garmin FIT activity file (#218).
 *
 * Pure and Android-free, the same shape as [GpxWriter]: a run in, bytes out, pinned by a golden
 * file. The Android side only writes those bytes somewhere.
 *
 * The encoding itself is the Garmin FIT SDK's ([BufferEncoder]) rather than hand-rolled — the CRCs,
 * the definition-message layout and the per-field scaling are the format, and the SDK's licence asks
 * that the protocol be followed exactly. What is decided here is only *which* messages a run
 * becomes, and in what order.
 */
object FitWriter {

    const val FILE_EXTENSION = "fit"

    /** The type Garmin Connect and every other FIT reader registers for. */
    const val MIME_TYPE = "application/vnd.ant.fit"

    /**
     * Degrees to the semicircles FIT stores a position in: the whole circle over 2^32, so a degree
     * is 2^31/180 of them.
     *
     * Exactly 180° lands on 2^31, which does not fit a signed 32-bit integer and wraps to -2^31 —
     * which is the antimeridian written the other way round and is the same place. That wrap is the
     * format working as intended, not an overflow to guard against; GPX has to be told about the
     * same meridian by hand ([GpxWriter]) because a decimal string has no such symmetry.
     */
    private const val SEMICIRCLES_PER_DEGREE = 2147483648.0 / 180.0

    /**
     * Says who made the file. `DEVELOPMENT` is the manufacturer id Garmin reserves for software that
     * is not one of their products, which is what this is — claiming a real manufacturer id would be
     * a lie about the file's provenance and is what §2a of the FIT licence forbids.
     */
    private const val PRODUCT = 1

    /**
     * A fixed serial number rather than a random or clock-derived one, so the same run encodes to the
     * same bytes every time. Garmin Connect uses the trio of manufacturer, product and serial number
     * to tell one recording device from another; every run this app exports did come from the same
     * one, so one number is the truthful answer as well as the testable one.
     */
    private const val SERIAL_NUMBER = 1L

    fun write(activity: FitActivity): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)

        // The order is the Activity file's, and readers depend on it: what the file is, then the
        // moments, then the laps over them, then the summary of the laps, then the activity holding
        // the session. A summary written before the records it summarises is a file a reader has to
        // buffer the whole of before it can believe anything.
        encoder.write(fileId(activity))
        timeline(activity).forEach { encoder.write(it) }
        activity.laps.forEachIndexed { index, lap -> encoder.write(lap(lap, index, activity)) }
        encoder.write(session(activity))
        encoder.write(activity(activity))

        return encoder.close()
    }

    private fun fileId(activity: FitActivity) = FileIdMesg().apply {
        type = File.ACTIVITY
        manufacturer = Manufacturer.DEVELOPMENT
        product = PRODUCT
        serialNumber = SERIAL_NUMBER
        // The moment the run started, not the moment it was exported: a file stamped with the export
        // time would be a different file every time the same run was shared, and Garmin Connect
        // reads this stamp when it decides whether it has seen an activity before.
        timeCreated = fitTime(activity.startTimeMillis)
    }

    /**
     * Every moment of the run in one time order: its records, and the timer starting and stopping
     * around them.
     *
     * The two streams are merged rather than written one after the other because a Pause has records
     * inside it — the strap keeps reporting while the runner stands still — and a `stop` stamped
     * before records that are already written is a file out of time order, which a reader is
     * entitled to distrust.
     *
     * Where a moment and an event share a timestamp the order is settled by [rank]: a `start` opens
     * the second it names, a `stop` closes it. So the fix that resumed a run is inside the running
     * timer, and the last fix before a Pause is inside the timer that was still running when it
     * arrived.
     */
    private fun timeline(activity: FitActivity): List<Mesg> {
        val moments = mutableListOf<Moment>()
        moments += Moment(activity.startTimeMillis, STARTS, timerEvent(activity.startTimeMillis, EventType.START))
        activity.pauses.forEach { pause ->
            moments += Moment(pause.startTimeMillis, STOPS, timerEvent(pause.startTimeMillis, EventType.STOP))
            moments += Moment(pause.endTimeMillis, STARTS, timerEvent(pause.endTimeMillis, EventType.START))
        }
        moments += Moment(activity.endTimeMillis, STOPS, timerEvent(activity.endTimeMillis, EventType.STOP_ALL))
        activity.records.forEach { moments += Moment(it.timeMillis, RECORDS, record(it)) }
        return moments.sortedWith(compareBy({ it.atMillis }, { it.rank })).map { it.message }
    }

    /** One thing the file says at one moment, and what settles its place among the rest — see [timeline]. */
    private class Moment(val atMillis: Long, val rank: Int, val message: Mesg)

    private const val STARTS = 0
    private const val RECORDS = 1
    private const val STOPS = 2

    /**
     * The timer starting and stopping. A FIT activity is required to bracket its records with these,
     * and a reader that sees none treats the whole file as one unstarted recording.
     */
    private fun timerEvent(atMillis: Long, type: EventType) = EventMesg().apply {
        timestamp = fitTime(atMillis)
        event = Event.TIMER
        eventType = type
    }

    private fun record(point: FitRecord) = RecordMesg().apply {
        timestamp = fitTime(point.timeMillis)
        point.latitude?.let { positionLat = semicircles(it) }
        point.longitude?.let { positionLong = semicircles(it) }
        point.altitudeMeters?.let { enhancedAltitude = it.toFloat() }
        point.heartRateBpm?.let { heartRate = it.toShort() }
    }

    private fun lap(lap: FitLap, index: Int, activity: FitActivity) = LapMesg().apply {
        messageIndex = index
        // A lap is stamped with the moment it ended, which is the FIT convention for every message
        // that describes a stretch rather than an instant.
        timestamp = fitTime(lap.endTimeMillis)
        startTime = fitTime(lap.startTimeMillis)
        event = Event.LAP
        eventType = EventType.STOP
        sport = activity.sport.fitSport()
        // The last lap of a run ends because the run did; the ones before it end on a kilometre.
        lapTrigger = if (index == activity.laps.lastIndex) LapTrigger.SESSION_END else LapTrigger.DISTANCE
        val wallClockMillis = lap.endTimeMillis - lap.startTimeMillis
        totalElapsedTime = seconds(wallClockMillis)
        // The lap's share of the Run's own Duration: its wall clock, less the Pauses inside it. The
        // laps then add up to the session's timer time, as the timer events say they must.
        totalTimerTime = seconds(wallClockMillis - pausedMillisWithin(lap, activity.pauses))
        totalDistance = lap.distanceMeters.toFloat()
        // Stated, not left to be worked out from the two above: this is the pace the app's own
        // splits table shows, and a reader dividing distance by the wrong clock would print a
        // different one beside the same lap.
        totalMovingTime = seconds(lap.movingMillis)
        averageSpeed(lap.distanceMeters, lap.movingMillis)?.let { avgSpeed = it }
        lap.averageBpm?.let { avgHeartRate = it.toShort() }
        // No max heart rate per lap: a Split does not measure one, and the only honest way to state
        // it here would be to walk the samples a second time to a different rule from the average
        // beside it. An absent field is a reader falling back to its own arithmetic; a derived one
        // would be a second measurement wearing the first one's clothes.
        lap.ascentMeters?.let { totalAscent = it.roundToInt() }
    }

    /**
     * How much of [lap] a Pause held, so the lap can state the share of the Run's Duration it covers.
     *
     * Clipped to the lap rather than counted whole: a Pause that straddles a kilometre boundary
     * belongs to both laps, each for the part of it that fell inside.
     */
    private fun pausedMillisWithin(lap: FitLap, pauses: List<FitPause>): Long = pauses.sumOf { pause ->
        val from = maxOf(pause.startTimeMillis, lap.startTimeMillis)
        val to = minOf(pause.endTimeMillis, lap.endTimeMillis)
        maxOf(0L, to - from)
    }

    private fun session(activity: FitActivity) = SessionMesg().apply {
        messageIndex = 0
        timestamp = fitTime(activity.endTimeMillis)
        startTime = fitTime(activity.startTimeMillis)
        event = Event.SESSION
        eventType = EventType.STOP
        sport = activity.sport.fitSport()
        subSport = activity.sport.fitSubSport()
        trigger = SessionTrigger.ACTIVITY_END
        firstLapIndex = 0
        numLaps = activity.laps.size
        // FIT's three clocks, each given the number that actually answers it — see [FitActivity].
        totalElapsedTime = seconds(activity.endTimeMillis - activity.startTimeMillis)
        totalTimerTime = seconds(activity.elapsedMillis)
        totalDistance = activity.distanceMeters.toFloat()
        totalMovingTime = seconds(activity.movingMillis)
        averageSpeed(activity.distanceMeters, activity.movingMillis)?.let { avgSpeed = it }
        activity.averageBpm?.let { avgHeartRate = it.toShort() }
        activity.maxBpm?.let { maxHeartRate = it.toShort() }
        activity.ascentMeters?.let { totalAscent = it.roundToInt() }
        activity.records.firstOrNull { it.latitude != null && it.longitude != null }?.let { first ->
            startPositionLat = semicircles(first.latitude!!)
            startPositionLong = semicircles(first.longitude!!)
        }
    }

    private fun activity(activity: FitActivity) = ActivityMesg().apply {
        timestamp = fitTime(activity.endTimeMillis)
        totalTimerTime = seconds(activity.elapsedMillis)
        numSessions = 1
        // Manual: the runner started and stopped this recording themselves. The alternative says the
        // watch decided where one sport ended and the next began, which never happened here.
        type = Activity.MANUAL
        event = Event.ACTIVITY
        eventType = EventType.STOP
    }

    private fun FitSport.fitSport(): Sport = when (this) {
        FitSport.RUN, FitSport.TREADMILL_RUN -> Sport.RUNNING
        FitSport.WALK -> Sport.WALKING
    }

    /**
     * What the app knows about the ground a Run was run on, which is only ever whether it moved.
     *
     * An outdoor Run is `GENERIC`, not `ROAD`. The app records no surface: a trail, a track and a
     * street are one Run Mode to it, and `ROAD` would be a claim off no page — the thing an omitted
     * field avoids and a filled-in one cannot
     * ([ADR 0017](docs/adr/0017-an-export-states-the-run-it-does-not-imply-it.md)). `TREADMILL` is
     * different: the runner told the app that, so the file may say it too.
     */
    private fun FitSport.fitSubSport(): SubSport = when (this) {
        FitSport.RUN -> SubSport.GENERIC
        FitSport.TREADMILL_RUN -> SubSport.TREADMILL
        FitSport.WALK -> SubSport.GENERIC
    }

    /**
     * Metres per second over the moving clock — the same clock the pace on the run's own page is
     * quoted against, so a reader showing speed and a runner reading pace see one run.
     *
     * Null where there is no moving time to have covered the ground in, rather than a division by
     * zero written into the file as an infinity.
     */
    private fun averageSpeed(distanceMeters: Double, movingMillis: Long): Float? =
        if (movingMillis <= 0L) null else (distanceMeters / (movingMillis / 1000.0)).toFloat()

    private fun seconds(millis: Long): Float = millis / 1000.0f

    /**
     * The SDK's own conversion from a wall-clock moment, rather than this code subtracting the FIT
     * epoch itself. FIT counts seconds from 1989-12-31 UTC and the licence asks that the protocol be
     * followed exactly; the SDK is where that constant lives.
     */
    private fun fitTime(atMillis: Long): DateTime = DateTime(java.util.Date(atMillis))

    private fun semicircles(degrees: Double): Int = (degrees * SEMICIRCLES_PER_DEGREE).roundToLong().toInt()
}
