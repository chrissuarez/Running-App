package com.example.runningapp.training

import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.distanceLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The moment a runner finished a whole Plan: they cleared the last Stage's Requirement, and there
 * was no Stage after it (#294).
 *
 * **Recorded, never derived.** Written at the instant the rule that answers a Requirement written
 * in numbers grants on a Plan's last Stage
 * ([com.example.runningapp.data.SessionRepository]), and never
 * worked out from history afterwards. A pass that read "the last Stage's bar is beaten somewhere in
 * history, so the plan is complete" would hand the runner the end of their plan retroactively, on
 * evidence recorded under other rules — the one act
 * [ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md) and #293
 * refuse. #293's line says a beaten bar out loud; it does not grant it, and neither does this.
 *
 * **Once, and never taken back.** A later Run that clears the bar again does not move [seconds] or
 * [completedOnEpochDay] and does not congratulate the runner a second time: this records the day the
 * plan was finished, not the runner's best — `bestEffortsOf` and the record book already own that.
 * Deleting the Run afterwards does not un-complete the plan, exactly as deleting it does not
 * un-graduate a Stage.
 *
 * The runner keeps their last Stage, its Workouts and their standing Prescription; the only thing
 * that changes is that the screen stops calling that Stage something to achieve.
 */
data class PlanCompletion(
    /**
     * Which Plan was finished. Kept so that switching to another Plan and back cannot make one
     * Plan's completion read as a claim about a different one.
     *
     * Storage holds one of these, not one per Plan, because exactly one Plan can be finished: a
     * completion is granted only by a Requirement written in numbers, and the app ships one Plan
     * that has one. A second such Plan would need a completion kept per Plan — this slot would hand
     * the runner the end of the second plan by taking away the end of the first.
     */
    val planId: String,
    /**
     * The day the plan was finished, as a local calendar day.
     *
     * The day of the *Run* that finished it, not the day the write happened — they are the same
     * afternoon except when they are not, and the fact being recorded is about the Run.
     *
     * An epoch day rather than a timestamp, because what is being recorded is a day and nothing
     * finer: a moment would have to be read back through some zone, and the zone that mattered was
     * the one the runner was in when they ran.
     */
    val completedOnEpochDay: Long,
    /**
     * The effort that cleared the bar, in whole seconds — the runner's own time, not the bar it was
     * enough for. Whole seconds because that is what a Best Effort is ranked in.
     */
    val seconds: Int,
)

/**
 * What the completed Stage's card says in place of its Requirement (#294): *"Completed 14 August
 * 2026 — you ran 5 km in 24:52."*
 *
 * The whole sentence comes from the stored completion and the Stage's own Requirement. It asks
 * history nothing — the record book holds the runner's *best*, which is a different fact and one
 * that keeps moving, while this is the day the plan ended.
 *
 * The year is always printed, unlike [alreadyBeatenLine]'s day, which drops the current year as
 * noise. This is the one moment the plan existed to produce, and a card that reads "14 August" for
 * a plan finished two summers ago is quietly overstating how recently it happened.
 */
fun planCompleteLine(completion: PlanCompletion, requirement: BestEffortRequirement): String {
    val distance = requirement.distanceLabel
    val day = LocalDate.ofEpochDay(completion.completedOnEpochDay)
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    return "Completed $day — you ran $distance in ${asClock(completion.seconds.toDouble())}."
}
