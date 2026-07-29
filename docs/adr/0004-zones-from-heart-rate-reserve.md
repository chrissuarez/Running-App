# Zones are sliced from heart-rate reserve, not from Max HR

Every zone edge was a fixed percentage of Max HR — Zone 2 at 60–70%, which at a
stated Max HR of 181 is **109–126 bpm**. The runner jogs at a median of 140 and
passes the talk test comfortably at the moment the app asks them to slow down.
Two exported runs put 70% and 78% of their seconds above the Zone 2 ceiling.
The band was not describing this runner's easy effort; it was describing a
formula's guess at it.

So zone edges are computed from heart-rate reserve instead:
`resting + (max − resting) × percent`, with resting heart rate stated by the
runner alongside Max HR. The same five names and percentages stay; only the
range they slice changes. Zone 2 moves to roughly 129–142 — where the runner
actually is on an easy run.

The deciding evidence was the talk test, not the arithmetic. A runner who can
hold a conversation is aerobic, and no percentage of a maximum outranks that.
Heart-rate reserve is the standard model that agrees with it, and it keeps
agreeing as fitness changes: resting heart rate falls, and the band follows
without anyone re-tuning a constant.

## Considered options

**Re-slicing the Max HR percentages** — redefining Zone 2 as, say, 70–80% of
maximum. Arrives at a similar band today with no new setting and no new
measurement, and was rejected because it is a constant chosen to fit one runner
at one moment. It carries no reason, so nothing tells anyone when it has
stopped being true.

**Anchoring zones to lactate threshold** — the model that fits the evidence
best, since the complaint is precisely that the aerobic ceiling sits higher
than a percentage of maximum predicts. Deferred rather than rejected: it needs
a threshold number the runner does not have, and obtaining one is a test in its
own right. Reserve is available tonight from a watch already worn.

## Consequences

- **A resting heart rate becomes a stated setting**, with the same standing as
  Max HR: load-bearing, deliberate, and never guessed.
- **History is recomputed, not stranded.** `SessionRepository.setStatedProfile`
  re-tallies every past Run's zone seconds from its stored per-second samples,
  and heart-rate samples are never pruned. Both heart rates go through that one
  door, so the whole history stays comparable and no migration is needed.
- **A Run live at the moment of the statement keeps its own numbers.** The
  re-tally visits finished Runs only, so stating a resting heart rate mid-run
  leaves that one Run banded on the profile it pinned at START
  ([ADR 0002](./0002-the-run-is-a-rulebook-not-a-service.md)) — permanently, as
  nothing sweeps it after it finalizes. This is the collision between "every
  statement re-bands everything" and "a Run's zone times are what the runner
  actually heard called out", and it is settled in favour of the Run: rewriting
  a run into times the coaching never used is the worse lie. The cost is one Run
  outside the model, reachable only by changing the setting mid-run. Max HR's
  first-ever set has always behaved the same way. Reviewers have raised this
  twice; it is a decision, not an oversight.
- **The band gets closer, and does not arrive.** Reserve roughly halves the time
  spent above target and still leaves about a third of an easy run outside it.
  That is tolerable only because heart rate no longer gates anything
  ([ADR 0003](./0003-heart-rate-is-a-readout-not-a-gate.md)) — under the old
  model this change alone would not have fixed the runner's problem.
- **Nothing else moves.** `zoneLowerBpm` is the only place a percentage becomes
  a BPM; the chart, the cues, the banding and TRIMP all read through it.
