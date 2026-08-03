package com.example.runningapp.run

/**
 * A Strap the scanner has seen: the two things anything outside Bluetooth ever needs of it.
 *
 * The scan callback hands back an `android.bluetooth.BluetoothDevice`, and the published state used
 * to carry those objects all the way to the device list on screen — where reading `.name` needs
 * BLUETOOTH_CONNECT, outside any check. The name is read once, inside the permission check that
 * covers it, and what travels is this.
 */
data class ScannedStrap(
    val address: String,
    val name: String,
)

/** Why an Acquisition cannot proceed. Each one is terminal: nothing is in flight behind it. */
sealed interface AcquisitionBlock {
    /** BLUETOOTH_SCAN or BLUETOOTH_CONNECT is not granted. */
    data object PermissionMissing : AcquisitionBlock

    /** No adapter — Bluetooth is off, or the phone has none. */
    data object BluetoothUnavailable : AcquisitionBlock

    /** The platform refused the scan itself. */
    data class ScanFailed(val errorCode: Int) : AcquisitionBlock
}

/**
 * Where an Acquisition has got to.
 *
 * `CONTEXT.md` already said this: an Acquisition is "in flight until the Strap is connected, given
 * up on, or blocked". Those are the three terminal phases below, and the code simply never had
 * them — it had a sentence, and eleven places read the sentence.
 */
sealed interface AcquisitionPhase {

    /** Nothing in flight and nothing connected. */
    data object Idle : AcquisitionPhase

    /**
     * Looking for Straps, until [endsAt].
     *
     * A scan has no natural end — nothing ever auto-connects from one — so an abandoned scan would
     * burn the scanner and the Promotion it earns indefinitely. The deadline lives here rather than
     * in a coroutine, which is what lets a scan that stops early simply stop: leaving this phase
     * takes the deadline with it, so there is no timer left over to decide it no longer counts.
     */
    data class Scanning(val endsAt: Long) : AcquisitionPhase

    /**
     * Chasing one Strap.
     *
     * [promoteOnVerify] rides along rather than sitting in a field of its own: only an explicit
     * Connect tap may promote a Strap to active, and only once its heart-rate service verifies. It
     * survives a retry — a tap that drops and reconnects is still that tap — and it is address-typed
     * by construction, since it can only ever be true of the Strap this phase names.
     */
    data class Connecting(
        val address: String,
        val name: String,
        val promoteOnVerify: Boolean,
        /** How many retries have been spent chasing this Strap. Zero on a fresh connect. */
        val attempt: Int,
        /** What the next backoff would be, doubling from [FIRST_RETRY_DELAY_MS]. */
        val nextDelayMs: Long,
    ) : AcquisitionPhase

    /**
     * Connected, and the Strap's readings are flowing.
     *
     * [promoteOnVerify] survives the hop from [Connecting] because the promotion it stands for is
     * spent later than the connection: only when the heart-rate service verifies. It falls to false
     * the moment it is spent, so one tap promotes once.
     */
    data class Connected(
        val address: String,
        val name: String,
        val promoteOnVerify: Boolean = false,
    ) : AcquisitionPhase

    /**
     * Waiting to try again, until [dueAt].
     *
     * [announcedDelayMs] is what the runner was told, kept so the status line says the same number
     * for the whole wait rather than counting down.
     */
    data class Retrying(
        val address: String,
        val name: String,
        val promoteOnVerify: Boolean,
        val attempt: Int,
        val dueAt: Long,
        val announcedDelayMs: Long,
        val nextDelayMs: Long,
    ) : AcquisitionPhase

    /**
     * Stopped chasing: the Strap could not be reached and no Run needs it.
     *
     * Deliberately not [Idle]. The record screen's auto-connect fires on Idle, so collapsing the
     * two would have it start the same doomed chase again the moment it gave up.
     */
    data object GaveUp : AcquisitionPhase

