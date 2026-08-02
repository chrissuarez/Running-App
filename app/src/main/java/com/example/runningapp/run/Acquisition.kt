package com.example.runningapp.run

/** How long a discovery scan runs before it stops itself, with nothing ever auto-connecting. */
const val SCAN_TIMEOUT_MS = 60_000L

/** The first backoff after a Strap drops. Doubles from here. */
const val FIRST_RETRY_DELAY_MS = 3_000L

/** The backoff stops doubling here. */
const val MAX_RETRY_DELAY_MS = 30_000L

/**
 * With no Run live, stop chasing an unreachable Strap after this many retries.
 *
 * Mid-Run reconnects are uncapped by design (#110): a dropout never ends or freezes a Run. Pre-Run
 * the endless loop served no one — a Strap left in a drawer kept the record screen saying it was
 * looking, forever.
 */
const val PRE_RUN_MAX_RETRIES = 3

/**
 * What the Run is told when the Strap goes and we mean to get it back. It is not a passing blip:
 * the Run holds it for the whole outage, so it is the value written to every heart-rate row
 * recorded while the Strap is away.
 */
const val LOST_RETRYING = "Disconnected (Retrying)"

/** What the Run is told when the Strap goes and we do not mean to get it back. */
const val LOST_DISCONNECTED = "Disconnected"

/**
 * The error code for a scan that never got as far as one of Android's own.
 *
 * Android's `ScanCallback` codes start at 1, so 0 is free and says "not the scanner's fault" —
 * there was no scanner, or the call threw before it could report anything.
 */
const val SCAN_UNAVAILABLE = 0

/** Something happened that an Acquisition may care about. */
sealed interface AcquisitionEvent {

    /**
     * Look for Straps.
     *
     * [force] is the Force Scan tap, and it is the difference between "scan if that makes sense"
     * and "scan regardless" — including tearing down a Strap already connected. It rides on the
     * event rather than being decided by the caller because whether we are connected is only
     * knowable here: a caller reading the published state can be a `GattConnected` behind.
     */
    data class ScanRequested(val force: Boolean = false) : AcquisitionEvent

    /**
     * Chase this Strap. [makeActive] is true only for an explicit Connect tap — every background
     * path (record-screen auto-connect, saved-Strap reconnect) passes false, so auto-connecting
     * Strap A while the runner makes Strap B active cannot steal the active slot back.
     */
    data class ConnectRequested(
        val address: String,
        val name: String?,
        val makeActive: Boolean,
    ) : AcquisitionEvent

    /** The scanner saw a Strap. An unnamed one is not a Strap anyone can choose between. */
    data class StrapSeen(val address: String, val name: String?) : AcquisitionEvent

    /** The platform refused the scan. */
    data class ScanFailed(val errorCode: Int) : AcquisitionEvent


    /** The runner removed a saved Strap. */
    data class ForgetRequested(val address: String) : AcquisitionEvent

    /** Let the Strap go and do not chase it. */
    data object DisconnectRequested : AcquisitionEvent

    /** A GATT reported itself connected. May be a Strap we abandoned — see [Acquisition]. */
    data class GattConnected(val address: String) : AcquisitionEvent

    /** A GATT reported itself disconnected. */
    data class GattDisconnected(val address: String) : AcquisitionEvent

    /** A GATT finished discovering its services. */
    data class ServicesDiscovered(
        val address: String,
        val name: String,
        val hasHeartRateService: Boolean,
    ) : AcquisitionEvent

    /** The pulse. Carries no time of its own — the time is context. */
    data object Tick : AcquisitionEvent
}

/** Something for the service to do. No decision left in any of them. */
sealed interface AcquisitionEffect {
    data object StartScan : AcquisitionEffect
    data object StopScan : AcquisitionEffect
    data class ConnectGatt(val address: String) : AcquisitionEffect

    /** Close a GATT without asking it to disconnect — for one already gone, or abandoned. */
    data class CloseGatt(val address: String) : AcquisitionEffect

    /** Ask a live GATT to disconnect, then close it. */
    data class DisconnectAndCloseGatt(val address: String) : AcquisitionEffect

    /** Discover services on a freshly connected GATT. The service also sets its priority here. */
    data class DiscoverServices(val address: String) : AcquisitionEffect

