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
for leaving it. A runner is in exactly one Stage at a time. Every Run writes
down the Stage it was run under, fixed when START is pressed, and a Stage's
requirement is answered only by the Runs recorded under that Stage — so one
Stage's work can never graduate the next, and a Run carrying no Stage answers
nothing (#234).
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

**Break**:
Any stretch of a Run the recording does not cover — a Pause or an Outage.
Nothing that reads the *shape* of a Run may join across one: no line is drawn
over it, no climb is banked across it, no window of pace or height reaches
through it. That is the whole of what a Break withholds; whether it carries
ground is a question about which kind of Break it is
([ADR 0010](docs/adr/0010-the-track-is-the-record-of-a-break.md)).
_Avoid_: dropout (a Strap drops out; a recording breaks), blackout

**Pause**:
A Break the Run wrote down — held down by the runner or reached automatically.
The Run's clock stops, GPS is torn down, and the runner was not running, so a
Pause carries no ground at all and no seconds the Run counted.
_Avoid_: stoppage, rest stop

**Outage**:
A Break nobody declared: the signal was lost while the Run carried on — a
tunnel, a stairwell, a built-up street. The runner did cover that ground, so an
Outage carries the straight line between the fixes either side of it, which is
never longer than the route they really took, and the seconds that line was
covered in when it was covered fast enough to be running
([ADR 0012](docs/adr/0012-an-outage-is-a-leg-like-any-other.md)). What it does
not carry is a route, which is why it is still a Break.
_Avoid_: signal loss, GPS drop

**Covered**:
How far a Run went: the ground it went over, or the Stated Distance where a
treadmill reported it. Measured one way by every path that measures it — the
live recorder, the rescue of an interrupted Run, and the Splits table alike — so
a Run's Splits total the distance printed above them
([ADR 0010](docs/adr/0010-the-track-is-the-record-of-a-break.md)).
_Avoid_: total distance, actual distance

**Stated Distance**:
How far a treadmill Run went: the number the machine's console showed, told to the app by the
runner. Stated rather than measured, exactly as a Max HR or a resting heart rate is — the app never
works it out and never guesses it, and a Run nobody stated one for has no distance at all rather
than a distance of zero. Every rule that counts distance counts a stated one
([ADR 0008](docs/adr/0008-a-stated-distance-is-a-real-distance.md)), save a Best Effort, which needs
a route to find a stretch inside.
_Avoid_: manual distance, entered distance, treadmill distance

**Effort Score**:
What a Run cost the runner, as one number: every second weighted by the Zone it was spent in — 1
through 5, and nothing at all below Zone 1, because idling is not training. Weighted second by
second and never off a Run's average heart rate, which is what lets a run/walk Run score its running
as running instead of having the walk breaks averaged into it. Banked when the Run finishes, and
absent rather than zero on a Run that recorded no heart rate at all. A Run finished before the Score
existed is scored afterwards from the beats it kept, so history carries the same number as today's
Runs; that pass re-derives its own work list from the Runs still missing a Score, which is what makes
it safe to stop half way through and to run again. A week's total is the sum of the Scores in it, so
a week holding a Run that wore no Strap is **measured in part**: the total is a floor under what was
run and never a ceiling. Every reader shown one is told the number is short — in its own words, but
never left to infer it, because unmarked it reads as a lighter week than the runner had (#247).
_Avoid_: suffer score, relative effort, training load; and on screen, the bare word "Effort", which
is already what the runner rates a Run out of ten. A Best Effort is a different thing again — a
stretch of a Run rather than a number about the whole of it.

**Fitness**:
What the runner has built: their Effort Scores averaged over the last 42 days,
with the recent days counting for most. Slow to move by design — six weeks of
training is what it takes to raise it, and a fortnight off is what it takes to
lose much of it.
_Avoid_: CTL, chronic load, form (Form is the difference between this and
Fatigue)

**Fatigue**:
What the runner is still carrying: the same Effort Scores over the last 7 days.
The same arithmetic as Fitness on a shorter memory, so a hard weekend shows up
here within days and in Fitness barely at all.
_Avoid_: ATL, acute load, tiredness

**Form**:
How fresh the runner is today: yesterday's Fitness less yesterday's Fatigue.
Yesterday's rather than today's, because freshness is a question asked in the
morning, before today's Run has cost anything. Above +10 is fresh, below −10 is
fatigued, and the band between is neutral.
_Avoid_: TSB, training stress balance, freshness as a name for it — "fresh" is
one of its three verdicts, and the number itself is Form

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
gate the Run waits on. What it says is averaged over five seconds before the
coach reasons about it, and that average is a fact about the Strap rather than
about who is listening — it keeps moving through a cool-down and with coaching
off ([ADR 0011](docs/adr/0011-the-smoothed-reading-belongs-to-the-strap.md)).
_Avoid_: device, monitor, HRM

**Acquisition**:
The attempt to get a Strap connected: scanning, connecting, or retrying. In
flight until the Strap is connected, given up on, or blocked. Those six are its
whole vocabulary — Scanning, Connecting, Connected, Retrying, Gave up, Blocked.
Scanning, Connecting and Retrying are the in-flight three, and being in flight is
what earns Promotion ([ADR 0007](docs/adr/0007-acquisition-is-a-rulebook-too.md)).
Gave up is not the same as idle: it is the end of a chase, and nothing starts
another from it.
_Avoid_: pairing, connecting (as a noun), chasing

**Promotion**:
The state of running in Android's foreground — persistent notification and wake
lock together. Earned by a live Run or an in-flight Acquisition.
_Avoid_: foreground state (Promotion is the whole of it, not the Android flag)

**Simulation**:
A developer mode that feeds fake heart rate in place of a Strap, so the app can
be exercised without hardware.
_Avoid_: demo mode, test mode, fake mode
