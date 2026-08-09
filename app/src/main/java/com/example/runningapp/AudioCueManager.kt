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
import java.util.Timer
import java.util.TimerTask

/**
 * The one queue every spoken cue in the app goes through (#53).
 *
 * Cues are enqueued with a [CuePriority] and spoken one at a time, back to back. A cue already
 * being said always finishes — nothing overtakes by interrupting, which is what the engine's flush
 * disposition used to do to every cue that landed close to another (#209). A higher-priority cue
 * enqueued behind lower ones is spoken next, after that one finishes; within a level it is first in,
 * first out.
 *
 * **The queue never drops a cue.** There is no expiry and no staleness rule: everything enqueued is
 * eventually spoken, however late. The one way a cue leaves unspoken is its producer taking it back
 * with [withdraw] — the Run does that with the halfway turnaround when it enters the cool-down,
 * because the cue has stopped being true. Withdrawal is the producer's act, not the queue's.
 *
 * Audio focus belongs to the queue rather than to each cue: it is taken when the queue starts
 * speaking and given back when it drains, so music ducks once across a run of cues instead of
 * recovering between them.
 *
 * Several threads reach in — the Run's, the recorder's, the UI's, and the engine's callback
 * thread — so all of the state is behind this instance's lock.
 */
class AudioCueManager(
    private val tts: TextToSpeech,
    private val audioManager: AudioManager?,
    private val serviceScope: CoroutineScope,
    private val logTag: String,
    private val cueFocusTimeoutMs: Long = 8_000L,
    /**
     * How long the last sentence has to finish once the service starts going away, before the
     * engine is torn down over the top of it. See [shutdown].
     */
    private val shutdownGraceMs: Long = cueFocusTimeoutMs,
    /**
     * How that grace period is timed. Not [serviceScope]: the service cancels that before it tears
     * the engine down, so a job launched there would be cancelled rather than run — and the whole
     * point of the backstop is to fire when nothing else will.
     */
    private val scheduleShutdownBackstop: (delayMs: Long, action: () -> Unit) -> Unit =
        { delayMs, action ->
            val timer = Timer("cue-shutdown-backstop", true)
            timer.schedule(
                object : TimerTask() {
                    override fun run() {
                        try {
                            action()
                        } finally {
                            timer.cancel()
                        }
                    }
                },
                delayMs,
            )
        },
    /**
     * Told true when the queue starts speaking and false when it drains — the app being
     * mid-sentence, for anything that needs to know. Every cue passes through here, Split
     * announcements included, so it is the one place that sees all of them.
     *
     * The [sequence] rises by one with every report and never repeats, so a listener can throw away
     * one that reaches it out of order. It has to: the reports are made outside this class's lock
     * (see [announce]), and they come from the engine's callback thread as well as the caller's.
     */
    private val onCueActivity: (speaking: Boolean, sequence: Long) -> Unit = { _, _ -> },
) {
    /** A cue that has been enqueued and not yet handed to the engine. */
    private data class QueuedCue(val ticket: Long, val text: String, val priority: CuePriority)

    private val queue = mutableListOf<QueuedCue>()
    private var focusRequest: AudioFocusRequest? = null
    private var currentCueUtteranceId: String? = null
    private var isCueFocusHeld = false

    /** Whether the queue is working: from its first cue until it drains. What [announce] reports. */
    private var isSpeaking = false
    private var isPumping = false
    private var cueCounter = 0L
    private var ticketCounter = 0L
    private var cueFocusTimeoutJob: Job? = null
    private var activitySequence = 0L

    /** The service is going away: nothing more is taken, and the engine goes with the last cue. */
    private var isShuttingDown = false

    /** Whether the engine has been torn down, so that it is torn down exactly once. */
    private var isEngineDown = false

    /** One report of the app starting or stopping talking, stamped in the order it happened. */
    private data class CueActivity(val speaking: Boolean, val sequence: Long)

    /**
     * What is left to do once this class's lock has been let go of: the reports to make, and
     * whether the engine is this caller's to tear down.
     *
     * Both are decided under the lock and done outside it — the reports for the reason [announce]
     * gives, and the teardown because it blocks on the engine's own callback thread, which is a
     * thread that takes this lock.
     */
    private data class Settlement(
        val activity: List<CueActivity> = emptyList(),
        val tearDownEngine: Boolean = false,
    )

    fun initialize() {
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finishUtterance("done", utteranceId)

            override fun onError(utteranceId: String?) = finishUtterance("error", utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                finishUtterance("stop(interrupted=$interrupted)", utteranceId)
        })
    }

    /**
     * Say this, in its turn. Returns the ticket the cue can later be taken back by ([withdraw]);
     * a caller with nothing to take back can ignore it.
     */
    fun enqueue(text: String, priority: CuePriority): Long {
        val (ticket, activity) = synchronized(this) {
            if (isShuttingDown) {
                // The engine is on its way out and nothing here will ever be said, so saying so is
                // the honest answer: a ticket that is nobody's, inert to withdraw like any other.
                Log.w(logTag, "A cue arrived as the service was going away, and is not queued")
                return NO_TICKET
            }
            val cue = QueuedCue(++ticketCounter, text, priority)
            // After everything at or above this level, before everything below it: priority order
            // between levels and first-in-first-out within one, from the one insertion.
            val at = queue.indexOfFirst { it.priority > cue.priority }
            queue.add(if (at < 0) queue.size else at, cue)
            cue.ticket to pump()
        }
        announce(activity)
        return ticket
    }

    /**
     * Take back a cue that has not been spoken yet — whatever it was going to say has stopped being
     * true. Inert for a cue already spoken, already withdrawn, or never enqueued, so a producer
     * never has to know which of those happened.
     */
    @Synchronized
    fun withdraw(ticket: Long) {
        if (queue.removeAll { it.ticket == ticket }) {
            Log.d(logTag, "Cue withdrawn before it was spoken: ticket=$ticket")
        }
    }

    /**
     * The service is going away and the engine with it. Nothing waiting is spoken after this, which
     * is the process ending rather than the queue dropping a cue.
     *
     * The sentence being said when this lands is not cut off: the engine goes when that sentence
     * ends — or after [shutdownGraceMs] if the engine never reports back, which is the same refusal
     * to wedge that the per-cue timeout is. Tearing the engine down over the top of a cue is what
     * used to truncate the last words of a run stopped from the notification (#220).
     *
     * In practice there is nothing waiting by the time this is reached, because the end of a Run
     * takes its cues back first (`OutstandingCues`). Clearing here is the backstop for the service
     * going away without a Run ending.
     *
     * There is no session-stop counterpart. Letting go of audio focus when a Run ends used to be a
     * step of its own, because focus was held per cue and a cue could hold it after the Run was
     * over; the queue lets go of it when it drains, which is at most a sentence away.
     */
    fun shutdown() {
        settle(beginShutdown())
    }

    @Synchronized
    private fun beginShutdown(): Settlement {
        if (isShuttingDown) return Settlement()
        isShuttingDown = true
        queue.clear()

        if (currentCueUtteranceId == null) {
            return Settlement(fallQuiet("service_destroy"), tearDownEngine = claimEngineTeardown())
        }

        Log.d(logTag, "Service destroyed mid-sentence: the engine goes when the sentence ends")
        scheduleShutdownBackstop(shutdownGraceMs) { settle(giveUpOnLastSentence()) }
        return Settlement()
    }

    /**
     * The last sentence has outstayed its grace period. The engine goes anyway — it is the process
     * ending, and holding audio focus for a cue the engine has gone quiet on is worse than the
     * words that are lost.
     */
    @Synchronized
    private fun giveUpOnLastSentence(): Settlement {
        if (isEngineDown) return Settlement()
        currentCueUtteranceId?.let { endUtterance("shutdown_grace_expired", it) }
        return Settlement(fallQuiet("service_destroy"), tearDownEngine = claimEngineTeardown())
    }

    /** Whether this caller is the one to tear the engine down. Called with the lock held. */
    private fun claimEngineTeardown(): Boolean {
        if (isEngineDown) return false
        isEngineDown = true
        return true
    }

    /** Do what was decided under the lock, now that it has been let go of. */
    private fun settle(settlement: Settlement) {
        announce(settlement.activity)
        if (settlement.tearDownEngine) {
            try {
                tts.shutdown()
            } catch (e: Exception) {
                Log.w(logTag, "Shutting the engine down failed", e)
            }
        }
    }

    /**
     * Tell the listener what happened, once this class's lock has been let go of.
     *
     * Not inside it, deliberately: a listener that speaks — or takes a lock of its own that
     * something speaking holds — would have the two locks taken in both orders, which is a deadlock
     * the day a cue ends on the engine's thread at the instant another goes out (Codex, #212).
     *
     * What that costs is ordering: two reports can reach the listener the wrong way round. That is
     * what the sequence number on [onCueActivity] is for — the order is decided under the lock and
     * carried rather than implied.
     */
    private fun announce(activity: List<CueActivity>) {
        activity.forEach { onCueActivity(it.speaking, it.sequence) }
    }

    /**
     * Hand cues to the engine while it is idle and there are cues to hand it, then report the app
     * quiet if that emptied the queue.
     *
     * Called with the lock held, and it returns the reports rather than making them — [announce]
     * does that outside the lock.
     *
     * The engine may report a cue done from inside `speak` itself, on this very thread, which
     * re-enters this class through the callback and lands back here. [isPumping] makes that
     * re-entry a no-op: the loop below is already running and will see the cleared utterance on its
     * next turn, so cues stay in order and no report is made from under the lock.
     */
    private fun pump(): List<CueActivity> {
        if (isPumping) return emptyList()
        isPumping = true
        val activity = mutableListOf<CueActivity>()
        try {
            while (currentCueUtteranceId == null) {
                val next = queue.removeFirstOrNull() ?: break
                activity += startSpeakingIfQuiet()
                speak(next)
            }
            if (currentCueUtteranceId == null && queue.isEmpty()) activity += fallQuiet("drained")
        } finally {
            isPumping = false
        }
        return activity
    }

    /** Take audio focus for the run of cues that starts here, and say the app is talking. */
    private fun startSpeakingIfQuiet(): List<CueActivity> {
        if (isSpeaking) return emptyList()
        isSpeaking = true
        // Best effort. A refused focus request is not a reason to lose the cue — the queue drops
        // nothing — so the cue is spoken either way and only the ducking is missed.
        if (requestAudioFocus()) {
            isCueFocusHeld = true
        } else {
            Log.w(logTag, "Audio focus request failed; speaking anyway")
        }
        return listOf(CueActivity(speaking = true, sequence = ++activitySequence))
    }

    private fun speak(cue: QueuedCue) {
        val utteranceId = "CUE_${++cueCounter}_${System.currentTimeMillis()}"
        currentCueUtteranceId = utteranceId
        scheduleCueFocusTimeout(utteranceId)

        val params = android.os.Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        // QUEUE_ADD, and the engine is only ever asked while it is idle — one cue is outstanding at
        // a time, so it has nothing of ours to queue behind. Flushing is what truncated cues (#209).
        val queued = tts.speak(cue.text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        Log.d(logTag, "Playing Cue: ${cue.text} (utteranceId=$utteranceId priority=${cue.priority})")
        if (queued != TextToSpeech.SUCCESS) {
            // Nothing will be said and nothing will report back, so the queue must move itself on
            // rather than wait for a callback that is not coming.
            Log.w(logTag, "The engine refused a cue: utteranceId=$utteranceId")
            endUtterance("speak_rejected", utteranceId)
        }
    }

    /**
     * The engine finished with an utterance; the next cue goes out.
     *
     * The report is only acted on if it is about the cue that is speaking now. A callback for a cue
     * that has already been stopped used to reach the release with its check made a moment earlier
     * and elsewhere, which let it hand back the *new* cue's focus and declare the app quiet while
     * that cue was still talking. The check and the act are one here (Codex, #212).
     */
    private fun finishUtterance(reason: String, utteranceId: String?) =
        settle(finishIfCurrent(reason, utteranceId))

    @Synchronized
    private fun finishIfCurrent(reason: String, utteranceId: String?): Settlement {
        if (utteranceId == null || utteranceId != currentCueUtteranceId) return Settlement()
        endUtterance(reason, utteranceId)
        return afterCue(pump())
    }

    /**
     * What follows a cue ending. Nothing, unless the service is going away and that was the last
     * sentence — the one the engine was being kept up for. Called with the lock held.
     */
    private fun afterCue(activity: List<CueActivity>): Settlement {
        val done = isShuttingDown && currentCueUtteranceId == null
        return Settlement(activity, tearDownEngine = done && claimEngineTeardown())
    }

    /** This cue is over. Focus is untouched: it belongs to the queue, not to the cue. */
    private fun endUtterance(reason: String, utteranceId: String) {
        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = null
        currentCueUtteranceId = null
        Log.d(logTag, "Cue finished: reason=$reason utteranceId=$utteranceId")
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

    /**
     * The engine has not reported back in [cueFocusTimeoutMs]. Checked against the cue of the
     * moment, as ever.
     *
     * The timeout is about not wedging the queue and not holding audio focus forever — it is not a
     * claim that the engine has finished, and it cannot be, because a slow enough speech rate can
     * carry one short sentence past it. So it makes the claim true before making it: the utterance
     * is stopped first, and only then does the next cue go out (#213).
     */
    private fun scheduleCueFocusTimeout(utteranceId: String) {
        cueFocusTimeoutJob?.cancel()
        cueFocusTimeoutJob = serviceScope.launch {
            delay(cueFocusTimeoutMs)
            // What follows must not suspend: it runs inside this job, and [endUtterance] cancels the
            // job as its first act. A suspension point between there and [announce] would be the one
            // report for this cue swallowed by its own cancellation.
            settle(stopAndMoveOn(utteranceId))
        }
    }

    @Synchronized
    private fun stopAndMoveOn(utteranceId: String): Settlement {
        if (utteranceId != currentCueUtteranceId) return Settlement()

        // Disowned before it is stopped, so the engine's own `onStop` for it — which may land on
        // this very thread, from inside the call below — finds nothing current and reports nothing.
        currentCueUtteranceId = null
        try {
            tts.stop()
        } catch (e: Exception) {
            // Nothing left to do about the speech, but the queue still has to move: an engine that
            // fails to stop must not also leave the app mid-sentence forever.
            Log.w(logTag, "Stopping the timed-out cue failed: utteranceId=$utteranceId", e)
        }
        endUtterance("timeout", utteranceId)
        return afterCue(pump())
    }

    /**
     * The queue has run dry: let go of audio focus and report the app quiet. Called only with the
     * lock held; the report it returns is made by [announce] once the lock is let go of.
     */
    private fun fallQuiet(reason: String): List<CueActivity> {
        if (!isSpeaking) return emptyList()

        if (isCueFocusHeld) abandonAudioFocus()
        isCueFocusHeld = false
        isSpeaking = false
        Log.d(logTag, "Cue queue drained: reason=$reason")
        return listOf(CueActivity(speaking = false, sequence = ++activitySequence))
    }

    companion object {
        /** What [enqueue] answers when there was no queue left to join. Withdrawing it is inert. */
        const val NO_TICKET = 0L
    }
}
