package com.example.runningapp.ui

import com.example.runningapp.data.Route
import com.example.runningapp.routes.GpxRefusal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the Routes screen says (#54).
 *
 * Pure and outside the composables, the same bargain [restoreRefusalMessage] makes: a refusal is the
 * only thing a runner gets when an import fails, so the words are the feature and are worth pinning
 * in a test rather than reading off a phone.
 */

/** The line under a Route's name: how far it goes, and how much of that is climbing. */
fun routeRowSubtitle(route: Route): String =
    routeDistanceLabel(route.distanceMeters) + " · " + routeElevationLabel(route.elevationGainMeters)

/** Kilometres to two places, as every other distance in the app is written. */
fun routeDistanceLabel(distanceMeters: Double): String =
    String.format(Locale.UK, "%.2f km", distanceMeters / 1000.0)

/**
 * The climb, or a plain statement that the file did not say.
 *
 * Never a nought for the second case. A flat route and a file with no heights in it are different
 * things, and "0 m" would tell a runner the hill they are about to run up is not there.
 */
fun routeElevationLabel(elevationGainMeters: Double?): String =
    if (elevationGainMeters == null) "No elevation in file" else "${elevationGainMeters.roundToInt()} m up"

/**
 * Why a file could not become a Route, in words that say what to do next.
 *
 * Every one of them leaves the library exactly as it was — worth saying, because a runner who has
 * just been refused needs to know nothing was half-saved.
 */
fun gpxRefusalMessage(reason: GpxRefusal): String = when (reason) {
    GpxRefusal.NOT_GPX ->
        "That file isn't a GPX route. Look for a file ending in .gpx — the kind Strava, Garmin " +
            "Connect and Komoot export."
    GpxRefusal.NO_POINTS ->
        "That GPX has no route in it — no track and no waypoint list to follow. Try exporting it again."
    GpxRefusal.TOO_LARGE ->
        "That GPX is too big to keep as a route. Try exporting a shorter one, or one recorded less often."
    GpxRefusal.UNREADABLE ->
        "That GPX couldn't be read — it may be damaged or only partly downloaded. Nothing has been " +
            "added to your routes."
}

/** What the screen says the moment a Route lands, so an import is visibly an import. */
fun routeImportedMessage(name: String): String = "Saved “$name” to your routes."
