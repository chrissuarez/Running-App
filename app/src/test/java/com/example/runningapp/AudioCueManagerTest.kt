package com.example.runningapp

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The cue player's audio focus, and what it tells listeners about the app being mid-sentence.
 *
 * The speech engine is a stand-in that says nothing back unless the test makes it, and the safety
 * timeout is a few milliseconds long on a virtual clock — so the slow-cue case (#213) is reachable
 * here without anyone waiting eight seconds or speaking slowly.
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

    /** The ids the engine has been handed so far — the names it reports cues back under. */
    private fun utteranceIds(): List<String> = argumentCaptor<String>().run {
        verify(tts, atLeastOnce()).speak(any(), eq(TextToSpeech.QUEUE_FLUSH), anyOrNull(), capture())
        allValues
    }

    private fun spokenUtteranceId(): String = utteranceIds().first()

    private fun secondUtteranceId(): String = utteranceIds()[1]

    @Test
    fun `the safety timeout stops the engine before letting go of focus`() {
        manager.playCue(TEXT)
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
        manager.playCue(TEXT)
        val utteranceId = spokenUtteranceId()
        elapse(TIMEOUT_MS + 1)

        // The engine catching up with the stop it was told to make: it names a cue that is no
        // longer the one speaking, so it changes nothing.
        listener.onStop(utteranceId, true)
        listener.onDone(utteranceId)

        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `a cue that finishes inside the timeout is untouched by it`() {
        manager.playCue(TEXT)
        val utteranceId = spokenUtteranceId()
        elapse(TIMEOUT_MS - 1)
        listener.onDone(utteranceId)

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
        manager.playCue(TEXT)
        listener.onError(spokenUtteranceId())
        assertEquals(listOf(true to 1L, false to 2L), reports)

        manager.playCue(TEXT)
        listener.onStop(secondUtteranceId(), true)
        assertEquals(listOf(true to 1L, false to 2L, true to 3L, false to 4L), reports)

        elapse(TIMEOUT_MS + 1)
        verify(tts, never()).stop()
        assertEquals(4, reports.size)
    }

    @Test
    fun `a cue the engine refuses is quiet at once, with no timeout left running`() {
        whenever(tts.speak(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(TextToSpeech.ERROR)
        manager.playCue(TEXT)

        assertEquals(listOf(true to 1L, false to 2L), reports)

        elapse(TIMEOUT_MS + 1)
        verify(tts, never()).stop()
        assertEquals(listOf(true to 1L, false to 2L), reports)
    }

    @Test
    fun `the timeout for a cue that has been replaced never touches the cue speaking now`() {
        manager.playCue(TEXT)
        elapse(TIMEOUT_MS / 2)
        manager.playCue(TEXT)
        reports.clear()

        // Only the second cue's timeout is live; the first one's was cancelled when it was replaced.
        elapse(TIMEOUT_MS / 2 + 1)
        assertEquals(emptyList<Pair<Boolean, Long>>(), reports)

        elapse(TIMEOUT_MS / 2)
        assertEquals(1, reports.count { !it.first })
    }

    private companion object {
        const val TIMEOUT_MS = 40L
        const val TEXT = "Halfway"
    }
}
