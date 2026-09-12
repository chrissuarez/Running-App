# A graduation is the app's to grant and the runner's to undo

A Stage only ever moved forward. `settleStageAfterRun` reaches
`SettingsRepository.graduateStage`, the active Stage id is written on, the standing Prescriptions
are dropped, and from that moment there was nothing anywhere — no control on the screen, no call in
a repository, no pass over history — that could put the runner back.

So every graduation was final the instant the app said yes, whatever it had said yes to:

- a GPS distance that overread on a bad-signal day
- a treadmill distance typed wrong and not noticed until the next Long Run
- a Long Run the runner would rather not have had judged at all
- the coach simply getting it wrong, which
  [ADR 0016](./0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md) leaves it
  entitled to do on every requirement written in prose

None of those is exotic, and the runner's answer to all of them was to have no answer: the Stage
they were on was the Stage they were on. Codex raised it round after round against
[ADR 0008](./0008-a-stated-distance-is-a-real-distance.md) on PR #233, which declined to build a way
back for the stated-treadmill case alone — rightly, because it is not that ADR's bug. The gap was
there for every Run that had ever graduated anything.

## The decision

**The app grants a graduation forwards, from evidence, and never withdraws one. The runner moves
themselves back, by hand, at any time, for any reason.**

Two writers, two rules, and they do not overlap:

| | Forwards | Backwards |
|---|---|---|
| Who | the app, from a finished Run | the runner, from the Training Plan screen |
| When | at the moment the Requirement is answered | whenever they say |
| Why | evidence | no reason is asked for, and none is recorded |

`TrainingPlan.passedStageIds` is the whole of what may be gone back to: the Stages *before* the one
the runner is in, off the same walk `lockedStageIds` takes, so "left", "standing in" and "not
reached" are one reading of one position and can never disagree. `SettingsRepository.moveBackToStage`
is the write.

### Backwards only

Moving *forward* by hand is not offered and should not be. A Stage is left by answering what it
asks, and a button that skipped that would hand out a graduation nobody earned — which is the one
thing the Plan exists to decide. Going back takes nothing away from the runner; going forward would
give them something they had not got.

### No reason, and no window

The runner is not asked why, and the offer never expires. Both were considered and both were
declined for the same reason: the cases this exists for are noticed late. A treadmill distance typed
wrong is spotted at the next Long Run, a fortnight on; a coach's judgement reads as wrong only after
training under it for a while. An undo button that sits on the card for a day would miss every
example in the list above, and a stored reason would be a field nothing ever reads.

### What a move takes with it, and what it does not

**It does not touch a single Run.** No Best Effort is given back, no record-book entry is withdrawn,
no Run's stored Stage is rewritten. What the runner ran, they ran, and `RunnerSession.ranUnderStageId`
still says truthfully which Stage they ran it under (#234). This is the sentence the confirmation
dialog and the debrief both exist to say out loud, because the fear that undoing a graduation also
undoes the run that earned it is the fear that would stop the feature being used at all.

**It drops the standing Prescriptions**, for the reason a graduation drops them: they were reasoned
about against the Stage being left, and a Workout modified by them is not the Workout of the Stage
now underfoot ([ADR 0013](./0013-a-prescription-stands-on-the-runs-it-was-shown.md)). The coach
writes new ones after the next Run.

**It cancels a Plan Completion of the Plan being moved within.** A runner standing in Stage 2 has
not finished the Plan, and a COMPLETE badge over a Stage they have walked away from is the screen
contradicting itself. Only that Plan's completion: one slot holds the fact, and the fact is about a
Plan. This is a real amendment to #294's "never taken back" — it was never taken back *by the app*,
and it still is not.

**It replaces the standing debrief.** What stood there explained the Stage being left, most often
the very congratulation that moved the runner off the Stage they are going back to.

All four land in **one** write, for the reason a graduation is one write: a Run started in a gap
between any two of them would be a Run on a Stage half arrived at.

### Not gated on the coach's scope

`moveBackToStage` deliberately does not go through `editCoachWrite`. That gate refuses work the
coach reasoned about against a Stage the runner has since moved off — and this *is* the runner
moving. Gated on the Stage it is changing, it could only ever refuse itself.

## What happens next

The Stage the runner lands on is live again, with its Workouts, its Test, and its bar. Its card will
say the bar has already been beaten where history holds a Run that beat it (#293) — which is true,
and a statement rather than an offer. Nothing re-graduates until a new Run answers the Requirement
under that Stage, because a graduation is granted forwards only and never from a pass over history
(#290). A runner who went back because the evidence was bad simply will not produce that Run again;
one who went back to train the block properly will, and that graduation is earned.

## The alternatives, and why not

- **A short undo window on the graduation card.** Smaller, and it reads well. Declined because the
  ticket's own examples happen days or weeks later — see *No reason, and no window* above.
- **Record the Stage and verdict on the Run, and recompute graduations rather than reverse them.**
  The most principled answer, and the most machinery: provenance per verdict, a replay of every
  judgement in order, and an answer for what a recompute does to a Prescription written under the
  Stage it deletes. Half of it — Stage provenance on the Run — shipped for #234 already. It is not
  ruled out, but it is a great deal of engine to build so that a runner can fix a typo, and it makes
  the app the decider of something the runner is perfectly able to decide.
- **Let the runner re-pick the Plan.** Available today, and the wrong shape: `setActivePlan` puts
  them at Stage 1 whatever Stage they wanted, so the runner fixing a wrong graduation out of Stage 3
  loses Stage 2 as well.

## The residue

A move is not written down anywhere. The app holds where the runner stands and not how they came to
be standing there, so history cannot show that a Stage was gone back to, and the archive carries the
position rather than the path. That is consistent with every other setting in this app and costs
nothing the runner can see; if a reason or an audit trail is ever wanted, it wants a record of
graduations first, which is the recompute answer above.
