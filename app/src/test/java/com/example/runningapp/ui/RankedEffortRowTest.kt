package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a placed row in any league table says out loud (#481). */
class RankedEffortRowTest {

    @Test
    fun `a placed row names its metal, or the number it came in at`() {
        assertEquals(
            "Gold, 5 Jan 2026, 01:00, 5:00 /km",
            placedRowSpoken(place = 1, medal = Medal.GOLD, primary = "5 Jan 2026", secondary = "5:00 /km", trailing = "01:00"),
        )
        assertEquals(
            "Number 4, 5 Jan 2026, 1:00:00",
            placedRowSpoken(place = 4, medal = null, primary = "5 Jan 2026", secondary = null, trailing = "1:00:00"),
        )
    }
}
