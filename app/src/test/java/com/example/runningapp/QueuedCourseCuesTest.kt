package com.example.runningapp

import com.example.runningapp.routes.CourseAlert
import com.example.runningapp.routes.TurnCue
import com.example.runningapp.routes.TurnCueMoment
import com.example.runningapp.routes.TurnDirection
import com.example.runningapp.run.CueTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The course's voice as the service wires it to the real cue bookkeeping (#479).
 *
 * The queue is a stand-in that says nothing, so every cue enqueued is a cue still waiting. The
 * bookkeeping in front of it is the real [OutstandingCues], shared with the rest of the Run's cues
 * the way the service shares it — which is what lets a withdrawal here be checked against a cue that
 * is not the course's.
 */
class QueuedCourseCuesTest {

    /** A stand-in for the service's hold on the queue: nothing is ever said, everything waits. */
    private class SilentQueue : CueHold {
        val waiting = LinkedHashMap<Long, Pair<String, CuePriority>>()
        private var lastTicket = 0L

        override fun enqueue(text: String, priority: CuePriority): Long {
            val ticket = ++lastTicket
            waiting[ticket] = text to priority
            return ticket
        }

        override fun withdrawAll(tickets: Collection<Long>) = tickets.forEach { waiting.remove(it) }

        fun texts(): List<String> = waiting.values.map { it.first }
    }

    private val queue = SilentQueue()
    private var hold: CueHold? = queue
    private val outstanding = OutstandingCues()
    private val runCues = RunCueQueue(outstanding) { hold }
    private val course = QueuedCourseCues(runCues)

    private val turnRight = TurnCue(TurnDirection.RIGHT, TurnCueMoment.AT_THE_TURN)

    /** A cue of the Run's own, enqueued the way the service enqueues one. */
    private fun theRunSays(text: String, tag: CueTag? = null) =
        runCues.enqueue(text, CuePriority.INFORMATION, tag)

    /** The top of the queue: a runner going the wrong way cannot wait for a split to finish. */
    @Test
    fun `a course cue goes into the queue at navigation priority`() {
        course.enqueue(CourseAlert.OFF_COURSE)

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken to CuePriority.NAVIGATION), queue.waiting.values.toList())
    }

    /** The line going takes back what was said about the line, and nothing the Run said. */
    @Test
    fun `taking back every course cue leaves the Run's own cues waiting`() {
        theRunSays("Halfway. Turn around.", CueTag.TURNAROUND)
        course.enqueue(CourseAlert.OFF_COURSE)
        theRunSays("Kilometre 3.")
        course.enqueue(turnRight)

        course.takeBackEveryCourseCue()

        assertEquals(listOf("Halfway. Turn around.", "Kilometre 3."), queue.texts())
    }

    /** By ticket is exactly those tickets: the course cue beside it is still true. */
    @Test
    fun `taking back one course cue by its ticket leaves the other waiting`() {
        course.enqueue(CourseAlert.OFF_COURSE)
        val ticket = course.enqueue(turnRight)!!

        course.takeBack(listOf(ticket))

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.texts())
    }

    /**
     * A ticket taken back is off the books as well as out of the queue, so a later wholesale
     * withdrawal does not hand it to the queue a second time.
     */
    @Test
    fun `a course cue taken back by ticket is not taken back again by name`() {
        val ticket = course.enqueue(turnRight)!!
        course.takeBack(listOf(ticket))

        assertEquals(emptyList<Long>(), outstanding.takeBack(CueTag.COURSE))
    }

    /** The end of the Run takes back everything the Run left, the course's cues among it (#220). */
    @Test
    fun `the end of the Run takes back a course cue still waiting`() {
        course.enqueue(CourseAlert.OFF_COURSE)
        theRunSays("Kilometre 3.")

        runCues.takeBackAll()

        assertEquals(emptyList<String>(), queue.texts())
    }

    /** No hold on the queue — before the Run has one, or after it let go — is no cue and no ticket. */
    @Test
    fun `with no hold on the queue nothing is enqueued`() {
        hold = null

        assertNull(course.enqueue(CourseAlert.OFF_COURSE))
        course.takeBackEveryCourseCue()
        course.takeBack(listOf(1L))
        runCues.takeBackAll()

        assertEquals(emptyList<Long>(), outstanding.takeBack(CueTag.COURSE))
    }
}
