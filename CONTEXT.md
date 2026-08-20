# Running App

An Android heart-rate coach: a Bluetooth chest strap feeds a live run, the app
coaches the runner through it by voice, and records it. This glossary fixes the
words the code and the issues should use for that.

## Language

**Run**:
One recorded outing, from the moment the runner presses START until it is
stopped or auto-stops. A live thing before it is a saved one: it holds the
clock, the Phase it is in, and everything counted so far. A Run can also end
without being stopped — the system taking the service out from under it while it
was still recording — and that is not a way for a Run to disappear: it is
finished there and then from the seconds it had already written down, and the
runner is told it happened (#309).
_Avoid_: session, activity — except inside the FIT Export, where `session` and
`activity` are the format's own names for its messages, exactly as `lap` is (see
**Split**). `FitActivity`, `RunFitActivity`, `SessionMesg` and `ActivityMesg` name
what is in the file; everything on this side of that boundary is still a Run.

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
nothing (#234). A Stage later in the Plan than the one the runner is in is
locked, and shown as such; the Stage they are in never is, and neither is one
they have left. That is worked out from where they stand rather than declared on
the Stage, so a graduation clears the padlock by itself (#301).
_Avoid_: phase (a Phase is a stretch of a single Run), level, block

**Stage Requirement**:
What a Stage asks for before it will be let go of. Always written in prose for
the runner to read, and sometimes also in numbers — a Best Effort at a record
distance, in a time. Where it is written in numbers the app measures it and
decides it, and the coach is fenced out of it entirely; where it holds a
judgement, such as "4 weeks of consistent Zone 2 training", the coach decides
([ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)).
A requirement written in numbers is answered by any finished Run that is not a
Walk, whichever kind of session it was — an Open Run included, because a time is
a time wherever it turned up. It is asked once the runner can no longer change
what the Run was: when the "How did that feel?" sheet closes, or straight away
for a Run no sheet was shown for, or at the next launch for a sheet nobody ever
answered. Never at STOP, because the Walk mark has not been given yet (#297).
Granted forwards only, never from a pass over
history, and never taken back (#290). Where history already holds a Run that
would clear the bar, the Stage card names it and says a new one would count —
a statement of what happened, never an offer, and nothing changes when it
appears (#293). On the last Stage of a Plan, answering it records a Plan
Completion rather than moving the runner anywhere (#294).
_Avoid_: graduation criteria, goal (a Goal is the runner's own distance or time
target), unlock condition

**Plan Completion**:
The runner finishing a whole Plan: they cleared the last Stage's Requirement and
there was no Stage after it. Recorded at the moment it is granted — the Plan, the
day, and the effort in seconds — and never worked out from history afterwards.
Granted once per Plan: a later Run clearing the bar again moves nothing and says
nothing, because this records the day the Plan ended and not the runner's best.
Never taken back, and carried in the archive. The runner stays in that last Stage
with its Workouts and their standing Prescription; what changes is that the card
stops calling it something to achieve, the already-beaten line is no longer shown
on it, and the coach is told (#294).
_Avoid_: graduation (a graduation moves a runner to the next Stage; there is no
next Stage here), finishing a Stage, maintenance mode

**Workout**:
The planned shape a Run may follow — its intervals, durations, and targets —
taken from the training plan.
_Avoid_: session, plan (the Plan is the whole schedule of Workouts)

**Test**:
The one Workout of a Stage that exists to answer its Requirement — a 5K run
flat out, with no warm-up or cool-down inside it. Named by the plan and never
inferred from its shape. Every Run writes down the Workout it followed, so when
the runner last ran a Test is read off history and never stored — the last Test
of any Stage, since the Test that graduated one was still a test (#292). The
app says a Test is due three weeks after the last one, held while Form is below
−10, and it stays pickable on any day either way — a prompt, never a gate. A
Test that misses the bar is still a Test: it states the gap, resets the three
weeks, and changes nothing else. A Test abandoned partway is not one: to have
been the Test a Run has to cover the Requirement's distance, or failing that
last nine tenths of the Workout's own length.
_Avoid_: time trial, assessment, benchmark

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
distance, unlike an Interval, which is measured by time. A Split also knows the
stretch of the clock it covers and the moving time its pace was quoted against,
because the Export writes those down as a lap and only the walk that cut the
Split knows where the kilometre was crossed (#218).
_Avoid_: lap, mile — except inside the FIT Export, where `lap` is the format's own
word for its message and using any other would be a name that matches nothing in
the file (`FitLap`, `LapMesg`).

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
Pause carries no ground at all and no seconds the Run counted. The recorder
writes one down as it ends, with the instants the Run's clock stopped and
started, so a Run with no GPS has its Pauses too
([ADR 0018](docs/adr/0018-a-pause-is-written-down.md)).
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
([ADR 0008](docs/adr/0008-a-stated-distance-is-a-real-distance.md)). It buys no Best Effort: a
measured one needs a route to find a stretch inside, and a Stated Best Effort is a claim of its own
that this one neither makes nor implies.
_Avoid_: manual distance, entered distance, treadmill distance

**Route**:
A course the runner keeps: a line to follow, how far it goes, and how much climbing it holds. A
plan rather than a recording — it may be run many times or never, and it carries no time, no heart
rate and no date it happened, because none of that is true of it yet. Having no recording behind it,
it has no Breaks either: the segments a file arrives in are joined, and its distance and climb are
worked out once and banked rather than re-measured on read
([ADR 0014](docs/adr/0014-a-route-is-a-plan-not-a-recording.md)). A Route _is_ its line, so a file
drawing a line already kept is that Route rather than a second one — it adds nothing, and where it
measures the line differently it re-measures the Route the runner already has. Imported from a GPX
file (#54), and later saved from a Run that has already been made. Deleting one costs no Run anything: nothing in the
library points at history and nothing in history points back.
_Avoid_: path, and **track** — a Track is what a Run recorded of where it went, and the two must
not be confused even in code (`TrackPoint` belongs to a Run). "Course" is fine, and is the word for
the line itself as against the whole record of it. The `Routes` object under
`navigation/` is the app's list of screen addresses and is a different word that happens to be
spelled the same.

**Segment**:
A stretch of ground the runner has named — "Cemetery Hill" — cut out of one Run they actually ran
(#69). Neither a Route nor a Run: a Route is a line somebody else drew and a Run is one outing, while
a Segment is a _place_ the runner expects to cross again. Its geometry is a slice of a real recorded
Track, never drawn freehand and never taken across a Break — a stretch marked either side of a Pause
or of lost signal is refused rather than joined, because the straight line over one is ground nothing
witnessed. That geometry is copied onto the Segment's own row rather than read back out of the Run,
which is what lets it outlive it: deleting the Run it was traced from forgets where it came from and
keeps the place. Timed against every Run that crosses it — see **Segment Effort**.
_Avoid_: PR segment, lap, split (a Split is a kilometre of one Run), and **track** for the same
reason a Route avoids it. A GPX file's own `<trkseg>` is a "track segment" of that file's making and
has nothing to do with this word.

**Segment Effort**:
One time a Run went over a Segment (#70) — a fact about a Run *and* a place, not about either alone,
which is why it is banked rather than worked out on read. A Run holds one for every time it crossed
the stretch, so a Run up the hill three times holds three. What counts as having crossed it is a gate
at either end with a corridor between them: the runner has to pass within about thirty metres of the
Segment's start, stay on its line — a GPS blip flung sideways for a second or two is forgiven, a
shortcut that skips part of it is not — and come out within about thirty metres of its end, the same
way round. The time is the wall clock between the two gate crossings, worked out between the fixes
either side of each gate rather than at the nearest one. A Segment's **PR** is the quickest effort
ever run at it, and a tie leaves it with the earlier one, as the record book does. Eligibility is the
record book's: a Run still being recorded holds none, a treadmill Run holds none because it has no
Track to put to the ground, and a **Walk** holds none — so marking a Run a Walk takes its efforts off
every Segment, and unmarking measures them again. Creating a Segment measures the whole of history
against it, so it arrives with its efforts and its PR already on it; measuring the same Run against
the same Segment again replaces what was there rather than adding to it, which is what makes any
scan safe to run twice. Both sides carry the debt until it is paid — a Segment that has never been
walked against history and a finished Run that has never been walked against the Segments are each
picked up at the next launch, which is how a Segment cut before efforts existed gets its own, and how
a walk lost to a process being reclaimed is made good. The matching engine calls one a *traversal*
until it is written down, because until then it is a finding about a track rather than a row.
An effort that stands in the quickest three ever run at its Segment holds a **Medal** there — the
same three metals the record book hands out, and shown in the same discs — which is what a Run's own
page says about the ground it went over (#71). It is a **Medal** and not an **Achievement**: an
Achievement is a place at one of the seven **Records**, which are facts about whole Runs, and a
Segment is not one of them. The places are worked out from the efforts on every read rather than
banked, so throwing a Run away hands its places straight on to whoever was behind it and no ghost is
left holding one.
_Avoid_: Best Effort (a stretch of one Run at a record distance), attempt, lap, and the bare word
"Effort", which is what the runner rates a Run out of ten. A Segment Medal is not an **Achievement**
and a Segment is not a **Record**.

**Export**:
A finished Run written out as a file for somewhere else to read (#84, #218). Two
formats, and the runner picks at the moment they share. **FIT** states the Run: its
own distance, its own Duration and Moving time, its own Splits as laps, and one
moment per second anything was recorded for — so a Run with no GPS at all is a whole
file, and so is one with neither Strap nor GPS, which states its summary and holds no
moments at all (#329); nothing is re-derived
([ADR 0017](docs/adr/0017-an-export-states-the-run-it-does-not-imply-it.md)). **GPX**
implies it: places and times only, and it needs a position for every moment, so a
treadmill Run has nothing to put in one. FIT is offered first for both reasons. An
Export is a copy handed on, never a record: it is written into a cache the phone may
reclaim, and nothing in the app reads one back.
_Avoid_: backup — that is the monthly Archive (#85), which is the whole database and
is a record; and import, which is how a Route arrives (#54) and goes the other way.

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

**Walk**:
A Run the runner has said they walked. One mark on a whole Run, set on the "How
did that feel?" sheet at the finish and changeable on the Run's own page for
ever afterwards; never inferred, because nothing in a stored Run distinguishes a
walk from a run and guessing would rewrite curves nobody asked to change. Its
Effort Score is untouched — the heart really did do that work — and what changes
is who reads it: the whole Score builds Fitness, and a quarter of it is carried
into Fatigue, because the fatigue that degrades a runner's form is largely
mechanical and walking barely pays it. A Walk counts towards Goals, fills the
weekly volume bars and is marked as one in the History list. It takes no Best
Effort and no record of any kind, completes no prescribed Workout and graduates
no Stage, and it reaches the coach named as a Walk. A mark made on the finish
sheet is in before the Run is judged at all: the Run's Stage is settled when that
sheet closes, not at STOP, precisely so that a walk cannot graduate a Stage a
moment before the app is told it was one (#297). Marking one takes back the
medals it held, through the same mend a deletion owes; it does not un-graduate a
Stage that is already past, and a mark made afterwards — on the Run's own page —
does not re-run the Run's own judgement, so what it buys is every evaluation
after it. Curves are worked out on read, so marking a session
from three weeks ago moves every Fitness, Fatigue and Form number from that day
forward — silently, because they are a live read of the truth and the
alternative is freezing numbers we know to be wrong.
_Avoid_: Run Mode (treadmill or outdoor) and Run Type (Long, Easy or Quality),
both of which are already taken and neither of which this is; walk mode, which
says it is chosen before a Run rather than after one; and using the word for the
walk stretches inside a run/walk Workout, which are Intervals and bank full
Fatigue.

**Fitness**:
What the runner has built: their Effort Scores averaged over the last 42 days,
with the recent days counting for most, and every Score in full — a Walk's
included. Slow to move by design — six weeks of training is what it takes to
raise it, and a fortnight off is what it takes to lose much of it.
_Avoid_: CTL, chronic load, form (Form is the difference between this and
Fatigue)

**Fatigue**:
What the runner is still carrying: the same Effort Scores over the last 7 days,
except that a Walk pays only a quarter of its Score in here. The same arithmetic
as Fitness on a shorter memory, so a hard weekend shows up here within days and
in Fitness barely at all.
_Avoid_: ATL, acute load, tiredness

**Form**:
How fresh the runner is today: yesterday's Fitness less yesterday's Fatigue.
Yesterday's rather than today's, because freshness is a question asked in the
morning, before today's Run has cost anything. Above +10 is fresh, below −10 is
fatigued, and the band between is neutral.
_Avoid_: TSB, training stress balance, freshness as a name for it — "fresh" is
one of its three verdicts, and the number itself is Form

**Goal**:
A standing target the runner sets themselves: this much distance, time or Runs
in a week, a month or a year. One per period-and-metric pair, and recurring —
there is no end date and no copy per period, so a week ends and the same Goal
measures the next one. Where they stand against it is worked out from their Runs
on read, so editing a Goal re-measures the period they are in as well as the ones
to come. Every finished Run counts towards one, walk and treadmill alike; a Run
whose distance nobody measured adds its time and its one to the count and nothing
to the distance. The coach is told where the runner stands against each one, in
the period it is being measured over, so a debrief can read a hard week as the
week the runner meant to have — and told just as plainly that a Goal is the
runner's own target and not the plan's: it graduates no Stage, and a shortfall is
never work for the coach to make up, because a Goal is chased across a whole
period and never bought with one harder Run (#83).
_Avoid_: target as a name for it (a target is what a Goal holds), challenge,
streak, objective

**Today**:
The calendar day the runner is in, and the zone that day is read in. Observed,
never captured: anything that needs the zone asks the phone for it at the moment
it answers, rather than holding the one it started with — and the same for the
clock a sleep to midnight is aimed at. It is the one input to a rule that moves
on its own, in two ways at once: midnight arrives, and the runner can fly. A
zone taken once and held for the life of a screen answers a runner where they
took off from, a calendar day out (#299). Because it moves on its own, both of its
movements are inputs a reader is woken by and not readings a reader remembers to
take: the turn of the day, and the phone changing zone (`ZoneChanges.kt`). A
reader built only over database flows would otherwise hold its answer until some
Run moved, which on a flight is hours (#320).
_Avoid_: the device timezone, the current date

**The day a Run happened**:
The calendar day the runner would say they ran, and the exact opposite of **Today**:
captured, never observed. A Run writes down how far east of UTC its runner's clock
was the moment START was pressed, and every reader that places the Run on a day —
the weekly bars, the Progress curve, the Goals, the Stage's Test clock, the day a
finished Plan is recorded on, the exported file's name, the date on its own page — reads that
rather than re-reading the start moment in whichever zone the phone is in now. A
Run near midnight otherwise changes the day it happened on the moment the runner
flies, and for a Plan Completion, whose day is recorded once and can never be
re-earned, that change is permanent (#304). A Run recorded before the app kept
this has no stamp and nothing can work one out, so those are still read in the
phone's zone; there is no backfill, because the only offset a backfill could write
is a guess at today's. It follows that a Run's day can honestly lead **Today** by
one — a runner who ran on Saturday evening in Sydney and landed on Saturday
morning in London — and the guards that keep a slipped phone clock out of the
week's totals allow exactly that one day and no more.
_Avoid_: the run date, the session timezone

**Best Effort**:
The fastest continuous stretch of a Run covering one of the record distances —
1 km, a mile, 5 km, 10 km, a half marathon — measured on the clock, so a walk
break inside it counts against it. Found anywhere in the Run, not at the Split
boundaries, so a fast middle section counts. Measured off the Run's route, or a
Stated Best Effort where a treadmill Run has no route to measure. A Walk holds
none: a walk taking a "fastest 1 km" makes the trophy case meaningless.
_Avoid_: PR, personal best (a Best Effort is only a claim until it places)

**Stated Best Effort**:
A Best Effort a treadmill Run is told it holds: one of the five record
distances, and the time the console showed for it, read off the machine by the
runner. Stated rather than measured, as a Stated Distance is, and never worked
out from a Run's distance and duration
([ADR 0015](docs/adr/0015-a-stated-best-effort-is-read-off-a-console-not-off-an-average.md)).
A Run may hold one at each of the five distances, and no one of them says
anything about another. It places in the record book exactly as a measured Best
Effort does.
_Avoid_: manual split, entered PB, treadmill best effort; and "lap time", which
is what the console calls it rather than what the app keeps

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
Where an effort placed: gold, silver or bronze. There is no fourth place. Handed out at a **Record**,
where it is banked as an **Achievement**, and at a **Segment**, where it is worked out on read from
the **Segment Efforts** themselves (#71) — the same three metals in the same discs either way,
because a place is a place and a runner should not have to learn two of them.
_Avoid_: rank, position

**Debrief**:
The sentences the runner is shown after a Run, in the card on the Today screen.
One slot, two writers, and the card says which: the coach writes one to explain
its Prescription, and the app writes its own for the things that are not the
coach's to judge — a Stage granted, a Plan finished, a Test short of its bar
([ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)).
Whose it is is stored beside it when it is written, never worked out afterwards
from how the words read: the app's own congratulation under the coach's name
hands back, on screen, the attribution the rule took away — and a runner with AI
sharing switched off must never be congratulated by a coach they never turned on.
_Avoid_: coach message as a name for the whole slot (only the coach's Debrief is
the coach's; the preference key is still spelled `latest_coach_message`, from
when the coach was its only writer), summary, feedback.

**Prescription**:
What the AI coach writes after a Run: the intervals and target zone for the next
Workout of one Run Type, and nothing else. The coach holds one per Run Type —
three independent slots — so a Prescription applies only to its own kind of
session and can never land on another. Only the Long slot is ever written: the
coach decides whether to evaluate a Run by its Run Type, and adjusts the Long Run
alone ([ADR 0006](docs/adr/0006-the-coach-adjusts-the-long-run-only.md)). Dated,
replaceable, and never a setting — the coach prescribes work, it does not
configure the app. It stands on the Runs the coach was shown to arrive at it, so
deleting one of them takes it back to the coach's previous Prescription, and to
the Stage's own Workout only when nothing is left standing
([ADR 0013](docs/adr/0013-a-prescription-stands-on-the-runs-it-was-shown.md)).
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

**Run Journal**:
The app's own record on disk of the events that decide whether a Run is
recording — the Run starting, pausing, resuming, stopping and being finalized;
the Promotion being taken, refused or handed back; the service coming up and
going down; the Strap arriving, leaving or being given up on. One plain-text
line each, wall clock first, naming the Run it happened to. It exists because
Android's log buffer holds about two hours and a Run plus the walk home is
longer, so the minute that would name a cause has rolled off before anyone
looks (#309). Bounded and rolled, carried in the archive, and written on a
thread that outlives the service — a line recording a teardown must not be
cancelled by the teardown it records. Nothing reads it back to make a decision:
it is for a person or an agent to read over `adb` (#310).
_Avoid_: log, logging, telemetry, audit trail

**Simulation**:
A developer mode that feeds fake heart rate in place of a Strap, so the app can
be exercised without hardware.
_Avoid_: demo mode, test mode, fake mode
