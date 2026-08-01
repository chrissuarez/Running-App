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
     */
    private val onCueActivity: (speaking: Boolean) -> Unit = {},
) {
    private var focusRequest: AudioFocusRequest? = null
    private var currentCueUtteranceId: String? = null
    private var isCueFocusHeld = false
    private var cueCounter = 0L
    private var cueFocusTimeoutJob: Job? = null

    fun initialize() {
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (isCurrentCueUtterance(utteranceId)) {
                    releaseCueAudioFocus("done", utteranceId)
                }
            }

            override fun onError(utteranceId: String?) {
                if (isCurrentCueUtterance(utteranceId)) {
                    releaseCueAudioFocus("error", utteranceId)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (isCurrentCueUtterance(utteranceId)) {
                    releaseCueAudioFocus("stop(interrupted=$interrupted)", utteranceId)
                }
            }
        })
    }

    fun playCue(text: String) {
        if (isCueFocusHeld) {
            releaseCueAudioFocus("pre_speak_cleanup", currentCueUtteranceId)
        }

        if (requestAudioFocus()) {
            val utteranceId = "CUE_${++cueCounter}_${System.currentTimeMillis()}"
            currentCueUtteranceId = utteranceId
            isCueFocusHeld = true
            scheduleCueFocusTimeout(utteranceId)

            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            onCueActivity(true)
            Log.d(logTag, "Playing Cue: $text (utteranceId=$utteranceId)")
        } else {
            Log.w(logTag, "Audio focus request failed")
        }
    }

    fun releaseForSessionStop() {
        releaseCueAudioFocus("session_stop", currentCueUtteranceId)
    }

    fun shutdown() {
        releaseCueAudioFocus("service_destroy", currentCueUtteranceId)
        tts.shutdown()
    }

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

    private fun isCurrentCueUtterance(utteranceId: String?): Boolean {
        return utteranceId != null && utteranceId == currentCueUtteranceId
    }

    private fun scheduleCueFocusTimeout(utteranceId: String) {
        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = serviceScope.launch {
            delay(cueFocusTimeoutMs)
            if (isCurrentCueUtterance(utteranceId)) {
                releaseCueAudioFocus("timeout", utteranceId)
            }
        }
    }

    private fun releaseCueAudioFocus(reason: String, utteranceId: String? = null) {
        if (!isCueFocusHeld) return

        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = null
        abandonAudioFocus()
        isCueFocusHeld = false
        currentCueUtteranceId = null
        // Focus is held for exactly as long as a cue is being spoken — done, error, stop and the
        // safety timeout all land here — so letting go of it is the app falling quiet.
        onCueActivity(false)
        Log.d(logTag, "Released cue audio focus: reason=$reason utteranceId=$utteranceId")
    }
}