    /** Subscribe to heart-rate notifications. */
    data class SubscribeToHeartRate(val address: String) : AcquisitionEffect

    /** Save the Strap, promoting it to active only when an explicit tap earned that. */
    data class SaveStrap(
        val address: String,
        val name: String,
        val makeActive: Boolean,
    ) : AcquisitionEffect

    /**
     * Tell the Run its reading is gone, rather than sending it a zero — so the outage is banked as
     * no-data instead of being fabricated from the last packet.
     */
    data class TellRunStrapLost(val status: String) : AcquisitionEffect
}

/**
 * What is true right now, read fresh on every decision rather than remembered.
 *
 * None of it is Acquisition's to keep. [runIsLive] belongs to the Run, and holding a copy would be
 * a tenth place for the same fact to drift; the permissions and the adapter belong to Android and
 * can change under us at any moment. Passing them in means a revoked permission mid-chase is just a
 * different context on the next tick, which needs no special handling at all.
 */
data class AcquisitionContext(
    val now: Long,
    val runIsLive: Boolean,
    val canScan: Boolean,
    val canConnect: Boolean,
    val bluetoothOn: Boolean,
)

/** A new state and what to do about it. */
data class AcquisitionOutcome(
    val state: AcquisitionState,
    val effects: List<AcquisitionEffect> = emptyList(),
)

/**
 * The Acquisition: a rulebook, not an actor.
 *
 * One entry point takes an [AcquisitionEvent] and returns the whole new [AcquisitionState] together
 * with the [AcquisitionEffect]s for the service to perform. It never scans, connects, closes,
 * saves or waits, and it touches no Android type.
 *
 * This is ADR 0002's inversion applied a second time, and it buys the same thing. The bugs here
 * have never been in the arithmetic — they have been in the interleavings: a scan timing out after
 * the runner already tapped a Strap, a GATT calling back about one that was abandoned two connects
 * ago, a retry counter that reset every cycle so the give-up cap could never trip. Each of those is
 * now a list of events and an expected result.
 *
 * Three pieces of hand-rolled concurrency went with it. `scanEpoch` and the connect sequence
 * counter both existed to let something that had already happened decide it no longer counted;
 * with the deadline held as state and every decision made on one thread, a superseded scan or
 * connect is simply a phase this state is no longer in. The lock that serialised the
 * close-old/connect-new handoff went the same way.
 *
 * What could not go is rejecting a stale callback by address. A real GATT can still report itself
 * connected long after we stopped caring — that is Android, not our threading — so every callback
 * is checked against the Strap actually being chased, and one that does not match is closed and
 * ignored. It is now a test rather than a comment.
 *
 * See docs/adr/0007-acquisition-is-a-rulebook-too.md.
 */
object Acquisition {

    fun decide(
        state: AcquisitionState,
        event: AcquisitionEvent,
        context: AcquisitionContext,
    ): AcquisitionOutcome = when (event) {
        is AcquisitionEvent.ScanRequested -> scanRequested(state, event, context)
        is AcquisitionEvent.ConnectRequested -> connectRequested(state, event, context)
        is AcquisitionEvent.StrapSeen -> strapSeen(state, event)
        is AcquisitionEvent.ScanFailed -> scanFailed(state, event)
        is AcquisitionEvent.ForgetRequested -> forgetRequested(state, event)
        AcquisitionEvent.DisconnectRequested -> disconnectRequested(state)
        is AcquisitionEvent.GattConnected -> gattConnected(state, event)
        is AcquisitionEvent.GattDisconnected -> gattDisconnected(state, event, context)
        is AcquisitionEvent.ServicesDiscovered -> servicesDiscovered(state, event, context)
        AcquisitionEvent.Tick -> tick(state, context)
    }