    /**
     * Cannot proceed. See [AcquisitionBlock].
     *
     * [interrupted] is the Strap this block stopped, if it stopped one — so the adapter coming back
     * mid-Run can resume the chase it took away (#224). It is deliberately **not**
     * [AcquisitionState.address], and the two must not be collapsed: `address` is what the
     * GATT-closing paths and the Forget and Disconnect identity checks match on, and a blocked
     * phase's GATT has already been closed on the way in. A non-null `address` here would have
     * Disconnect try to hang up a dead handle and a fresh scan try to close it a second time. This
     * is a memory, not a live connection.
     *
     * Null when there was nothing to interrupt — blocked while scanning, or from [Idle].
     */
    data class Blocked(
        val reason: AcquisitionBlock,
        val interrupted: InterruptedChase? = null,
    ) : AcquisitionPhase
}

/** The Strap a block stopped, kept only so [AcquisitionPhase.Blocked] can name it again later. */
data class InterruptedChase(
    val address: String,
    val name: String,
)

/**
 * The whole of an Acquisition: where it has got to, and what it has seen.
 *
 * [scanned] sits outside the phase on purpose. A scan that ends — timed out, or superseded by a
 * connect — leaves its discoveries on screen to be tapped; only starting a fresh scan clears them.
 */
data class AcquisitionState(
    val phase: AcquisitionPhase = AcquisitionPhase.Idle,
    val scanned: List<ScannedStrap> = emptyList(),
) {

    /**
     * Is this Acquisition in flight?
     *
     * Promotion depends on the answer (ADR 0001), and it used to be answered by searching the
     * status text for four words — one of which, "Connecting", was matched against a string built
     * by interpolating the Strap's own name. A wake lock was held or released on a substring match
     * against a device name.
     */
    val inFlight: Boolean
        get() = phase is AcquisitionPhase.Scanning ||
            phase is AcquisitionPhase.Connecting ||
            phase is AcquisitionPhase.Retrying

    /** The Strap being chased or held, if any. */
    val address: String?
        get() = when (val p = phase) {
            is AcquisitionPhase.Connecting -> p.address
            is AcquisitionPhase.Connected -> p.address
            is AcquisitionPhase.Retrying -> p.address
            else -> null
        }

    /**
     * Does this Acquisition already cover a Run about to start, so START should not begin another?
     *
     * Kicking off a fresh Acquisition mid-connect tears down the pending GATT and drops the Strap
     * the runner just chose. A bare scan deliberately does not count: nothing auto-connects from
     * one, so deferring to a scan would leave the whole Run strapless while the scanner runs.
     *
     * Being connected only counts when the connected Strap *is* the active one — Set Active writes
     * only the settings and leaves the old GATT up, so a START after switching Straps must
     * re-acquire the newly chosen one rather than record from the old.
     */
    fun coversRunStart(activeAddress: String?): Boolean = when (val p = phase) {
        is AcquisitionPhase.Connected -> activeAddress == null || p.address == activeAddress
        is AcquisitionPhase.Connecting -> true
        is AcquisitionPhase.Retrying -> true
        else -> false
    }

    /**
     * What the runner is told, and what a heart-rate row records.
     *
     * The phase is the truth; this is its sentence. It stays a string at this one edge because
     * `hr_samples.connectionState` has been storing one since the first run ever recorded, and a
     * typed column would make every old row disagree with every new one for no gain the runner can
     * see.
     *
     * Only `"Connected"` has ever actually reached a row, though — a second with no reading is
     * banked as no-data by [Run.bankSecond] and emits no row at all (#110, #115), so the outage
     * sentences below are written to the screen and to the Run, never to the database. Keep
     * `"Connected"` spelled exactly as it is; the rest are free to change wording.
     */
    val statusLine: String
        get() = when (val p = phase) {
            AcquisitionPhase.Idle -> "Disconnected"
            is AcquisitionPhase.Scanning -> "Scanning..."
            is AcquisitionPhase.Connecting -> "Connecting to ${p.name}..."
            is AcquisitionPhase.Connected -> "Connected"
            is AcquisitionPhase.Retrying -> "Reconnecting in ${p.announcedDelayMs / 1000}s..."
            AcquisitionPhase.GaveUp -> "Strap not found"
            is AcquisitionPhase.Blocked -> when (val r = p.reason) {
                AcquisitionBlock.PermissionMissing -> "Permission Missing"
                AcquisitionBlock.BluetoothUnavailable -> "Bluetooth Off/Unavailable"
                is AcquisitionBlock.ScanFailed -> "Scan Failed: ${r.errorCode}"
            }
        }
}
