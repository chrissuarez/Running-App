# Heart rate is a readout, not a gate

The app coached to a target zone by treating any excursion above it as a
failure: the voice ordered a walk break, the walk was labelled HR-triggered,
and the run's intervals were scored on how long the runner held out before
crossing the line. That score went to the AI coach as a red-flag metric, and
the coach shortened the next run accordingly.

Every one of those judgements rested on a number that could not carry them.
`classifyIntervalCompletionBand` divided *the second heart rate first went
above target* by the interval's planned length and called the result
completion — so crossing the line 40 seconds into a 3-minute interval logged a
0.22 ratio and the name **"severe breakdown"**, for an interval run in full by
a runner who was never out of breath. At a Zone 2 ceiling of 126 bpm and a
jogging heart rate of 140, that was every interval of every run. Shorter runs
lowered the 30-day maximum, which lowered the growth ceiling in
`clampAiResponseByRecentLoad`, which had no floor. The ratchet only turned one
way, and nothing in it was about fitness.

So heart rate stops driving anything. It is recorded, charted, and shown; it no
longer prescribes a walk, no longer marks a walk as its own, and no longer
reaches the AI coach as interval quality. The above-target cue survives as
advice — "ease off slightly" — because a coach that never mentions heart rate
is not coaching, but it asks for a change of effort and nothing follows from
ignoring it.

## Considered options

**Fixing the zone band instead** — the first suspect, and insufficient on its
own. Recomputing the target from heart-rate reserve (ADR 0004) roughly halves
the time spent above it, and still leaves a third of an easy run outside the
band. Any gate anchored to a zone would have kept firing.

**Keeping the metrics and correcting the prompt** — telling the model that a
trigger means a line was crossed rather than that the runner stopped. Rejected
because the same request would have to be understood correctly on every run,
against a metric elsewhere labelled a red flag. A number that has to be
explained away is better deleted.

**Measuring real breakdown instead** — replacing the line-crossing with whether
the runner actually walked. Rejected because the app cannot tell. There is no
pace- or cadence-based walk detection; `totalTimeSpentWalkingDuringRunInterval`
counts seconds spent *above target*, not seconds spent walking. The app has
never known, and inferred it from heart rate — which is the error itself, one
layer down. Worth building later; not worth blocking this on.

## Consequences

- **The runner is the judge of effort.** The design now trusts the talk test
  over the formula, because that is what the evidence supported: the cue fired
  while the runner could still hold a conversation. There is no longer any
  mechanism by which the app can decide a run went badly.
- **The AI coach is told less, and less of it is wrong.** It keeps duration,
  average heart rate, walk-break count and Stage. It loses the whole interval
  quality block.
- **The words on the run detail screen change with the model.** "Severe
  breakdown", "poor tolerance" and "strained completion" are the same
  line-crossing bucketed three ways, and they go with it. What remains is
  named for what it measures — see *Trigger* in `CONTEXT.md`.
- **No absolute high-heart-rate cue exists, and this does not add one.** Every
  cue in the app is relative to the target zone; there is no "you are at 95% of
  maximum, stop". Loosening the gate therefore weakens no safety net, because
  there was none. Whether one should exist is a separate question.
