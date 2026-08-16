# A Stated Distance is a real distance

For half the year the runner trains indoors. Winter takes the pavement away, the treadmill takes its
place, and until now the app could not see any of it: a treadmill Run recorded a heart rate, a
duration, and `distanceKm = 0.0`. No distance, so no pace. No distance, so nothing in the weekly
volume. No distance in what the coach is sent, so a winter of work looked to the coach like a winter
of nothing, and no Stage could be graduated out of.

So the runner states the distance — the number the treadmill's console showed — and **the app treats
it as a distance like any other**. It sets the pace, it counts toward the volume, it goes to the
coach, it can graduate a Stage, and it can take the longest-distance Record.

That last one reverses a rule this repository argues for in prose today. `Records.kt` says a
treadmill Run "contests the longest time and nothing else", because "its distance is a number the
machine reported, never measured against ground, so letting it hold a distance record would put an
unverifiable claim above every measured one". The reasoning was sound and the conclusion is now
wrong, for a reason that was never in front of it: **a record book that cannot record the longest
run of the runner's winter has a hole in it exactly where the winter went.** Unverifiable is not the
same as untrue, and the app already stakes far more than a medal on numbers the runner simply
states — every zone edge in it is a percentage of a Reserve built from two typed-in heart rates
([ADR 0004](./0004-zones-from-heart-rate-reserve.md)). A distance is not the place to start
doubting them.

## The five fastest-* Records stay barred, and nothing fakes a way in

A Best Effort is the quickest continuous stretch covering 1 km, a mile, 5 km, 10 km or a half —
found *anywhere inside a Run* by a rolling window over its track. A treadmill Run has no track. Not
a poor one, not a sparse one: none. There is no stretch to find, so there is no Best Effort, so
those five Records are unreachable and stay so.

The tempting move is to derive one: distance ÷ duration gives an average pace, an average pace gives
a 5K time, and the number would slot straight into `fastest5kSeconds` where everything downstream
already reads it. **Rejected.** It is not the same measurement wearing the same name — a negative
split is under-credited by it and a fast start is over-credited, and once it is in that field
nothing downstream can tell it from a stretch someone actually ran. The whole point of measuring a
best effort on the clock is that it is the runner's best, not their average.

So a Stage requirement phrased as a distance and a time — *"Run a 5K in 24:59 or faster"* — is
judged on a treadmill Run by the coach, in prose, from the distance and the duration it is already
sent. Graduation is an AI judgement here already; this asks it to do the job it was given rather
than building machinery to hand it a number that would be a guess.

**But it is only asked what those two numbers can answer.** A 6 km Run in 30 minutes may hold a
sub-25-minute 5K and may not, depending entirely on splits the console never handed over, and a
coach ruling on it either way would be doing the derivation this section has just rejected — with
`evaluateAndAdjustPlan` advancing the stored Stage the moment it answered yes. So the rule the coach
is given is that a stated distance and a whole-Run duration establish a time **over the whole Run and
nothing shorter**: 5 km in 24:30 graduates a *5K in 24:59* Stage, 6 km in 30:00 does not, and where
the Run is longer than the requirement the coach declines rather than guesses. A Stage held one
winter longer is a smaller loss than a Stage graduated out of on a number nobody ran.

That is the judgement made of every Run *after* the one the number belongs to. Its own Run is a case
of its own, settled below.

## Only a treadmill Run can hold one, and that is what makes it free

A stated distance is available to treadmill Runs and to nothing else. An outdoor Run whose GPS
recorded nothing cannot be rescued this way, however tempting.

That restriction is doing real work. It leaves `runMode` as the sole thing distinguishing a stated
distance from a measured one, which means:

- The existing `distanceKm` column carries both, and **there is no migration**.
- `0.0` keeps its current meaning — nobody stated one — because no treadmill Run is ever legitimately
  zero kilometres long.
- Every rule that needs to know which kind of distance it is holding already asks `runMode`, and
  `Records.kt` gates on `runMode` today.

Admit outdoor Runs and all three of those go: provenance needs its own column, a migration, and a
rewrite of every rule that currently says "measured" by saying "outdoor".

## What a late number can put right, and what it cannot

The number arrives after the Run is over, and by then `finalizeRun` has been through the whole of
its work: the row saved without a distance, the record book scored, the coach asked whether the Run
graduates the Stage. So "treated as a distance like any other" has to say which of that is done
again.

