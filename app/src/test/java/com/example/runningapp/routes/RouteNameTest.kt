package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteNameTest {

    @Test
    fun takesTheNameTheFileGaveItself() {
        assertEquals(
            "Regent's Park loop",
            routeName(fileSuggested = "Regent's Park loop", fileNamed = "download-3.gpx"),
        )
    }

    @Test
    fun fallsBackToWhatTheFileIsCalledOnDisk() {
        assertEquals(
            "regents-park-loop",
            routeName(fileSuggested = null, fileNamed = "regents-park-loop.gpx"),
        )
    }

    /** A dot in the middle of a name is part of it; only the extension comes off. */
    @Test
    fun takesOffOnlyTheExtension() {
        assertEquals("10.5k loop", routeName(fileSuggested = null, fileNamed = "10.5k loop.gpx"))
        assertEquals("no extension", routeName(fileSuggested = null, fileNamed = "no extension"))
    }

    @Test
    fun standsInWhenNothingNamesTheFile() {
        assertEquals("Imported route", routeName(fileSuggested = null, fileNamed = null))
    }

    /** Blank is not a name: an untitled Route would leave a row with nothing to tap on. */
    @Test
    fun fallsThroughAnythingBlank() {
        assertEquals("loop", routeName(fileSuggested = "   ", fileNamed = "loop.gpx"))
        assertEquals("Imported route", routeName(fileSuggested = "", fileNamed = "  .gpx"))
        assertEquals("Imported route", routeName(fileSuggested = null, fileNamed = ".gpx"))
    }

    @Test
    fun trimsTheWhitespaceAroundARealName() {
        assertEquals("Park loop", routeName(fileSuggested = "\n  Park loop \n", fileNamed = null))
    }
}
