package com.example.runningapp

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class AudioCueManager(
    private val tts: TextToSpeech,
    private val audioManager: AudioManager?,
    private val serviceScope: CoroutineScope,
    private val logTag: String,
    private val cueFocusTimeoutMs: Long = 8_000L,
    /**
     * Told true when a cue starts speaking and false when it stops, for anything that needs to know
     * whether the app is mid-sentence — see [QuietGapCue]. Every cue passes through here, Split
     * announcements included, so it is the one place that sees all of them.
     *
     * The [sequence] rises by one with every report and never repeats, so a listener can throw away
     * one that reaches it out of order. It has to: the reports are made outside this class's lock
     * (see [announce]), and they come from the engine's callback thread as well as the caller's.
     */
    private val onCueActivity: (speaking: Boolean, sequence: Long) -> Unit = { _, _ -> },
) {
    private var focusRequest: AudioFocusRequest? = null
    private var currentCueUtteranceId: String? = null
    private var isCueFocusHeld = false
    private var cueCounter = 0L
    private var cueFocusTimeoutJob: Job? = null
    private var activitySequence = 0L

    /** One report of the app starting or stopping talking, stamped in the order it happened. */
    private data class CueActivity(val speaking: Boolean, val sequence: Long)

    fun initialize() {
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = releaseUtterance("done", utteranceId)

            override fun onError(utteranceId: String?) = releaseUtterance("error", utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                releaseUtterance("stop(interrupted=$interrupted)", utteranceId)
        })
    }

    fun playCue(text: String) = announce(startCue(text))

    fun releaseForSessionStop() = announce(releaseCurrentCue("session_stop"))

    fun shutdown() {
        announce(releaseCurrentCue("service_destroy"))
        tts.shutdown()
    }

    /**
     * Tell the listener what happened, once this class's lock has been let go of.
     *
     * Not inside it, deliberately. The listener is [QuietGapCue], which speaks a held cue from
     * inside its own lock — so it takes this class's lock while holding its own. Reporting from
     * under this one as well would be the two locks taken in both orders, which is a deadlock the
     * day a cue ends on the engine's thread at the instant a held one goes out (Codex, #212).
     *
     * What that costs is ordering: two reports can reach the listener the wrong way round. That is
     * what the sequence number on [onCueActivity] is for — the order is decided here, under the
     * lock, and carried rather than implied.
     */
    private fun announce(activity: List<CueActivity>) {
        activity.forEach { onCueActivity(it.speaking, it.sequence) }
    }

    /**
     * Start a cue: take audio focus, name the utterance, and ask the engine to speak it.
     *
     * All of it under the lock, so an engine callback for the cue this one is replacing cannot land
     * half way through and clear the new cue's focus out from under it.
     */
    @Synchronized
    private fun startCue(text: String): List<CueActivity> {
        val activity = mutableListOf<CueActivity>()
        if (isCueFocusHeld) activity += release("pre_speak_cleanup", currentCueUtteranceId)

        if (!requestAudioFocus()) {
            Log.w(logTag, "Audio focus request failed")
            return activity
        }

        val utteranceId = "CUE_${++cueCounter}_${System.currentTimeMillis()}"
        currentCueUtteranceId = utteranceId
        isCueFocusHeld = true
        scheduleCueFocusTimeout(utteranceId)
        // Stamped before the engine is asked to speak, not after. The engine reports done, error
        // and stop on its own thread, and it may do so before speak() even returns — a "started"
        // stamped after that terminal report would leave the app permanently mid-sentence in
        // [QuietGapCue]'s eyes, and every later held cue would wait out its full ceiling in
        // silence (Codex, #212).
        activity += CueActivity(speaking = true, sequence = ++activitySequence)

        val params = android.os.Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            // Nothing will be spoken and nothing will report back, so the app is quiet now.
            activity += release("speak_rejected", utteranceId)
        }
        Log.d(logTag, "Playing Cue: $text (utteranceId=$utteranceId)")
        return activity
    }

    /**
     * The engine finished with an utterance.
     *
     * The report is only acted on if it is about the cue that is speaking now. A callback for a cue
     * that has already been flushed used to reach the release with its check made a moment earlier
     * and elsewhere, which let it hand back the *new* cue's focus and declare the app quiet while
     * that cue was still talking. The check and the release are one act here (Codex, #212).
     */
    private fun releaseUtterance(reason: String, utteranceId: String?) =
        announce(releaseIfCurrent(reason, utteranceId))

    @Synchronized
    private fun releaseIfCurrent(reason: String, utteranceId: String?): List<CueActivity> =
        if (utteranceId != null && utteranceId == currentCueUtteranceId) {
            release(reason, utteranceId)
        } else {
            emptyList()
        }

    @Synchronized
    private fun releaseCurrentCue(reason: String): List<CueActivity> =
        release(reason, currentCueUtteranceId)

    fun onTtsInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            initialize()
        } else {
            Log.e(logTag, "TTS Initialization failed")
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()

            focusRequest = request
            val res = audioManager?.requestAudioFocus(request)
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    /**
     * The engine has not reported back in [cueFocusTimeoutMs]. Checked against the cue of the
     * moment, as ever.
     *
     * The timeout is about not holding audio focus forever — it is not a claim that the engine has
     * finished, and it cannot be, because a slow enough speech rate can carry one short sentence
     * past it. So it makes the claim true before making it: the utterance is stopped first, and only
     * then is focus let go of and the app reported quiet (#213).
     */
    private fun scheduleCueFocusTimeout(utteranceId: String) {
        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = serviceScope.launch {
            delay(cueFocusTimeoutMs)
            // What follows must not suspend: it runs inside this job, and [release] cancels the job
            // as its first act. A suspension point between there and [announce] would be the one
            // report for this cue swallowed by its own cancellation.
            announce(stopAndReleaseIfCurrent(utteranceId))
        }
    }

    @Synchronized
    private fun stopAndReleaseIfCurrent(utteranceId: String): List<CueActivity> {
        if (!isCueFocusHeld || utteranceId != currentCueUtteranceId) return emptyList()

        // Disowned before it is stopped, so the engine's own `onStop` for it — which may land on
        // this very thread, from inside the call below — finds nothing current, reports nothing,
        // and so cannot reach the listener from under this lock (the #212 deadlock rule). The one
        // report for this cue is the one [release] returns, made by [announce] afterwards.
        currentCueUtteranceId = null
        try {
            tts.stop()
        } catch (e: Exception) {
            // Nothing left to do about the speech, but focus still has to go back: an engine that
            // fails to stop must not also leave the app holding focus and mid-sentence forever.
            Log.w(logTag, "Stopping the timed-out cue failed: utteranceId=$utteranceId", e)
        }
        return release("timeout", utteranceId)
    }

    /**
     * Let go of audio focus and report the app quiet. Called only with the lock held; the report it
     * returns is made by [announce] once the lock is let go of.
     */
    private fun release(reason: String, utteranceId: String?): List<CueActivity> {
        if (!isCueFocusHeld) return emptyList()

        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = null
        abandonAudioFocus()
        isCueFocusHeld = false
        currentCueUtteranceId = null
        Log.d(logTag, "Released cue audio focus: reason=$reason utteranceId=$utteranceId")
        // Focus is held for exactly as long as a cue is being spoken — done, error, stop, and the
        // safety timeout once it has stopped the utterance, all land here — so letting go of it is
        // the app falling quiet.
        return listOf(CueActivity(speaking = false, sequence = ++activitySequence))
    }
}
