package com.example.runningapp.floating

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any of this app's screens is in sight right now (#545).
 *
 * The floating coach hides while the app is open — the live screen already shows everything it
 * would — so the service needs to know. Counted from the activity's own onStart and onStop, which
 * bracket exactly the time it is visible, and counted rather than flagged so an overlap between two
 * starts (a configuration change, or a second instance) cannot leave the app wrongly "off screen".
 *
 * Process-wide because the service and the activity share a process and nothing else connects them
 * reliably: the service's bind callbacks are not called again on a re-bind.
 */
object AppOnScreen {
    private var visibleScreens = 0
    private val onScreen = MutableStateFlow(false)

    val isOnScreen: StateFlow<Boolean> = onScreen.asStateFlow()

    @MainThread
    fun screenStarted() = adjust(+1)

    @MainThread
    fun screenStopped() = adjust(-1)

    private fun adjust(by: Int) {
        visibleScreens = (visibleScreens + by).coerceAtLeast(0)
        onScreen.value = visibleScreens > 0
    }
}
