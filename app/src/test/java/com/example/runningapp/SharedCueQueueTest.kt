package com.example.runningapp

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * One voice across the Run's service coming and going (#274): a service created while the last one's
 * sentence is still being said borrows that queue rather than building a second engine beside it.
 *
 * The queue's own behaviour across the handover — the new cue waiting, focus held — is
 * [AudioCueManagerTest]'s. This is only which queue a service is handed.
 */
class SharedCueQueueTest {

    private val built = mutableListOf<AudioCueManager>()

    private val shared = SharedCueQueue {
        val tts: TextToSpeech = mock()
        whenever(tts.speak(any(), any(), anyOrNull(), anyOrNull())).thenReturn(TextToSpeech.SUCCESS)
        val audioManager: AudioManager = mock()
        AudioCueManager(
            tts = tts,
            audioManager = audioManager,
            scope = TestScope(),
            logTag = "test",
            // The grace period is held and never run: these tests are about who is handed what.
            scheduleShutdownBackstop = { _, _ -> },
        ).also { built += it }
    }

    @Test
    fun `the first service builds the queue`() {
        val queue = shared.acquire()

        assertEquals(listOf(queue), built)
    }

    @Test
    fun `a service created during the last sentence is handed the same queue`() {
        val old = shared.acquire()
        old.enqueue("last words", CuePriority.INSTRUCTION)
        old.shutdown()

        val next = shared.acquire()

        assertSame(old, next)
        assertEquals(1, built.size)
    }

    @Test
    fun `a service created once the engine has gone builds a fresh queue`() {
        val old = shared.acquire()
        // Nothing being said, so the engine goes at once.
        old.shutdown()

        val next = shared.acquire()

        assertNotSame(old, next)
        assertEquals(2, built.size)
    }
}
