# An Outage is a leg like any other

[ADR 0010](0010-the-track-is-the-record-of-a-break.md) settled what a Break is worth in metres and
left one question open in as many words: *judging whether the runner was moving across a Break is a
separate decision (#165).* This is that decision.

## The same ground, counted for distance and not for time

A Run's Covered distance counts an Outage in full — the straight line between the fixes either side
of it, banked live by the recorder and rebuilt the same way by every reader since ADR 0010. Moving
time did not. Any gap longer than `TRACK_BREAK_MS` was rest by definition, whatever the fixes either
side of it said.

Pace is distance over moving time, so the two rules together flatter it. A minute lost under tree
cover on a 30:00 / 5 km Run — every fix refused by the accuracy gate, 250 m of ground on the far
side — leaves 5 km over 29:00, and the Run reads 5:48 /km where the runner ran 6:00. The error grows
with every second of lost signal and has no ceiling but the Run's own clock.

## An Outage is judged on the ground it carries

**A leg across an Outage counts as moving when its ground over its seconds clears
`MOVING_SPEED_THRESHOLD_MPS`, exactly as a leg the recording covered does.** No leg has a rule of
its own any more; the gap decides nothing except that no route may be drawn through it.

This is ADR 0010's rule read once rather than twice. That ADR said an Outage carries the straight
line because *the runner did cover that ground* — and ground covered is the whole of the evidence
that a runner was running. Refusing the seconds while banking the metres is holding both of two
contradictory beliefs about the same stretch of a Run.

It also needs nothing the track does not already carry. A tunnel at 4 m/s reads as running because
it was; two fixes five minutes apart in the same spot read as rest because the runner got nowhere.
The threshold is doing the work it was calibrated to do, on the one leg that used to be exempt.

## A Pause is still never moving, and for the same reason

A Pause carries no ground at all — the Run wrote it down, its own clock stopped for it, and the
recorder drops its distance baseline across one. There is therefore nothing to judge: zero metres
over any number of seconds is under any threshold. The exception survives without being an exception
to this rule, which is the test that the rule is the right one.

So the Pause is told from the Outage by the record on the track (`TrackPoint.startsAfterPause`),
never by the length of the gap. Length was only ever a stand-in for a record that did not exist
before #84.

## What was rejected

- **Taking a Break's distance back out of the pace denominator.** It closes the same arithmetic, and
  ADR 0010 rejected it for reasons that have not changed: the Splits table would stop totalling the
  distance printed above it, and a Run's pace would no longer divide the Run's own distance.
- **Counting an unmarked gap as moving only on Runs recorded since #84.** The appeal is that every
  Pause has been written down since then, so any *other* gap on such a Run is certainly lost signal.
  But there is no way to tell a post-#84 Run that never paused from a Run recorded before the column
  existed — they are the same rows — and the speed test reaches the same verdict on the case that
  motivated it without needing to know which kind of Run it is looking at.

## Consequences

- **A legacy unmarked Pause the runner covered ground across is now counted as moving.** Before #84 a
  manual pause left nothing but a gap, so a runner who paused, walked to a shop and resumed reads as
  a slow leg that clears the threshold. The error is real and it is bounded twice over: it can only
  ever make a Run's pace *slower* than it was — never flattering, which is the direction ADR 0010
  chose everywhere else — and `SessionRepository.computeMovingTime` still caps moving time at the
  Run's own clock, which never banked those seconds. A Run whose whole rest was unrecorded pausing
  therefore degrades to the pace it showed before moving time existed, rather than to nonsense.
- **A Split holding an Outage now reads the pace the runner ran.** ADR 0010 shipped that Split
  reading too fast, deliberately and temporarily; the ground was already in it and the seconds were
  not. Both are now.
- **Every measured Run is measured again** (`MIGRATION_22_23`). The Splits table is worked out on
  read and follows the new rule the moment the app is upgraded, while `sessions.movingTimeSeconds`
  was banked at the finish under the old one. Left alone, a Run holding an Outage would print one
  pace at the top of its page and a truer set of them underneath — two answers to one question,
  which is the defect ADR 0010 exists to close. The migration nulls the column on every finished
  outdoor Run and the #163 backfill pass re-measures the history once, in the background, at the
  next launch.
- **A hesitation running into an Outage is kept rather than discarded.** A slow spell inside the
  rest window used to be thrown away along with any Break that followed it, on the reasoning that the
  runner was slowing to the stop. Where the Outage is moving there was no stop to slow to, so the
  spell is redeemed by it exactly as it would be by any other moving leg. Before a Pause — a stop the
  Run wrote down — it still goes.
- **The pace line on the combined chart still steps over an Outage**, and for a reason this ADR does
  not touch: no window of pace may reach through a Break, because that line is the shape of the Run
  and a tunnel has no shape. What changed is only the reason ADR 0010 gave for it — the seconds are
  no longer missing, they simply belong to the Splits table rather than to a curve.
- **A Run's records are not re-scored by this.** Best Efforts have always spanned a Break over its
  straight line and its full seconds (ADR 0010), so nothing in the record book was measured under
  the rule that changed.
