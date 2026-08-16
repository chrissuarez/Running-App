package com.example.runningapp

import com.example.runningapp.data.RunnerSession
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The one door to what day a Run happened on, and what the clock on the wall said while it was
 * happening (#304).
 *
 * A Run's day is a fact about the Run, fixed the moment the runner set off, and the opposite of
 * **Today** — which is observed and never captured, because the day the *app* is in moves on its
 * own (#299). Nothing about a Run moves: the runner set off at half past seven in the evening
 * wherever they were standing, and they still did after they flew home. Re-reading `startTime` in
 * whichever zone the phone is in now is what makes a Run near midnight change the day it was run on
 * — and for a Plan Completion, whose day is recorded once and can never be re-earned, it makes it
 * change permanently.
 *
 * [ranAtUtcOffsetSeconds] is the Run's own stamp ([com.example.runningapp.data.RunnerSession]).
 * Null is "this Run never wrote one down" — every Run recorded before v32 — and those are read in
 * [fallbackZone], which is the behaviour they have always had. Every caller must therefore still
 * supply a zone; there is no reading of a Run's day that does not need one.
 *
 * The stored number is seconds and not hours because zones offset by three quarters of an hour
 * exist, and it is an offset rather than a zone id because the offset is the whole of what a day
 * boundary needs: a zone id would have to be resolved back through that year's daylight-saving
 * rules to say anything at all, and those rules are rewritten by governments after the fact.
 */
fun ranAt(
    startedAtMillis: Long,
    ranAtUtcOffsetSeconds: Int?,
    fallbackZone: ZoneId,
): ZonedDateTime =
    Instant.ofEpochMilli(startedAtMillis)
        .atZone(offsetOrNull(ranAtUtcOffsetSeconds) ?: fallbackZone)

/** The calendar day [ranAt] falls on — the day the runner would say they ran. */
fun ranOn(
    startedAtMillis: Long,
    ranAtUtcOffsetSeconds: Int?,
    fallbackZone: ZoneId,
): LocalDate = ranAt(startedAtMillis, ranAtUtcOffsetSeconds, fallbackZone).toLocalDate()

/**
 * What a Run starting at [atMillis] in [zone] should be stamped with — the offset in force at that
 * moment, not the zone's standard one, so a summer evening is not read back through a winter rule.
 */
fun utcOffsetSecondsAt(atMillis: Long, zone: ZoneId): Int =
    zone.rules.getOffset(Instant.ofEpochMilli(atMillis)).totalSeconds

/**
 * A stored offset is only ever as good as whatever wrote it, and one outside the range a
 * [ZoneOffset] can hold would throw where it is read — on the History list, in the middle of a
 * curve. Such a number says nothing about where the Run was, so it is treated as the absence it
 * is and the caller's zone answers instead.
 */
private fun offsetOrNull(seconds: Int?): ZoneOffset? {
    if (seconds == null) return null
    return try {
        ZoneOffset.ofTotalSeconds(seconds)
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * The calendar day this Run happened on, and the day the runner would say they ran — read off the
 * Run's own stamp where it has one, and off [fallbackZone] where it does not.
 *
 * Here rather than beside the entity so that one name answers the question everywhere: the
 * repository reads a bare pair off a projection and the screens read a whole row, and two functions
 * called [ranOn] in two packages would have every caller naming which one it meant.
 */
fun RunnerSession.ranOn(fallbackZone: ZoneId): LocalDate =
    ranOn(startTime, ranAtUtcOffsetSeconds, fallbackZone)

/** What the clock on the wall said when this Run set off — see [ranOn]. */
fun RunnerSession.ranAt(fallbackZone: ZoneId): ZonedDateTime =
    ranAt(startTime, ranAtUtcOffsetSeconds, fallbackZone)

/**
 * Whether [this] Run's day is further ahead of [today] than any runner's clock could put it (#304).
 *
 * The guard it replaces asked plainly whether the Run's day was after today, which was the whole
 * answer while both sides were read in the same zone. They are not any more: a Run's day is its own
 * and today is the phone's, so a runner who ran on Saturday evening in Sydney and landed in London
 * on Saturday morning holds a Run stamped a day ahead of the phone — and dropping it would take a
 * Run they really ran out of their week.
 *
 * One day of slack and no more, because no two clocks on earth are more than 26 hours apart. A
 * phone whose clock has genuinely slipped is out by months, which is what the guard is for.
 */
fun LocalDate.isBeyondAnyonesToday(today: LocalDate): Boolean =
    isAfter(today.plusDays(1))
