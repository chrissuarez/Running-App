/**
 * Reading the Bluetooth Heart Rate Measurement characteristic — the one place a beat from a Strap
 * enters the app, and so the one place that judges whether it is a heart rate at all (#326).
 */
package com.example.runningapp

/**
 * The beat in one packet, or null where the packet holds none this app will believe.
 *
 * Null is the whole answer to an unusable reading: it is dropped rather than clamped to the bound,
 * because a clamped beat is a measurement the Strap never took, and rather than published as a
 * zero, because a zero would join the smoothed average as if a heart had beaten that slowly
 * (ADR 0011). A dropped packet is simply a packet that did not arrive, which is a thing the Run
 * already knows how to live with.
 *
 * The first byte's low bit says whether the beat is one byte or two. A two-byte beat is genuinely
 * read as two bytes, so 300 is 300 and not a truncated 44 — and then [HIGHEST_BELIEVABLE_BPM]
 * judges it exactly as it judges a one-byte beat, because the bound is about a heart rate rather
 * than about a byte.
 */
fun bpmFromHeartRateMeasurement(packet: ByteArray): Int? {
    if (packet.isEmpty()) return null
    val isSixteenBit = (packet[0].toInt() and 0x01) != 0
    val bpm = if (isSixteenBit) {
        if (packet.size < 3) return null
        ((packet[2].toInt() and 0xFF) shl 8) + (packet[1].toInt() and 0xFF)
    } else {
        if (packet.size < 2) return null
        packet[1].toInt() and 0xFF
    }
    return bpm.takeIf { it in 1..HIGHEST_BELIEVABLE_BPM }
}
