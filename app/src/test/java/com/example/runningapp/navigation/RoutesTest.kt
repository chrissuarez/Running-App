package com.example.runningapp.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `sessionDetail builds a route that matches the detail route pattern`() {
        val expected = Routes.SESSION_DETAIL.replace("{${Routes.ARG_SESSION_ID}}", "42")
        assertEquals(expected, Routes.sessionDetail(42L))
    }

    @Test
    fun `all screen routes are distinct`() {
        val routes = listOf(
            Routes.MAIN,
            Routes.SETTINGS,
            Routes.MANAGE_DEVICES,
            Routes.HISTORY,
            Routes.SESSION_DETAIL,
            Routes.TRAINING_PLAN,
            Routes.MAP,
            Routes.PROGRESS
        )
        assertEquals(routes.size, routes.toSet().size)
    }
}
