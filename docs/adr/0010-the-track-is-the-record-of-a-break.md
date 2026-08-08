# The track is the record of a Break

A Run does not always get recorded end to end. The runner holds Pause at a crossing and GPS is torn
down for the length of it; or the signal simply goes — a tunnel, a stairwell, a street of tall
buildings — while the Run carries on. Both leave the same shape in the stored track: two fixes with a
hole between them. This is the vocabulary for that hole and the one rule about what it is worth.

**Break** is the hole. **Pause** is the one the Run wrote down; **Outage** is the one nobody
declared. The words are in [CONTEXT.md](../../CONTEXT.md); what follows is why the distinction has to
exist at all.

## Three paths were answering "how far?", and one disagreed

The live recorder banks distance as the Run happens, from the last accepted fix to the next one. It
never stops doing that across an Outage — the baseline survives the silence — so the straight line
across the tunnel is in the total saved on the Run and shown in the summary. On a Pause it is told to
forget the baseline (`SessionRecorder.discardLastFix`), so a Pause costs it nothing.

The readers did something else. `measureTrack`, which the Splits table and the charts are cut from,
and `measureTrackDistanceKm`, which rebuilds the total for a Run the rescue pass finishes, both threw
the whole leg away — Pause and Outage alike — on the reasoning that the recording does not say where
the runner went, so nothing measured off it may claim the ground.

That reasoning is sound in isolation and produced a Run whose Splits did not add up to the distance
printed above them (#204). Two answers to one question is the defect, and it is the defect whichever
answer is the better one.

## The recorder's number is the Run's Covered distance

**Every path that measures a track now carries a Break's straight line, and the readers were the ones
that moved.**

Three reasons, in the order they mattered:

- **The runner did cover that ground.** A straight line between two fixes is never longer than the
  route actually run between them, so counting it can only ever under-state a Run. The other
  direction — refusing it — over-states nothing and *under-states everything*, which is not the safer
  error, only the quieter one.
- **The summary distance is already the recorder's**, and it is what history holds, what the coach is
  sent, what the record book was scored on, and what the runner has been reading all along. Moving the
  authority to the track would mean every saved Run disagreeing with a recount of its own track, on a
  number nobody would think to doubt.
- **Nothing already banked changes.** Making the readers agree with the recorder is a change to what
  is derived on read. Making the recorder agree with the readers would have been a change to what has
  been written down for a year.

## A Pause carries nothing, and that is not an inconsistency

A Pause is the exception, and it is the exception for a reason that has nothing to do with
convenience: **the runner was not running.** GPS is torn down for the length of one, the Run's own
clock stops, and the fix on the far side of it may be a shop doorway forty metres off the route. The
recorder declines that ground deliberately; so must everything else.

So the rule is not "a Break carries its straight line". It is: *a Break carries the ground the runner
covered across it, which is the straight line when the Run was running and nothing when it was
paused.* The two cases are one rule looking at two different facts, and the fact it looks at is
written on the track.

## What "unrecorded" means now

The per-leg `recorded` flag used to carry two claims at once: *the recording does not cover this
stretch*, and *therefore it is worth no metres*. The second one is now gone, and the first one is the
whole of it.

**Unrecorded means nothing may be drawn or joined across the leg.** The route map stops the line and
starts a new one. The elevation total re-arms at the Break rather than banking the climb across it,
and the smoothing windows either side refuse to reach through. Both charts cut their traces there.
None of that has moved a millimetre; it is the part of the old meaning that was always right.

What a leg is *worth* is a separate question, answered by its metres. The two were fused because,
until there was a Splits table to add up, nothing had asked them apart.

## The track is the record, not a table beside it

The obvious alternative was to write Pauses down properly: a row per pause interval, start and end,
queried by every reader. **Rejected.** The evidence already exists on the track — every Pause, held
down or automatic, is stamped onto the fix that resumed the Run (`TrackPoint.startsAfterPause`) — and
a second record of the same fact is a second record to keep in step. It would need a migration, it
would need backfilling for history recorded before it, and the day the two disagreed the app would
have no way to say which was right.

One record, read consistently, is worth more than a better-shaped record read twice.

## Consequences

- **A Run rescued by the interrupted-run pass measures the same as one finished live.** It did not
  before: a rescued Run shrank by the width of its Outages. `measureTrackDistanceKm` is now one line
  over `measureTrack`'s legs — the only way two paths are certain to agree is to be one path.
- **A Best Effort may span a Break**, measured over the straight line plus every second it took. Both
  halves of that lean the same way: the ground is never over-stated and the clock is never
  under-charged, so a window through a tunnel reads slower than the runner was, never faster. It used
  to end the effort outright, which lost a runner a graduation they had earned.
- **A Split holding an Outage reads faster than the runner ran, and one built entirely of that ground
  shows no pace at all.** Distance moved and moving time did not, because judging whether the runner
  was moving across a Break is a separate decision (#165). This is the honest shape of that gap
  rather than a flattering one — hiding the ground to hide it would leave the table not totalling the
  Run, which is the whole defect this ADR exists to close. *Closed by
  [ADR 0012](0012-an-outage-is-a-leg-like-any-other.md): an Outage now carries its seconds too, on
  the same evidence it carries its metres.*
- **The pace *line* on the combined chart skips an unrecorded leg entirely.** Ground with no
  witnessed seconds, divided one by the other, is a sprint nobody ran; the line is for the shape of
  the Run and the Splits table is where that ground is accounted for.
- **The distance axis now has a hole in it** as wide as the ground an Outage spans, since the
  stretches either side no longer meet. The readout follows the dot across it on the map's own
  nearest-fix rule, so the scrubber does not go dead in the middle of a drag.
- **Measured, not assumed.** Replaying the five exported GPX tracks through the analysis: one Break
  over 20 s across all five runs, worth 37.0 m — 1.4% of the Run holding it. Four runs unchanged to
  the metre, and that one's Splits now total its distance. The disagreement was always the point
  rather than its size.
