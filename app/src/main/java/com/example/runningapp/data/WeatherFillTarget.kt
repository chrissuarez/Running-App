package com.example.runningapp.data

/**
 * One Run still owed the weather it was run in, and the three things a fetch needs to ask for it
 * (#81).
 *
 * A projection rather than the Run itself, because the read behind it does not return a Run: a
 * position is taken from the row where the row has one and from the Run's track where it does not
 * (see [RUNS_OWED_WEATHER_SQL]), and a [RunnerSession] handed back with a position it does not
 * actually store would be a Run the app disagrees with itself about. What travels here is a
 * *question* — where and when to ask — and nothing else about the Run is any of the pass's business.
 *
 * [latitude] and [longitude] are not null because the read only returns rows that have both.
 */
data class WeatherFillTarget(
    val sessionId: Long,
    val startTime: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Every Run still owed its weather, newest first — the whole work list of
 * [SessionRepository.backfillWeather], named so a test can put the real statement to a real SQLite
 * database ([RECORD_EFFORTS_SQL]'s reason exactly).
 *
 * **Why a Run's start position is not enough on its own.** `startLatitude` arrived with the weather
 * columns themselves at v11, so *every* Run recorded before that upgrade has a null there — which is
 * the bulk of the history this ticket exists to fill, and the reason the retry shipped with #79
 * (which asked only for rows carrying a start position) would have walked straight past it. Those
 * Runs are not positionless: v12 folded their old `hr_samples` breadcrumbs into `track_points`, so
 * their first fix is on record. This read takes it.
 *
 * **The pair is taken from one place or from the other, never one of each.** The first fix is
 * found once and joined to, so "the position this Run started from" is defined in a single place
 * that both coordinates read. A latitude off the row
 * and a longitude off the track would name a place the runner has never been, and would do it
 * silently — the fetch would come back with real weather for the wrong ground. Nothing writes half a
 * start position today (both come off one `Location`), which is exactly why the guard is written
 * here rather than trusted to stay true elsewhere.
 *
 * **Newest first**, because a pass over a long history is minutes of fetching and can be stopped at
 * any point in them. Whatever is filled by the time the runner opens a Run should be the Runs they
 * are likeliest to open.
 *
 * The filters are the three the ticket states: outdoor Runs only, finished Runs only, and only
 * where no weather is stored. That last one is the whole of "a completed run is never re-fetched" —
 * the list is worked out afresh at every launch, so a Run filled by an earlier pass is simply not on
 * it, and a pass killed halfway comes back to the remainder.
 */
const val RUNS_OWED_WEATHER_SQL: String =
    """
        SELECT * FROM (
            SELECT s.id AS sessionId,
                   s.startTime AS startTime,
                   CASE WHEN s.startLatitude IS NOT NULL AND s.startLongitude IS NOT NULL
                        THEN s.startLatitude ELSE f.latitude END AS latitude,
                   CASE WHEN s.startLatitude IS NOT NULL AND s.startLongitude IS NOT NULL
                        THEN s.startLongitude ELSE f.longitude END AS longitude
            FROM sessions s
            LEFT JOIN track_points f ON f.id = (
                SELECT t.id FROM track_points t
                WHERE t.sessionId = s.id
                ORDER BY t.timestampMillis ASC, t.id ASC
                LIMIT 1
            )
            WHERE s.runMode = 'outdoor'
              AND s.endTime > 0
              AND s.weatherTempC IS NULL
        )
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY startTime DESC
    """

/**
 * How long [SessionRepository.backfillWeather] waits between one Run's fetch and the next (#81).
 *
 * Open-Meteo is free and asks for no key, and the whole of a long history is hundreds of requests
 * that would otherwise leave the phone as fast as the network can carry them. A quarter of a second
 * apart is about four a second — far under anything the service objects to, and slow enough that a
 * burst is never mistaken for one.
 *
 * The cost of getting this wrong only ever lands one way. A refused request is not an error the
 * runner sees; it comes back as no weather, the Run stays on the work list, and the next launch
 * makes the same refused burst again. Paying a minute or two of waiting, once, on a pass nobody is
 * watching, buys a history that fills.
 */
const val WEATHER_FETCH_GAP_MILLIS: Long = 250L
