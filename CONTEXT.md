# Running App

An Android heart-rate coach: a Bluetooth chest strap feeds a live run, the app
coaches the runner through it by voice, and records it. This glossary fixes the
words the code and the issues should use for that.

## Language

**Run**:
One recorded outing, from the moment the runner presses START until it is
stopped or auto-stops. A live thing before it is a saved one: it holds the
clock, the Phase it is in, and everything counted so far.
_Avoid_: session, activity

**Plan**:
The whole schedule a runner is following: an ordered list of Stages, fixed in
the app rather than built by anyone.
_Avoid_: program, schedule

**Stage**:
One block of a Plan, holding the Workouts available in it and the requirement
for leaving it. A runner is in exactly one Stage at a time.
_Avoid_: phase (a Phase is a stretch of a single Run), level, block

**Workout**:
The planned shape a Run may follow — its intervals, durations, and targets —
taken from the training plan.
_Avoid_: session, plan (the Plan is the whole schedule of Workouts)

**Run Type**:
What kind of work a Workout is: Long, Easy, or Quality. The Stage offers one of
each and the runner chooses; it is what makes two Workouts different in kind
rather than only in length.
_Avoid_: category, workout type, session type

**Pick**:
Which of the Stage's Workouts today's Run follows. Made fresh each time and
worth only as long as the screen that made it — the Plan is a menu, so there is
no place in it to keep ([ADR 0005](docs/adr/0005-the-plan-is-a-menu-not-a-cursor.md)).
_Avoid_: choice, selection, current workout

**Phase**:
Which of a Run's three stretches it is in: warm-up, main, or cool-down. Every
Run has all three in that order; only the main one is open-ended.
_Avoid_: stage (a Stage is a block of the training Plan), segment

**Interval**:
One run or walk stretch inside the main Phase, repeated as the Workout
prescribes. Only a Run following a Workout has any.
_Avoid_: rep, segment, split (a Split is a kilometre of distance)

**Trigger**:
The moment a Run's heart rate has sat outside its target band long enough for
the coach to speak. A record of where heart rate went, and never a verdict on
the runner: a Trigger says a line was crossed, not that anyone struggled,
stopped, or failed.
_Avoid_: breakdown, poor tolerance, strain, cap, HR event

**Reserve**:
The gap between the runner's stated Max HR and their stated resting heart rate —
the range every zone edge is a percentage of ([ADR 0004](docs/adr/0004-zones-from-heart-rate-reserve.md)).
Both numbers are measured and stated, never guessed; with no resting heart rate
stated the Reserve is the whole of Max HR, which is exactly the model that came
before.
_Avoid_: HRR, working heart rate, Karvonen

**Split**:
One completed kilometre of a Run, and the pace it was covered at. Measured by
distance, unlike an Interval, which is measured by time.
_Avoid_: lap, mile

**Prescription**:
What the AI coach writes after a Run: the intervals and target zone for the next
Workout of one Run Type, and nothing else. The coach holds one per Run Type —
three independent slots — so a Prescription applies only to its own kind of
session and can never land on another. Only the Long slot is ever written: the
coach decides whether to evaluate a Run by its Run Type, and adjusts the Long Run
alone ([ADR 0006](docs/adr/0006-the-coach-adjusts-the-long-run-only.md)). Dated,
replaceable, and never a setting — the coach prescribes work, it does not
configure the app.
_Avoid_: AI adjustments, AI intervals

**Strap**:
The Bluetooth heart-rate sensor worn on the chest. A sensor the Run reads, not a
gate the Run waits on.
_Avoid_: device, monitor, HRM

**Acquisition**:
The attempt to get a Strap connected: scanning, connecting, or retrying. In
flight until the Strap is connected, given up on, or blocked.
_Avoid_: pairing, connecting (as a noun), chasing

**Promotion**:
The state of running in Android's foreground — persistent notification and wake
lock together. Earned by a live Run or an in-flight Acquisition.
_Avoid_: foreground state (Promotion is the whole of it, not the Android flag)

**Simulation**:
A developer mode that feeds fake heart rate in place of a Strap, so the app can
be exercised without hardware.
_Avoid_: demo mode, test mode, fake mode
