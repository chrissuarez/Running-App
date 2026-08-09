package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteNameTest {

    @Test
    fun `takes the name the file gave itself`() {
        assertEquals(
            "Regent's Park loop",
            routeName(fileSuggested = "Regent's Park loop", fileNamed = "download-3.gpx"),
        )
    }

    @Test
    fun `falls back to what the file is called on disk`() {
        assertEquals(
            "regents-park-loop",
            routeName(fileSuggested = null, fileNamed = "regents-park-loop.gpx"),
        )
    }

    /** A dot in the middle of a name is part of it; only the extension comes off. */
    @Test
    fun `takes off only the extension`() {
        assertEquals("10.5k loop", routeName(fileSuggested = null, fileNamed = "10.5k loop.gpx"))
        assertEquals("no extension", routeName(fileSuggested = null, fileNamed = "no extension"))
    }

    @Test
    fun `stands in when nothing names the file`() {
        assertEquals("Imported route", routeName(fileSuggested = null, fileNamed = null))
    }

    /** Blank is not a name: an untitled Route would leave a row with nothing to tap on. */
    @Test
    fun `falls through anything blank`() {
        assertEquals("loop", routeName(fileSuggested = "   ", fileNamed = "loop.gpx"))
        assertEquals("Imported route", routeName(fileSuggested = "", fileNamed = "  .gpx"))
        assertEquals("Imported route", routeName(fileSuggested = null, fileNamed = ".gpx"))
    }

    @Test
    fun `trims the whitespace around a real name`() {
        assertEquals("Park loop", routeName(fileSuggested = "\n  Park loop \n", fileNamed = null))
    }
}