    /**
     * Start a fresh scan, abandoning whatever was in flight.
     *
     * Already connected is the one case that declines: a scan tears the Strap down, and nothing
     * asks for one while holding a good connection except by accident. A Force Scan tap is not an
     * accident, so it tears the Strap down instead of declining.
     */
    private fun scanRequested(
        state: AcquisitionState,
        event: AcquisitionEvent.ScanRequested,
        context: AcquisitionContext,
    ): AcquisitionOutcome {
        if (!context.canScan) return blocked(state, AcquisitionBlock.PermissionMissing)
        if (!context.bluetoothOn) return blocked(state, AcquisitionBlock.BluetoothUnavailable)
        val connected = state.phase is AcquisitionPhase.Connected
        if (connected && !event.force) return AcquisitionOutcome(state)

        val effects = buildList {
            // Some devices need a stop before a start or the scan fails silently.
            add(AcquisitionEffect.StopScan)
            state.address?.let {
                // A live connection is hung up on, not just let go of; anything in flight has
                // nothing to hang up.
                if (connected) {
                    add(AcquisitionEffect.DisconnectAndCloseGatt(it))
                    add(AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED))
                } else {
                    add(AcquisitionEffect.CloseGatt(it))
                }
            }
            add(AcquisitionEffect.StartScan)
        }
        return AcquisitionOutcome(
            AcquisitionState(
                phase = AcquisitionPhase.Scanning(endsAt = context.now + SCAN_TIMEOUT_MS),
                // A fresh scan is the only thing that clears what an earlier one found.
                scanned = emptyList(),
            ),
            effects,
        )
    }

    private fun connectRequested(
        state: AcquisitionState,
        event: AcquisitionEvent.ConnectRequested,
        context: AcquisitionContext,
    ): AcquisitionOutcome {
        if (!context.canConnect) return blocked(state, AcquisitionBlock.PermissionMissing)
        if (!context.bluetoothOn) return blocked(state, AcquisitionBlock.BluetoothUnavailable)

        val effects = buildList {
            add(AcquisitionEffect.StopScan)
            state.address?.let { add(AcquisitionEffect.CloseGatt(it)) }
            add(AcquisitionEffect.ConnectGatt(event.address))
        }
        return AcquisitionOutcome(
            state.copy(
                phase = AcquisitionPhase.Connecting(
                    address = event.address,
                    name = event.name ?: event.address,
                    promoteOnVerify = event.makeActive,
                    // A fresh connect is a fresh chase: the give-up cap starts over.
                    attempt = 0,
                    nextDelayMs = FIRST_RETRY_DELAY_MS,
                ),
            ),
            effects,
        )
    }

    private fun strapSeen(
        state: AcquisitionState,
        event: AcquisitionEvent.StrapSeen,
    ): AcquisitionOutcome {
        if (state.phase !is AcquisitionPhase.Scanning) return AcquisitionOutcome(state)
        // A Strap with no name is nothing the runner could pick out of a list.
        val name = event.name ?: return AcquisitionOutcome(state)
        if (state.scanned.any { it.address == event.address }) return AcquisitionOutcome(state)
        return AcquisitionOutcome(
            state.copy(scanned = state.scanned + ScannedStrap(event.address, name)),
        )
    }

    /**
     * A scan failure is only news while scanning. Reported later it would overwrite a genuinely
     * in-flight connect the runner started by tapping a Strap the scan had already found.
     */
    private fun scanFailed(
        state: AcquisitionState,
        event: AcquisitionEvent.ScanFailed,
    ): AcquisitionOutcome {
        if (state.phase !is AcquisitionPhase.Scanning) return AcquisitionOutcome(state)
        return blocked(state, AcquisitionBlock.ScanFailed(event.errorCode))
    }

    private fun forgetRequested(
        state: AcquisitionState,
        event: AcquisitionEvent.ForgetRequested,
    ): AcquisitionOutcome {
        // Narrower than a disconnect on purpose: it touches only the Acquisition, never the Run.
        // Forgetting a Strap mid-Run behaves like a plain dropout — a sensor going away never ends
        // a Run (#110).
        if (state.address != event.address) return AcquisitionOutcome(state)
        return AcquisitionOutcome(
            state.copy(phase = AcquisitionPhase.Idle),
            listOf(
                AcquisitionEffect.DisconnectAndCloseGatt(event.address),
                AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED),
            ),
        )
    }

    /**
     * Let the Strap go, whatever the Acquisition was doing — which is why the scan is stopped here
     * too. This is what "Stop Workout" means once the Run itself is over, and a scan left running
     * behind it would keep the Acquisition in flight and the Promotion with it.
     */
    private fun disconnectRequested(state: AcquisitionState): AcquisitionOutcome {
        val effects = buildList {
            if (state.phase is AcquisitionPhase.Scanning) add(AcquisitionEffect.StopScan)
            state.address?.let { add(AcquisitionEffect.DisconnectAndCloseGatt(it)) }
            add(AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED))
        }
        return AcquisitionOutcome(state.copy(phase = AcquisitionPhase.Idle), effects)
    }

    /**
     * A GATT says it is connected.
     *
     * If it is not the Strap being chased it was abandoned by a forget, a fresh scan, or a
     * superseding connect — publishing from it would keep feeding heart rate from a Strap the
     * runner just replaced. Close it and say nothing.
     */
    private fun gattConnected(
        state: AcquisitionState,
        event: AcquisitionEvent.GattConnected,
    ): AcquisitionOutcome {
        if (state.address != event.address) {
            return AcquisitionOutcome(state, listOf(AcquisitionEffect.CloseGatt(event.address)))
        }
        val phase = state.phase
        val name = when (phase) {
            is AcquisitionPhase.Connecting -> phase.name
            is AcquisitionPhase.Retrying -> phase.name
            is AcquisitionPhase.Connected -> phase.name
            else -> event.address
        }
        val promoteOnVerify = when (phase) {
            is AcquisitionPhase.Connecting -> phase.promoteOnVerify
            is AcquisitionPhase.Retrying -> phase.promoteOnVerify
            is AcquisitionPhase.Connected -> phase.promoteOnVerify
            else -> false
        }
        return AcquisitionOutcome(
            state.copy(
                phase = AcquisitionPhase.Connected(event.address, name, promoteOnVerify),
            ),
            // Connecting reports the sensor and nothing else. It never starts a Run and never opens
            // a database row — START owns that (#110).
            listOf(AcquisitionEffect.DiscoverServices(event.address)),
        )
    }

    private fun gattDisconnected(
        state: AcquisitionState,
        event: AcquisitionEvent.GattDisconnected,
        context: AcquisitionContext,
    ): AcquisitionOutcome {
        // Not the Strap being chased — a late report from one already let go. Close it and stop.
        // The chase it belonged to already told the Run what it needed to know.
        if (state.address != event.address) {
            return AcquisitionOutcome(state, listOf(AcquisitionEffect.CloseGatt(event.address)))
        }
        val phase = state.phase
        val name = when (phase) {
            is AcquisitionPhase.Connecting -> phase.name
            is AcquisitionPhase.Connected -> phase.name
            is AcquisitionPhase.Retrying -> phase.name
            else -> event.address
        }
        val promoteOnVerify = when (phase) {
            is AcquisitionPhase.Connecting -> phase.promoteOnVerify
            is AcquisitionPhase.Connected -> phase.promoteOnVerify
            is AcquisitionPhase.Retrying -> phase.promoteOnVerify
            else -> false
        }
        // A connection that is lost starts its backoff over; one that never landed carries on
        // where it was, which is what makes the give-up cap reachable at all.
        val attempt = when (phase) {
            is AcquisitionPhase.Connecting -> phase.attempt
            is AcquisitionPhase.Retrying -> phase.attempt
            else -> 0
        }
        val nextDelayMs = when (phase) {
            is AcquisitionPhase.Connecting -> phase.nextDelayMs
            is AcquisitionPhase.Retrying -> phase.nextDelayMs
            else -> FIRST_RETRY_DELAY_MS
        }

        return AcquisitionOutcome(
            state.copy(
                phase = nextChase(event.address, name, promoteOnVerify, attempt, nextDelayMs, context),
            ),
            listOf(
                AcquisitionEffect.TellRunStrapLost(LOST_RETRYING),
                AcquisitionEffect.CloseGatt(event.address),
            ),
        )
    }

    /**
     * Wait and try again, or stop.
     *
     * The cap is read before the attempt is counted, so three retries are spent — at three, six and
     * twelve seconds — and the fourth failure is the one that gives up.
     */
    private fun nextChase(
        address: String,
        name: String,
        promoteOnVerify: Boolean,
        attempt: Int,
        nextDelayMs: Long,
        context: AcquisitionContext,
    ): AcquisitionPhase {
        if (!context.runIsLive && attempt >= PRE_RUN_MAX_RETRIES) return AcquisitionPhase.GaveUp
        return AcquisitionPhase.Retrying(
            address = address,
            name = name,
            promoteOnVerify = promoteOnVerify,
            attempt = attempt + 1,
            dueAt = context.now + nextDelayMs,
            announcedDelayMs = nextDelayMs,
            nextDelayMs = (nextDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS),
        )
    }

    /**
     * The Strap's services came back.
     *
     * Persisting is for the Strap still being chased. A closed GATT's discovery has no business
     * saving anything — even a save that does not promote would re-add a Strap just forgotten.
     */
    private fun servicesDiscovered(
        state: AcquisitionState,
        event: AcquisitionEvent.ServicesDiscovered,
        context: AcquisitionContext,
    ): AcquisitionOutcome {
        if (!event.hasHeartRateService) return AcquisitionOutcome(state)
        if (state.address != event.address) return AcquisitionOutcome(state)
        val phase = state.phase as? AcquisitionPhase.Connected ?: return AcquisitionOutcome(state)

        val effects = buildList {
            add(AcquisitionEffect.SaveStrap(event.address, event.name, phase.promoteOnVerify))
            if (context.canConnect) add(AcquisitionEffect.SubscribeToHeartRate(event.address))
        }
        // Spent: a promotion is consumed by the verify it was waiting for, once.
        return AcquisitionOutcome(
            state.copy(phase = phase.copy(promoteOnVerify = false)),
            effects,
        )
    }

    private fun tick(state: AcquisitionState, context: AcquisitionContext): AcquisitionOutcome =
        when (val phase = state.phase) {
            is AcquisitionPhase.Scanning ->
                if (context.now < phase.endsAt) {
                    AcquisitionOutcome(state)
                } else {
                    // Nothing was chosen. Stop, and leave what was found on screen to be tapped.
                    AcquisitionOutcome(
                        state.copy(phase = AcquisitionPhase.Idle),
                        listOf(AcquisitionEffect.StopScan),
                    )
                }

            is AcquisitionPhase.Retrying ->
                if (context.now < phase.dueAt) {
                    AcquisitionOutcome(state)
                } else if (!context.canConnect) {
                    // Permission first, matching every other rule here, and for a reason: the
                    // adapter's own state is behind this permission, so without it "Bluetooth is
                    // off" is not something we can know — only something we would be guessing.
                    // The runner is told the thing they can act on.
                    blocked(state, AcquisitionBlock.PermissionMissing)
                } else if (!context.bluetoothOn) {
                    // Bluetooth went off mid-backoff. This used to be the one branch that reached
                    // nothing at all: the retry silently failed to fire and the status sat on
                    // "Reconnecting in Ns..." forever, which by ADR 0001 is a Promotion nobody
                    // releases. Every branch here must reach a phase that is not in flight.
                    blocked(state, AcquisitionBlock.BluetoothUnavailable)
                } else {
                    AcquisitionOutcome(
                        state.copy(
                            phase = AcquisitionPhase.Connecting(
                                address = phase.address,
                                name = phase.name,
                                promoteOnVerify = phase.promoteOnVerify,
                                attempt = phase.attempt,
                                nextDelayMs = phase.nextDelayMs,
                            ),
                        ),
                        listOf(AcquisitionEffect.ConnectGatt(phase.address)),
                    )
                }

            else -> AcquisitionOutcome(state)
        }

    /** Blocking ends the Acquisition but keeps what a scan already found. */
    /**
     * Stop, and say why.
     *
     * Whatever was in flight is stopped on the way out. Blocked is terminal and remembers no
     * address — the pulse that would tick again stops with it, and Forget and Disconnect both
     * match on an address this phase no longer has — so nothing else would ever come along to
     * stop the platform scan or let the GATT go. Left behind, that handle would still be the
     * map's entry for its address and its readings would still pass the identity check.
     */
    private fun blocked(state: AcquisitionState, reason: AcquisitionBlock): AcquisitionOutcome =
        AcquisitionOutcome(
            state.copy(phase = AcquisitionPhase.Blocked(reason)),
            buildList {
                if (state.phase is AcquisitionPhase.Scanning) add(AcquisitionEffect.StopScan)
                state.address?.let {
                    // Blocked is usually a permission or adapter that has gone, so hanging up may
                    // not be possible; the effect asks anyway and the service does what it may.
                    add(AcquisitionEffect.DisconnectAndCloseGatt(it))
                }
                if (state.phase is AcquisitionPhase.Connected) {
                    add(AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED))
                }
            },
        )
}
