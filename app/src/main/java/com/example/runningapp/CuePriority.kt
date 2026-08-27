package com.example.runningapp

/**
 * How urgent a spoken cue is, and so where it sits in the queue (#53).
 *
 * Declared highest first: a cue is spoken before every cue below its level that is still waiting,
 * and after everything at or above it — first in, first out within a level. Nothing overtakes by
 * interrupting; whatever is being said always finishes.
 *
 * The order is #102's question, answered in #53's brief:
 *
 * - [NAVIGATION] — where to go: leaving the course a routed Run set out on, and rejoining it (#58).
 *   Above every instruction because a runner going the wrong way covers more wrong ground for as
 *   long as anything else is being said.
 * - [INSTRUCTION] — what to do now: Interval step cues, Phase transitions, auto-pause and resume.
 *   Top of the order because an instruction that arrives late is wrong, not merely less useful.
 * - [COACHING] — advice on effort, including the return-to-target cue. Above information because it
 *   is the cue the runner acts on.
 * - [INFORMATION] — things worth knowing: Split announcements, the halfway turnaround, and the
 *   target-reached cue. Late costs nothing.
 */
enum class CuePriority {
    NAVIGATION,
    INSTRUCTION,
    COACHING,
    INFORMATION,
}
