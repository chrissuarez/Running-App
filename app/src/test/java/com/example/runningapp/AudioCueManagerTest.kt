package com.example.runningapp

import android.media.AudioManager
import com.example.runningapp.routes.CourseAlert
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The cue queue: what order cues are spoken in, that none of them are lost, and what the queue does
 * with audio focus (#53). Plus the safety timeout it is built on (#213).
 *
 * The speech engine is a stand-in that says nothing back unless the test makes it — every cue waits
 * for its `onDone` here, exactly as a slow sentence does on the phone — and the timeout is a few
 * milliseconds long on a virtual clock, so the slow-cue case is reachable without anyone waiting
 * eight seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioCueManagerTest {

    private val tts: TextToSpeech = mock()
    private val audioManager: AudioManager = mock()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val reports = mutableListOf<Pair<Boolean, Long>>()

    private lateinit var manager: AudioCueManager
    private lateinit var listener: UtteranceProgressListener

    /** The grace period the service's teardown gives the last sentence, held rather than run. */
    private var shutdownBackstop: (() -> Unit)? = null

    @Before
    fun setUp() {
        whenever(audioManager.requestAudioFocus(anyOrNull(), any(), any()))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        whenever(tts.speak(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(TextToSpeech.SUCCESS)
        manager = AudioCueManager(
            tts = tts,
            audioManager = audioManager,
            serviceScope = scope,
            logTag = "test",
            cueFocusTimeoutMs = TIMEOUT_MS,
            scheduleShutdownBackstop = { _, action -> shutdownBackstop = action },
            onCueActivity = { speaking, sequence -> reports += speaking to sequence },
        )
        manager.initialize()
        listener = argumentCaptor<UtteranceProgressListener>().run {
            verify(tts).setOnUtteranceProgressListener(capture())
            firstValue
        }
    }

    /** Let [millis] of the virtual clock go by and run whatever that came due. */
    private fun elapse(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    /**
     * Everything handed to the engine so far, in order, as text and the id it will report back
     * under. The disposition is asserted here rather than in a test of its own: the queue only ever
     * speaks into an idle engine, so nothing it says may flush (#209).
     */
    private fun handedToEngine(): List<Pair<String, String>> {
        val text = argumentCaptor<CharSequence>()
        val utteranceId = argumentCaptor<String>()
        verify(tts, atLeast(0))
            .speak(text.capture(), eq(TextToSpeech.QUEUE_ADD), anyOrNull(), utteranceId.capture())
        return text.allValues.map { it.toString() }.zip(utteranceId.allValues)
    }

    private fun spokenTexts(): List<String> = handedToEngine().map { it.first }

    /** The engine reporting the cue it is on as finished, the way it does when a sentence ends. */
    private fun finishCurrent() {
        listener.onDone(handedToEngine().last().second)
    }

    @Test
    fun `cues enqueued while one is speaking are spoken in full, in order`() {
        manager.enqueue("first", CuePriority.INSTRUCTION)
        manager.enqueue("second", CuePriority.INSTRUCTION)
        manager.enqueue("third", CuePriority.INSTRUCTION)

        // Only the first has been handed over: the other two are waiting, not racing it.
        assertEquals(listOf("first"), spokenTexts())

        finishCurrent()
        assertEquals(listOf("first", "second"), spokenTexts())

        finishCurrent()
        assertEquals(listOf("first", "second", "third"), spokenTexts())
    }

    @Test
    fun `a higher-priority cue enqueued behind lower ones is spoken first`() {
        manager.enqueue("speaking now", CuePriority.INFORMATION)
        manager.enqueue("a split", CuePriority.INFORMATION)
        manager.enqueue("ease off", CuePriority.COACHING)
        manager.enqueue("start running", CuePriority.INSTRUCTION)

        // The one in flight is not cut off, and the two that jumped it go in priority order.
        assertEquals(listOf("speaking now"), spokenTexts())
        finishCurrent()
        assertEquals(listOf("speaking now", "start running"), spokenTexts())
        finishCurrent()
        finishCurrent()
        assertEquals(listOf("speaking now", "start running", "ease off", "a split"), spokenTexts())
    }

    @Test
    fun `a course alert goes ahead of everything waiting and cuts nothing off`() {
        manager.enqueue("split two kilometers", CuePriority.INFORMATION)
        manager.enqueue("ease off slightly", CuePriority.COACHING)
        manager.enqueue("start walking", CuePriority.INSTRUCTION)
        manager.enqueue(CourseAlert.OFF_COURSE.spoken, CuePriority.NAVIGATION)

        // The split is half-said and stays half-said: the runner going the wrong way is told at the
        // end of that sentence and not over the top of it (#58).
        assertEquals(listOf("split two kilometers"), spokenTexts())
        finishCurrent()
        assertEquals(listOf("split two kilometers", "Off course."), spokenTexts())

        // And nothing waiting behind it is lost — the coaching goes on exactly as it was.
        repeat(2) { finishCurrent() }
        assertEquals(
            listOf("split two kilometers", "Off course.", "start walking", "ease off slightly"),
            spokenTexts(),
        )
    }

    @Test
    fun `order within a level is first in, first out`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.enqueue("split one", CuePriority.INFORMATION)
        manager.enqueue("split two", CuePriority.INFORMATION)
        manager.enqueue("split three", CuePriority.INFORMATION)

        repeat(3) { finishCurrent() }
        assertEquals(listOf("in flight", "split one", "split two", "split three"), spokenTexts())
    }

    @Test
    fun `a cue withdrawn before it is spoken is not spoken, and the ones around it still are`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        val turnaround = manager.enqueue("turn around", CuePriority.INFORMATION)!!
        manager.enqueue("a split", CuePriority.INFORMATION)

        manager.withdrawAll(listOf(turnaround))
        repeat(2) { finishCurrent() }

        assertEquals(listOf("in flight", "a split"), spokenTexts())
    }

    /**
     * The end of a Run takes back everything it enqueued in one act, so that the sentence in flight
     * finishing cannot let the next one out between two withdrawals (#220).
     */
    @Test
    fun `a set of cues withdrawn together is not spoken, and the sentence in flight still finishes`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        val waiting = listOf(
            manager.enqueue("start running", CuePriority.INSTRUCTION)!!,
            manager.enqueue("ease off", CuePriority.COACHING)!!,
            manager.enqueue("a split", CuePriority.INFORMATION)!!,
        )

        manager.withdrawAll(waiting)
        finishCurrent()

        assertEquals(listOf("in flight"), spokenTexts())
    }

    @Test
    fun `withdrawing a set with nothing in it, or cues already spoken, does nothing`() {
        val spoken = manager.enqueue("in flight", CuePriority.INSTRUCTION)!!
        manager.enqueue("a split", CuePriority.INFORMATION)

        manager.withdrawAll(emptyList())
        manager.withdrawAll(listOf(spoken, 9999L))
        finishCurrent()

        assertEquals(listOf("in flight", "a split"), spokenTexts())
    }

    @Test
    fun `focus is taken once when the queue starts speaking and released once when it drains`() {
        manager.enqueue("first", CuePriority.INSTRUCTION)
        manager.enqueue("second", CuePriority.INSTRUCTION)
        finishCurrent()

        // Both cues, one grant: focus is the queue's, not each cue's, so music ducks once and
        // stays ducked across the pair rather than recovering between them.
        @Suppress("DEPRECATION")
        verify(audioManager, times(1)).requestAudioFocus(anyOrNull(), any(), any())
        @Suppress("DEPRECATION")
        verify(audioManager, never()).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L), reports)

        finishCurrent()
        @Suppress("DEPRECATION")
        verify(audioManager, times(1)).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L, false to 2L), reports)

        // And a cue after the drain takes focus again.
        manager.enqueue("later", CuePriority.INSTRUCTION)
        @Suppress("DEPRECATION")
        verify(audioManager, times(2)).requestAudioFocus(anyOrNull(), any(), any())
        assertEquals(listOf(true to 1L, false to 2L, true to 3L), reports)
    }

    @Test
    fun `a cue whose engine callback never arrives does not wedge the queue`() {
        manager.enqueue("silent failure", CuePriority.INSTRUCTION)
        manager.enqueue("next", CuePriority.INSTRUCTION)

        elapse(TIMEOUT_MS + 1)

        // The timeout stops the utterance before claiming it is over (#213), and the queue moves on
        // rather than waiting out a report that is never coming.
        verify(tts).stop()
        assertEquals(listOf("silent failure", "next"), spokenTexts())
    }

    @Test
    fun `a cue the engine refuses does not wedge the queue`() {
        whenever(tts.speak(eq("refused"), any(), anyOrNull(), anyOrNull()))
            .thenReturn(TextToSpeech.ERROR)

        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.enqueue("refused", CuePriority.INSTRUCTION)
        manager.enqueue("next", CuePriority.INSTRUCTION)

        // The refused cue was never going to report back, so the queue moves itself past it rather
        // than waiting on a callback that is not coming.
        finishCurrent()
        assertEquals(listOf("in flight", "refused", "next"), spokenTexts())

        finishCurrent()
        @Suppress("DEPRECATION")
        verify(audioManager, times(1)).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `the safety timeout stops the engine before letting go of focus`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        elapse(TIMEOUT_MS + 1)

        // Focus goes back on the pre-O path here: `Build.VERSION.SDK_INT` is 0 in a plain JVM test,
        // so this is the branch the class takes, whichever one a phone would.
        inOrder(tts, audioManager) {
            verify(tts).stop()
            @Suppress("DEPRECATION")
            verify(audioManager).abandonAudioFocus(anyOrNull())
        }
    }

    @Test
    fun `the safety timeout reports quiet exactly once, whatever the engine says after`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        val utteranceId = handedToEngine().last().second
        elapse(TIMEOUT_MS + 1)

        // The engine catching up with the stop it was told to make: it names a cue that is no
        // longer the one speaking, so it changes nothing.
        listener.onStop(utteranceId, true)
        listener.onDone(utteranceId)

        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `a cue that finishes inside the timeout is untouched by it`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        elapse(TIMEOUT_MS - 1)
        finishCurrent()

        // Nothing was stopped: the engine reported for itself, truthfully, as it always did.
        verify(tts, never()).stop()
        assertEquals(listOf(true to 1L, false to 2L), reports)

        // And the timeout that was pending for it does not fire behind its back.
        elapse(TIMEOUT_MS + 1)
        verify(tts, never()).stop()
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `an engine that reports error or stop for itself is untouched by the timeout`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        listener.onError(handedToEngine().last().second)
        assertEquals(listOf(true to 1L, false to 2L), reports)

        manager.enqueue(TEXT, CuePriority.INFORMATION)
        listener.onStop(handedToEngine().last().second, true)
        assertEquals(listOf(true to 1L, false to 2L, true to 3L, false to 4L), reports)

        elapse(TIMEOUT_MS + 1)
        verify(tts, never()).stop()
        assertEquals(4, reports.size)
    }

    @Test
    fun `the timeout for a cue that has been replaced never touches the cue speaking now`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        elapse(TIMEOUT_MS / 2)
        finishCurrent()
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        reports.clear()

        // Only the second cue's timeout is live; the first one's was cancelled when it finished.
        elapse(TIMEOUT_MS / 2 + 1)
        assertEquals(emptyList<Pair<Boolean, Long>>(), reports)

        elapse(TIMEOUT_MS / 2)
        assertEquals(1, reports.count { !it.first })
    }

    @Test
    fun `the engine goes at once when the service is destroyed with nothing being said`() {
        manager.enqueue(TEXT, CuePriority.INFORMATION)
        finishCurrent()
        reports.clear()

        manager.shutdown()

        verify(tts).shutdown()
        // Nothing to report: the queue had already drained and said so.
        assertEquals(emptyList<Pair<Boolean, Long>>(), reports)
    }

    @Test
    fun `a sentence being said when the service is destroyed finishes before the engine goes`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)

        manager.shutdown()

        // The engine is still up, and what it is saying was neither stopped nor cut short: that
        // truncation mid-word is what a backgrounded STOP used to do (#220).
        verify(tts, never()).shutdown()
        verify(tts, never()).stop()

        finishCurrent()
        verify(tts).shutdown()
    }

    @Test
    fun `focus goes back when the last sentence ends, not when the service is destroyed`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.shutdown()

        @Suppress("DEPRECATION")
        verify(audioManager, never()).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L), reports)

        finishCurrent()
        @Suppress("DEPRECATION")
        verify(audioManager, times(1)).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `a cue still waiting when the service is destroyed is never spoken`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.enqueue("waiting", CuePriority.INSTRUCTION)

        manager.shutdown()
        finishCurrent()

        assertEquals(listOf("in flight"), spokenTexts())
    }

    @Test
    fun `nothing enqueued after the service is destroyed is spoken`() {
        manager.shutdown()

        // No ticket either: there is no promise to take back, because none was made.
        assertNull(manager.enqueue("too late", CuePriority.INSTRUCTION))

        assertEquals(emptyList<String>(), spokenTexts())
        assertEquals(emptyList<Pair<Boolean, Long>>(), reports)
    }

    @Test
    fun `a last sentence the engine never finishes does not keep the engine alive forever`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.shutdown()
        verify(tts, never()).shutdown()

        // The service's scope is already cancelled by the time it tears the engine down, so the
        // per-cue safety timeout cannot be what bounds this wait.
        shutdownBackstop!!.invoke()

        verify(tts).shutdown()
        @Suppress("DEPRECATION")
        verify(audioManager, times(1)).abandonAudioFocus(anyOrNull())
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `the engine is not torn down twice when the last sentence ends after the grace period`() {
        manager.enqueue("in flight", CuePriority.INSTRUCTION)
        manager.shutdown()
        shutdownBackstop!!.invoke()

        // The engine catching up afterwards changes nothing: it has already gone.
        finishCurrent()

        verify(tts, times(1)).shutdown()
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    private companion object {
        const val TIMEOUT_MS = 40L
        const val TEXT = "Halfway"
    }
}
