package com.example.runningapp.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words on the live map's badge (#57). */
class CourseRemainingLabelTest {

    @Test
    fun `a Run following no course says nothing`() {
        assertNull(courseRemainingLabel(null))
    }

    @Test
    fun `kilometres while there are kilometres left`() {
        assertEquals("2.50 km to go", courseRemainingLabel(2500.0))
        assertEquals("10.00 km to go", courseRemainingLabel(9999.0))
    }

    @Test
    fun `metres once there is less than a kilometre left`() {
        assertEquals("840 m to go", courseRemainingLabel(842.0))
        assertEquals("0 m to go", courseRemainingLabel(0.0))
    }

    @Test
    fun `the last of the kilometres never reads as a thousand metres`() {
        assertEquals("1.00 km to go", courseRemainingLabel(998.0))
        assertEquals("990 m to go", courseRemainingLabel(994.0))
    }

    @Test
    fun `the figure is rounded to ten metres, so it does not flicker every second`() {
        assertEquals("120 m to go", courseRemainingLabel(123.0))
        assertEquals("130 m to go", courseRemainingLabel(126.0))
    }

    @Test
    fun `a runner past the end of the course has nothing left, never less`() {
        assertEquals("0 m to go", courseRemainingLabel(-40.0))
    }
}
