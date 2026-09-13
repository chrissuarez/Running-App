package com.example.runningapp.navigation

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavGraph

/**
 * The app's real back stack as a [PageStack] (#476). It reports and it pops; every decision about
 * which pages come off is made in `PageStack.kt`, where a JVM test can reach it.
 *
 * [NavController.currentBackStack] is re-published inside every pop, before the pop returns, so
 * [addresses] read straight after [takeOff] is already the stack that pop left.
 */
class NavControllerPageStack(private val navController: NavController) : PageStack {

    override val addresses: List<String>
        get() = navController.currentBackStack.value
            // The graph's own entry sits under every page and is not a page.
            .filterNot { it.destination is NavGraph }
            .map { entry ->
                fillAddress(entry.destination.route.orEmpty(), entry.arguments.asStrings())
            }

    override fun takeOff(count: Int) {
        repeat(count) { navController.popBackStack() }
    }
}

/** Each argument by name, as the text it takes in an address — a Run's id 9 as "9". */
private fun Bundle?.asStrings(): Map<String, String> {
    val bundle = this ?: return emptyMap()
    // `Bundle.get` is deprecated only in favour of the typed getters, and an address holds text,
    // numbers and names alike, so the untyped read is the one that fits.
    @Suppress("DEPRECATION")
    return bundle.keySet().associateWith { key -> bundle.get(key).toString() }
}