**The record book is.** Scoring is a function of history and nothing else — the same Runs put in the
same order — so running it again with a distance that had been missing simply arrives at the book
that should have been written. Stating a distance re-scores; so does correcting one. A correction
*downward* rebuilds rather than re-scores, from all of history: only the top three are ever banked,
so the Run that should move up behind a demoted medal exists nowhere but in the sessions themselves,
and re-scoring the corrected Run cannot find it. That is the mend a deletion already owes, for
exactly the same reason. Either way the history backup is refreshed afterwards — the one
`finalizeRun` took went out before the number existed, and a Run restored from it would come back
with the distance gone.

**The Stage evaluation is not, and will not be.** It is not a function of history; it is a judgement
made once, about one Run, under the Stage and the Workout in force at that moment, from the three
Runs standing before it. There is nothing to replay it from: a Run records neither the Stage nor the
Run Type it was judged under, and `evaluateAndAdjustPlan` reads whatever the last three eligible
Runs are *now*. And a graduation cannot be taken back — `advanceStageAndClearPrescriptions` moves
the plan on and nothing moves it back — so a mistyped 5.00 km that graduated a Stage and was then
corrected to 4.90 would leave the runner advanced on a Run nobody ran. Building the way back is
provenance on every Run, a Stage that reverses, and prescriptions restored behind it: a great deal
of machinery, standing between the runner and a typo.

So what a stated distance buys the coach is **every evaluation after it** — which is where the
winter was going missing in the first place. The context is built fresh each time from the stored
distances, so the next Long Run is judged against a season that is actually there. The Run whose own
number arrived late is judged on its duration and its heart rate, as it is today. A Stage graduated
one Run later is a small loss; a Stage graduated by a typo, with no way back, is not.

**And that has to hold even when the runner is quick.** The Run's own evaluation used to be in
flight while the sheet was on screen — it reads the last three Runs out of the database on its way
to asking the coach — so a number typed fast enough would slip into the judgement of its own Run,
which looked like the single case where a typo could graduate a Stage nothing can ungraduate. The
answer here was to judge the Run **as it stood when it was finalized**, freezing the row against the
race.

[#297](https://github.com/chrissuarez/Running-App/issues/297) later replaced the freeze, because the
same race lost the *Walk mark* — which arrives on that same sheet, and which withdraws a Run from
the judgement entirely. The judgement now waits for the sheet to close instead of racing it, so the
race is gone rather than frozen against, and everything the sheet says is always in.

The typo this section worried about is still no threat, but the guard is not the one it looks like
and is worth naming exactly. A stated distance *does* reach the record book — it is the Run's
longest distance, which is the whole of ADR 0008. What it can never reach is a **graduation**:
`BestEffortRequirement` refuses to be written at anything but a fixed distance
(`record.distanceMeters != null`, ADR 0016), so the two records a stated distance can move are
precisely the two no requirement may be written in, and a treadmill Run's *fastest* efforts come
only from a Stated Best Effort read off the console. A mistyped distance therefore cannot graduate a
Stage however quickly it is typed — because of that `require`, not because of the wait. A future
requirement written in a distance or a duration would have to answer this question again.

What has not changed is anything said *later*: a number typed on the Run's own page waits for the
next evaluation, exactly as this section has it.

## Consequences

- **`Records.kt`'s eligibility prose is now wrong where it stands and must be rewritten**, not
  patched around. The treadmill bullet reverses for the longest distance and holds for the fastest
  five, and it should say why on both counts.
- **The other unverifiable case is untouched.** A Run with no usable track — old history from before
  the app kept one, or a Run whose every fix was too vague to trust — still contests no distance
  Record. It carries a total measured against ground nobody can now see, and unlike a treadmill Run
  nobody has stood behind that number since.
- **A treadmill Run shows no live distance or pace while it is being run.** The number arrives after
  the Run, from a console that was in front of the runner the whole time. Little is lost by the
  phone learning it late, and what is lost is settled above.
- **A distance nobody stated reads as a dash, not as `0.00 km`** — on the History row and on the
  Run's own page, for every Run with no distance rather than as a treadmill special case. A zero was
  always a lie about a treadmill Run; it is the same lie about an outdoor Run the GPS lost.
- **A stated distance has to be correctable.** It reaches the volume, the coach and the record book,
  so a mistyped one is not a cosmetic error and cannot be write-once. It is editable on the Run's
  page, and what a correction puts right — and what it cannot — is settled above.
