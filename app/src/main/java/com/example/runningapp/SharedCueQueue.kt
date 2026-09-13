package com.example.runningapp

/**
 * The one cue queue the app speaks through, however many times the Run's service comes and goes
 * (#274).
 *
 * The queue used to belong to the service instance. A Run stopped mid-sentence keeps its engine up
 * until that sentence ends (#220), and a Run started inside that window gets a new service instance
 * — which built a second engine beside the first. For the tail of the old sentence the app had two
 * voices: the new Run's first cue over the old Run's last words, and the old queue letting go of
 * audio focus while the new one was still talking.
 *
 * So the queue lives at process scope and a service borrows it. [acquire] hands back the queue
 * still saying its last sentence if there is one, and a fresh one only once the engine has gone;
 * the service lets go of it with [AudioCueManager.shutdown], exactly as before. Android never runs
 * two instances of one service at once — the old one's `onDestroy` comes before the new one's
 * `onCreate` — so there is only ever one borrower, and letting go needs no count.
 *
 * [build] makes a queue and its engine. A queue whose engine has gone is never handed out again,
 * so an idle process holds no engine: the next Run builds one, the way every Run used to.
 */
class SharedCueQueue(private val build: () -> AudioCueManager) {
    private var current: AudioCueManager? = null

    @Synchronized
    fun acquire(): AudioCueManager {
        current?.let { if (it.reopen()) return it }
        return build().also { current = it }
    }
}
