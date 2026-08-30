package com.example.runningapp.training

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The Monday of the week this day falls in.
 *
 * Weeks start Monday, the convention every training week in this app and most others runs on — a
 * runner's "big weekend" is the end of a week, not the start of one. One home for it, because three
 * things ask it — the weekly bars ([weeklyVolumeOf]), a weekly Goal ([GoalPeriod.startOn]) and a
 * Stage's training record ([stageTrainingRecordOf]) — and a week that began on a different day in
 * one of them would put the same Run in two different weeks on two screens.
 */
fun LocalDate.mondayOfWeek(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * Every week from [first]'s week through [last]'s week, oldest first and inclusive of both.
 *
 * The walk that leaves no gap. Both readers of it — the weekly bars and a Stage's training record —
 * have to place a week nobody ran in, and both would be wrong in the same way without it: the bars
 * are drawn side by side with nothing but their order to place them in time, so a fortnight off
 * would close up and read as two hard weeks in a row, and a missing week in a training record is
 * exactly the gap a requirement about *consistent* training is asking after.
 *
 * Empty when [last] falls before [first]'s week, which is a range with nothing in it rather than an
 * error — the callers each decide what to do with no weeks at all.
 */
fun weeksFrom(first: LocalDate, last: LocalDate): List<LocalDate> {
    val lastWeek = last.mondayOfWeek()
    val weeks = mutableListOf<LocalDate>()
    var week = first.mondayOfWeek()
    while (!week.isAfter(lastWeek)) {
        weeks += week
        week = week.plusWeeks(1)
    }
    return weeks
}
