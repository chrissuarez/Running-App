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

**Break**:
A stretch of a Run the recording does not cover. Three causes, one word: a
Pause, an Outage, or fixes too vague to trust. Nothing measured off the track
draws, climbs or joins across a Break — but the straight line between its ends
is ground the runner Covered, because a straight line is never longer than the
route they actually ran ([ADR 0007](docs/adr/0007-the-track-is-the-record-of-a-break.md)).
_Avoid_: gap, dropout, hole

**Pause**:
A Break the runner caused: the pause button, or auto-pause. The only Break that
carries no distance and never any Moving time, because the Run itself says it
happened rather than leaving it to be guessed from a gap. Written down on the
fix that resumed it.
_Avoid_: stop, rest (a rest is judged from speed; a Pause is recorded)

**Outage**:
A Break nobody asked for — the signal lost under trees, in a tunnel, in a
street of tall buildings. Judged like any other stretch of a Run: the straight
line over the time it took, Moving or resting by the same speed rule.
_Avoid_: dropout, signal loss, GPS gap

**Elapsed**:
The Run's own clock: the wall time it spanned, less its Pauses. What the
summary calls duration.
_Avoid_: duration (in prose about the three totals), total time

**Moving**:
Elapsed, less the spells the runner spent getting nowhere. The clock pace is
quoted over, judged leg by leg against a 30-minute-mile threshold rather than
declared by the runner.
_Avoid_: active time, running time

**Covered**:
The Run's distance: every accepted-to-accepted leg, including the straight lines
across its Breaks. One number, quoted by the summary, the Splits and the record
book alike — a Run's Splits total its Covered distance.
_Avoid_: distance (when the point is *which* distance), tracked distance

**Split**:
One completed kilometre of a Run, and the pace it was covered at. Measured by
distance, unlike an Interval, which is measured by time.
_Avoid_: lap, mile

**Best Effort**:
The fastest continuous stretch of a Run covering one of the record distances —
1 km, a mile, 5 km, 10 km, a half marathon — measured on the clock, so a walk
break inside it counts against it. Found anywhere in the Run, not at the Split
boundaries, so a fast middle section counts.
_Avoid_: PR, personal best (a Best Effort is only a claim until it places)

**Record**:
One of the seven things a Run can be the best at: the five distances, and the
longest Run by ground covered and by the clock. The book keeps the all-time top
three at each.
_Avoid_: PB, milestone, trophy

**Achievement**:
A Medal one Run holds at one Record — the only part of a Run's page that is
banked rather than worked out on read, because it is a fact about a Run relative
to every other one.
_Avoid_: award, badge

**Medal**:
Where an effort placed: gold, silver or bronze. There is no fourth place.
_Avoid_: rank, position

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
