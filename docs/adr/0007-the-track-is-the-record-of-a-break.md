# The track is the record of a Break, and Covered is one number

Four issues (#165, #204, #195, #162) reported the same fault from four angles: a
Run's Splits did not total its distance, its Moving time dropped stretches its
distance kept, a Pause before the first fix was never written down, and the
Run's totals disagreed with each other whenever the signal went. They were filed
as four bugs. They are one: **Elapsed, Moving and Covered were each derived from
a different reading of what the recording covers.**

Three code paths answered "how far?" — the live recorder, the track reader, the
rescue path — and two of them agreed. Moving time treated every hole in the
track as rest; distance treated the same hole as ground covered. Neither was
wrong on its own terms, and together they flattered pace by roughly two seconds
per kilometre for every ten seconds of lost signal, unbounded in bad GPS.

**The track is the single record of where a Run stopped.** A Pause is written on
the fix that resumed it; everything else that leaves a hole is an Outage or a
stretch of fixes too vague to trust. Elapsed, Moving and Covered are all derived
from that one record, so they cannot drift apart.

**A Break carries its straight line.** The ground between the last good fix
before a Break and the first one after it counts towards Covered — for the live
recorder, the track reader and the rescue path alike. A straight line is never
longer than the route the runner actually ran, so counting it can only
under-state a Run, never flatter it. The Break is still marked unrecorded, so
nothing draws it on the map, climbs across it, or joins two heart-rate lines
over it.

**Only a Pause is settled rest.** Every other stretch, hole or not, meets the
same 30-minute-mile threshold every other leg meets. A tunnel run through reads
as Moving; a café stop where the signal also died reads as a straight line going
nowhere, and reads as rest. The stretch judges itself, because once a Break
carries a distance the ordinary rule has everything it needs.

## Considered options

**A table of Pause intervals per Run** (#162's proposal, and the shape the triage
note assumed the other three would build on). Rejected: it is a *second* record
of a thing the track already records, and two records that can disagree is the
exact failure this ADR exists to end. It also could not deliver what it was
wanted for. Its first motivation — auto-paused fixes landing in the GPX export
as though they were run — was already delivered by #84, which stopped storing
them. Its second — placing heart-rate rows written before v16 on the wall clock
exactly — is unachievable by any design: those rows belong to Runs recorded
before pauses were written down at all, so there is nothing to back-fill a table
from. 15,590 such rows across 8 Runs; their Pauses were never recorded and
cannot be recovered.

**Letting the track win instead of the recorder.** A straight line across
unwitnessed ground is a guess, and refusing to claim it is the purer position.
Rejected because it is the *more* wrong number — it drops ground the runner
certainly covered — and because every Run already in history has a saved
distance that would no longer match a recount of its own track, including the
Runs holding medals.

**Excluding a Break's distance from the pace denominator instead.** Fixes the
flattered pace without touching Covered, at the cost of a pace that no longer
divides the distance shown beside it. Rejected: two numbers on one card that do
not relate to each other is how this cluster started.

**One rule for every Run, with no era flag.** Simpler to explain, and wrong for
Runs recorded before pauses were written down: a gap is the only evidence those
Runs carry, so a runner who paused, walked to a shop and resumed would have the
walk counted as Moving. The flag is the price of not rewriting history.

## Consequences

- **Runs already in history stay on the old rule.** A per-Run flag marks the Runs
  whose Pauses are written down; everything recorded before this lands keeps the
  20-second-gap fallback. Deliberately not decided by comparing a Run's start
  date against a ship date — that is a constant the code would have to keep
  explaining, in exchange for correcting a dozen Runs that are already measured
  and already scored.
- **The record book is rebuilt once.** Splits, charts and distances are worked out
  on read and pick the new rule up immediately; medals are banked. Without a
  rebuild a Run's page could show an effort that disagrees with the medal beside
  it until some unrelated delete happened to mend it.
- **A Best Effort may be measured across a Break.** The straight line under-states
  the ground, so an effort spanning a Break comes out slower than the truth: it
  can cost a medal, never inflate one. Refusing to measure across Breaks would
  instead let a Run through a tunnel place nothing at all.
- **Slow satellite lock still counts as running.** The wait between START and the
  first fix is credited unless the Run says a Pause happened in it. That is only
  distinguishable once the tracker's pause flag is cleared at START rather than
  ignored at write time — the flag survives from the previous Run's teardown, so
  today it has to be discarded, and the one case where a Pause really did happen
  goes with it.
- **Auto-pause needs no separate treatment.** It already leaves the same shape as
  a tapped Pause: its fixes are not stored, and the fix that resumes carries the
  marker.
- **A Run with no track is unaffected.** A treadmill Run has no legs to judge, so
  it has no Moving time to state and quotes its pace over Elapsed, as before.
