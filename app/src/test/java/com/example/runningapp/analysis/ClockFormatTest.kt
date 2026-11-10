package com.example.runningapp.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {
    @Test
    fun `pads minutes and seconds to two digits`() {
        assertEquals("00:00", formatMinutesSeconds(0))
        assertEquals("00:09", formatMinutesSeconds(9))
        assertEquals("00:59", formatMinutesSeconds(59))
        assertEquals("01:00", formatMinutesSeconds(60))
        assertEquals("02:05", formatMinutesSeconds(125))
        assertEquals("59:59", formatMinutesSeconds(3599))
    }

    @Test
    fun `minutes never roll into hours`() {
        assertEquals("60:00", formatMinutesSeconds(3600))
        assertEquals("125:30", formatMinutesSeconds(7530))
    }

    @Test
    fun `negative reads as zero`() {
        assertEquals("00:00", formatMinutesSeconds(-1))
        assertEquals("00:00", formatMinutesSeconds(-4000))
    }
}
