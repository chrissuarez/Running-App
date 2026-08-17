package com.example.runningapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

/**
 * The runner changing zone, as something a rule can be woken by (#320).
 *
 * Today is observed and never captured (CONTEXT.md): every reader that places a Run on a day asks
 * the phone for the zone at the moment it answers. That settles what the answer *is*, and leaves
 * open when it is next given — a reader whose other inputs are database flows says nothing until
 * history changes, so a runner who lands in another zone with the screen in front of them is shown
 * the answer from where they took off until some Run happens to move. This is the missing input: the
 * passage of the runner through zones, on the same terms as the passage of time through midnight.
 *
 * Stated here once and taken by each reader, rather than each reader registering for the broadcast
 * itself: there is one fact — the phone's zone moved — and every reader of Today wants it. The flow
 * carries nothing but the nudge, for the same reason the day's does: whoever was woken reads the zone
 * themselves, at the moment they answer, so nothing here can hand on a zone that was true when the
 * broadcast arrived and is not true now.
 *
 * `ACTION_TIMEZONE_CHANGED` is a protected broadcast — only the system can send it — so the
 * receiver is registered unexported and nothing but a real zone change can wake these readers.
 *
 * Shared with no replay and only while somebody is listening: the receiver is registered when the
 * first reader collects and unregistered when the last one stops, and a reader that arrives after a
 * change has already happened does not want to be told about it — it is about to read the zone for
 * the first time anyway. Which is what [startingNow] is for, and every reader goes through it.
 */
fun systemZoneChanges(context: Context, scope: CoroutineScope): SharedFlow<Unit> =
    callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

/**
 * [this] with a nudge of its own at the front — how every reader takes a nudge stream.
 *
 * The stream itself only speaks when the phone moves, and a reader that waited for that would say
 * nothing at all until the runner flew. Starting it here rather than at each reader means one
 * statement of the rule "answer now, and again each time this fires", which is the whole shape both
 * [repeatedOn] and `SessionRepository.dayTurns` are built on.
 */
fun Flow<Unit>.startingNow(): Flow<Unit> = onStart { emit(Unit) }

/**
 * [this], offered again each time [nudges] fires — the shape every chart reader of Today takes.
 *
 * The value itself is untouched: what moves is *when* it is handed on. A reader built as `flow.map {
 * … zone() … }` reads the right zone and reads it too rarely; wrapping the flow in this makes the
 * map run again on a nudge, with the same history and a new zone.
 *
 * `combine` holds the latest value and re-offers it on each nudge, and [startingNow] is what stops a
 * reader waiting for the first one.
 *
 * The reader that does *not* take this shape is `SessionRepository.dayTurns`: a sleep aimed at
 * midnight is not a value to be re-offered, it is work to be thrown away and restarted, so that one
 * takes the same nudge stream through `flatMapLatest` instead.
 */
fun <T> Flow<T>.repeatedOn(nudges: Flow<Unit>): Flow<T> =
    combine(nudges.startingNow()) { value, _ -> value }
