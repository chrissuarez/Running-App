# A leg stamped one moment is ground without a speed

[ADR 0010](0010-the-track-is-the-record-of-a-break.md) settled what a Break is worth in metres and
[ADR 0012](0012-an-outage-is-a-leg-like-any-other.md) settled what it is worth in seconds. Both were
written about a gap in time. This is the other shape a leg can take: two fixes in *different places*
carrying the *same* timestamp, so the leg has ground and no time at all. #336.

## The line was drawn and the metres were not counted

`measureTrack` gave such a leg zero metres, on the reasoning that a leg with no time to have been run
in would otherwise be distance for free. But the leg stayed in the track, and its two fixes stayed in
different places, so every line drawn from `TrackMap.route` drew the jump anyway.

A Run's own map could therefore show a straight hop its distance did not include, and since #69 a
Segment cut across one inherited the same silence. The two numbers never disagreed with each other —
a Segment's distance and the Run's are one measurement read at two points — but both could disagree
with the picture.

There was a second disagreement underneath it, and it is the one that settles the question. The live
recorder banks a fix's distance with no test on the time since the last one (`SessionRecorder`), so
the metres were already in `sessions.distanceKm`. Only the readers dropped them. A Run's saved
distance and its own re-measurement therefore answered differently — which is exactly the defect
ADR 0012 exists to close, in a place ADR 0012 did not look.

## The leg carries its ground, and no seconds

**A leg between two fixes stamped the same moment carries the geodesic distance between them, and
`millis` and `movingMillis` of zero.**

Ground, because the two fixes are in different places and the runner reached the second of them. What
is wrong about such a leg is its *stamp*, not its *position* — a clock that failed to tick is not
evidence that a runner failed to move — and the same argument ADR 0010 made for an Outage applies
unchanged: the runner did cover that ground, the straight line is never longer than the route they
took, and the recorder has banked it since before either ADR was written.

No seconds, because there are none to bank. A leg with no time has no speed to put to
`MOVING_SPEED_THRESHOLD_MPS`, and handing it seconds it did not record would make the pace across it
infinite. It is not moving time, it cannot be rest, and it cannot redeem or condemn a slow spell.

A *Pause* still beats this, as it beats every other reading of the clock. The Run wrote it down, so
a leg whose far fix resumes one carries no ground and stays a Break whatever its two ends are
stamped — the record is the authority, and the length of a gap has not been evidence of a Pause since
#84 ([ADR 0018](0018-a-pause-is-written-down.md)).

It stays `recorded` otherwise. There is no stretch between the two fixes for the recording to have missed, so
the line is drawn across it and the climb underneath it is banked — reading a single repeated stamp
mid-hill as a Break would throw that climb away for nothing.

## A total counts every leg; a speed counts only the legs that hold one

The two halves of the rule fall out of one distinction, which is why they can be stated together
without contradicting each other.

**Anything totalling the Run counts the leg** — the distance on the summary, the rescue pass's
rebuild, the kilometre the Splits table is cutting, the axis the route map and the distance chart
measure themselves along. A kilometre has to add up to the ground under it, and a map has to draw the
ground the total claims.

**Anything reading a speed skips it** (`TrackLeg.carriesSpeed`). The smoothed pace line folds a
window of legs into one reading, and metres over no seconds would bend that line faster than the
runner ran; the fastest-effort window would reach its target across ground that took no time and
report a 5K nobody ran. This is the same predicate a Break already satisfied for its own reason — a
tunnel has no shape — so the pace line's guard is now one test rather than two, and stated where
both readers inherit it rather than in each of them.

## What was rejected

- **Reading it as a Break.** It would cut the drawn line, which closes the complaint that started
  this ticket — but the map would then agree with a total that *still* disagreed with the Run's
  banked distance, so the deeper disagreement survives untouched. It would also throw away any climb
  banked across the fix, and refuse a Segment marked either side of it. It buys the smaller half of
  the fix and pays more for it.
- **Leaving the metres out and dropping the fix from the drawn route instead.** Same result as a
  Break for the drawing, and it puts the rule in `trackMapOf` where only the map inherits it. The
  ticket's own test of a fix is that one statement reaches every reader.
- **Refusing such a pair at the accuracy gate, before any measurement sees it.** Tempting, because
  it makes the case unreachable. But the gate's job is to judge a fix's *accuracy*, and a fix with a
  duplicated stamp may be perfectly accurate; a gate that also judged clocks would be two rules in
  one door, and the second of them invisible to anything reading the track afterwards.

## Consequences

- **No Run on the phone changes.** Checked before deciding, against all 22 recorded Runs: of 46,045
  fixes, not one consecutive pair shares a timestamp. The rule is written for a recording the app
  can produce rather than one it has produced, and for the imported and synthetic tracks that reach
  the same readers.
- **`sessions.distanceKm` needs no migration**, because it was always the value this change moves
  the readers *towards*. A Run re-measured under the new rule matches what it banked; under the old
  one it could come out short.
- **A Segment may be cut across such a leg.** It is ground now, so a stretch containing one has
  length, and `SegmentCut.TooShort` goes back to meaning what it says — a runner who stood still.
- **`measureFastestEffortSeconds` keeps its own walk of the track and its existing behaviour.** It
  skips the leg, as it always has, but for the reason stated here rather than the one it used to
  give: not that the leg carries no ground, but that an effort is a speed and this leg holds none.
