package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RunModeTest {

    @Test
    fun `each mode reads back from the setting it writes`() {
        RunMode.entries.forEach { assertEquals(it, RunMode.ofSettingValue(it.settingValue)) }
    }

    @Test
    fun `a missing or unrecognised setting reads as treadmill`() {
        assertEquals(RunMode.TREADMILL, RunMode.ofSettingValue(null))
        assertEquals(RunMode.TREADMILL, RunMode.ofSettingValue(""))
        assertEquals(RunMode.TREADMILL, RunMode.ofSettingValue("Outdoor"))
        assertEquals(RunMode.TREADMILL, RunMode.ofSettingValue("garbage"))
    }
}
