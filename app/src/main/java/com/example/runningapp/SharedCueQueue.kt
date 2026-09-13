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
 * So the queue lives at process scope and a service holds a [AudioCueManager.Lease] on it.
 * [acquire] hands back a hold on the queue still saying its last sentence if there is one, and on a
 * fresh one only once the engine has gone; the service lets go with [AudioCueManager.Lease.shutdown].
 * Taking a new hold ends the last one, so the old Run's late producers cannot speak into the new
 * Run. Android never runs two instances of one service at once — the old one's `onDestroy` comes
 * before the new one's `onCreate` — so there is only ever one hold that matters.
 *
 * [build] makes a queue and its engine. A queue whose engine has gone is never handed out again,
 * so an idle process holds no engine: the next Run builds one, the way every Run used to.
 */
class SharedCueQueue(private val build: () -> AudioCueManager) {
    private var current: AudioCueManager? = null

    @Synchronized
    fun acquire(): AudioCueManager.Lease {
        current?.open()?.let { return it }
        val fresh = build().also { current = it }
        return checkNotNull(fresh.open()) { "A queue just built has its engine" }
    }
}
