package com.example.runningapp.data

import com.example.runningapp.recording.geodesicDistanceMeters

/**
 * Speed at or above which Strava counts a runner as moving rather than resting: a 30-minute mile,
 * which is 1609.344 m in 1800 s. Published in Strava's running glossary — "the moving threshold is
 * anything faster than a 30-minute mile pace for running activities".
 *
 * Well above this app's own auto-pause standstill bar
 * ([com.example.runningapp.recording.SessionRecorder.STANDSTILL_SPEED_THRESHOLD_MPS], 0.3 m/s):
 * auto-pause asks "has the runner stopped?", this asks "is the runner still getting anywhere?", and
 * a slow shuffle answers those two differently on purpose.
 */
const val MOVING_SPEED_THRESHOLD_MPS = 1609.344 / 1800.0

/**
 * How long the runner must stay under [MOVING_SPEED_THRESHOLD_MPS] before that spell is taken out
 * as rest. Below this, a slow spell stays in: a single dropped stride or a GPS wobble is not a
 * rest, and stopping the clock for each one would flatter every pace in the app.
 *
 * Unlike [MOVING_SPEED_THRESHOLD_MPS], Strava does not publish this, so it is calibrated against
 * runs Strava and this app both measured. Measuring each run's own exported GPX at each window,
 * against what Strava made of the same file:
 *
 * ```
 *                 0s      1s      2s      3s      4s      5s     15s    Strava
 *   28 Jul     36:30   36:44   36:54   36:57   37:01   37:11   37:39     36:56
 *   26 Jul     21:40   21:50   21:58   22:01   22:05   22:05   22:13     21:59
 * ```
 *
 * Three seconds lands within two seconds of Strava on both, and is the only window that does.
 * Fifteen removes almost nothing, because this runner's rest is many short breaks rather than one
 * long stop; zero removes far too much, because a dropped stride is not a rest. Holding the window
 * at 3s and sweeping the speed threshold instead puts the best fit at 0.894 m/s — Strava's
 * published number, arrived at independently, which is the reason to trust the pair rather than
 * either alone.
 *
 * Two runs are two data points, and both are this one runner on this one phone. The number to
 * re-check against is pace rather than moving time, since pace is what either app puts on screen:
 * both runs match Strava's to the second there (8:10 and 8:58 /km).
 */
const val REST_SUSTAINED_MS = 3_000L

/**
 * How long the track may go unrecorded before the gap counts as an Outage — a stretch of the run
 * nothing witnessed — and the same number the GPX export draws its route break at
 * (`RunGpxTrack.ROUTE_BREAK_SECONDS`). Fixes arrive about a second apart, so twenty seconds sits
 * well above the gaps of a run in progress.
 *
 * What a gap this long buys is silence about the *route*, and only that: nothing is drawn or joined
 * across it. It does not decide whether the runner was moving, which is measured off the ground the
 * gap carries like any other leg's (#165,
 * [ADR 0012](docs/adr/0012-an-outage-is-a-leg-like-any-other.md)).
 * A Pause is the leg that decides otherwise, and it decides it by being written down rather than by
 * being long: a pause shorter than this leaves no gap worth noticing, and one longer would be
 * indistinguishable from lost signal. It is written down twice over — on the fix that resumed the
 * run ([TrackPoint.startsAfterPause]) and, for the runs recorded before that column existed, in the
 * run's own clock, which stops for a Pause and runs on through an Outage ([withinTheRunsClock]).
 */
const val TRACK_BREAK_MS = 20_000L

/**
 * A finished run's moving time, in seconds — the run's own clock minus the spells the runner spent
 * going nowhere (#163). See [measureTrack] for how each leg is judged.
 *
 * [clockSeconds] is the run's own clock ([RunnerSession.durationSeconds]), which is the last word on
 * how long the run ran: no measurement of it may come out longer, and an Outage the clock never
 * banked was a stop rather than lost signal ([withinTheRunsClock], #165).
 */
fun measureMovingTimeSeconds(points: List<TrackPoint>, clockSeconds: Long): Long =
    measureTrack(points).withinTheRunsClock(clockSeconds).movingSeconds
