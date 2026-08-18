package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementTest {

    /** A packet as the characteristic sends it: flags, then the beat, one byte or two. */
    private fun eightBit(bpm: Int) = byteArrayOf(0x00, bpm.toByte())

    private fun sixteenBit(bpm: Int) =
        byteArrayOf(0x01, (bpm and 0xFF).toByte(), ((bpm shr 8) and 0xFF).toByte())

    @Test
    fun `an ordinary beat reads the same on either width`() {
        assertEquals(150, bpmFromHeartRateMeasurement(eightBit(150)))
        assertEquals(150, bpmFromHeartRateMeasurement(sixteenBit(150)))
        assertEquals(60, bpmFromHeartRateMeasurement(eightBit(60)))
    }

    @Test
    fun `a two-byte beat is read as two bytes, up to the bound`() {
        // The whole reason the flag exists: 300 is not a truncated 44.
        assertEquals(HIGHEST_BELIEVABLE_BPM, bpmFromHeartRateMeasurement(sixteenBit(HIGHEST_BELIEVABLE_BPM)))
    }

    @Test
    fun `a beat no heart can hold is no reading at all`() {
        assertNull(bpmFromHeartRateMeasurement(sixteenBit(HIGHEST_BELIEVABLE_BPM + 1)))
        assertNull(bpmFromHeartRateMeasurement(sixteenBit(300)))
        assertNull(bpmFromHeartRateMeasurement(sixteenBit(65535)))
        // The bound is about a heart rate, not about a byte, so the one-byte path answers to it
        // too — and 255 is the very value FIT would have written to mean "no heart rate" (#326).
        assertNull(bpmFromHeartRateMeasurement(eightBit(255)))
    }

    @Test
    fun `no beat the parse keeps can be mistaken for FIT's marker`() {
        // ADR 0017: a Run and its Export must say the same thing. FIT's heart_rate is a uint8
        // whose 255 means "none", so nothing the app records may ever be 255 or above.
        for (bpm in 0..65535) {
            val read = bpmFromHeartRateMeasurement(sixteenBit(bpm)) ?: continue
            assertEquals(bpm, read)
            if (read >= 255) throw AssertionError("kept $read, which FIT cannot say")
        }
    }

    @Test
    fun `a packet with no beat in it is no reading`() {
        assertNull(bpmFromHeartRateMeasurement(byteArrayOf()))
        assertNull(bpmFromHeartRateMeasurement(byteArrayOf(0x00)))
        assertNull(bpmFromHeartRateMeasurement(byteArrayOf(0x01, 0x2C)))
        // A Strap reporting nothing reports it as a zero, which is not a heart rate either.
        assertNull(bpmFromHeartRateMeasurement(eightBit(0)))
    }
}
