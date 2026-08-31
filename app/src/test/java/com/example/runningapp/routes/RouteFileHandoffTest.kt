package com.example.runningapp.routes

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The rule these pin is the one the whole of #277 turns on — see [RouteFileLaunch].
 */
class RouteFileHandoffTest {

    private val file: Uri = mock()

    @Test
    fun `the app is opened first, and the file follows`() {
        assertEquals(
            listOf(RouteFileLaunch.Home, RouteFileLaunch.Handover(file)),
            routeFileHandoff(file),
        )
    }

    @Test
    fun `the launch that may create the task carries no file`() {
        val first = routeFileHandoff(file).first()

        assertNull("The intent Android keeps must carry no file", first.file)
        assertTrue(
            "Only the first launch may make a task",
            first.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `the file lands on the launch before it rather than making a task`() {
        val handover = routeFileHandoff(file).last()

        assertEquals(file, handover.file)
        assertTrue(
            "The file must be delivered onto the Home launch",
            handover.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
        )
        assertFalse(
            "A task rooted by the file is the bug this fixes",
            handover.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `the file's read is passed on with it`() {
        val handover = routeFileHandoff(file).last()

        assertTrue(
            "Without the grant the import refuses the file it was just handed",
            handover.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun `an intent with no file still opens the app`() {
        assertEquals(listOf(RouteFileLaunch.Home), routeFileHandoff(null))
    }
}
