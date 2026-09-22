# The requirement is judged over the training, not Run by Run

[ADR 0023](./0023-the-graduation-is-a-typed-judgement-the-debrief-is-prose.md) moved the graduation
onto a typed judgement, and asked it **one Noul per candidate Run**, each question forbidden to read
the others. That independence was the point: a Run either meets the requirement on its own numbers
or it does not, and a Walk sitting beside it could lend it nothing.

Stage 1's requirement is "Complete 4 weeks of consistent Zone 2 training."

No single Run shows four weeks of anything. The Stage Training Record that travelled beside each
question counts Runs per calendar week and deliberately measures none of them (ADR 0019) — no heart
rate, no zone, no distance. So the per-Run question had two ways to be wrong and no way to be right:
answer honestly and no Run ever clears the bar, so the Stage never graduates; or answer from the
candidate's own Zone 2 seconds, and a Stage whose other three weeks were run hard graduates anyway —
for good, because nothing takes a graduation back (ADR 0020).

This was not a regression. The old Gemini path was shown three Runs and the same measurement-free
record, and had the same gap; #514 neither caused it nor closed it.

## Measured, not argued

Three shapes were put to `jev-1.13.0` against Chris's own runs (#516, phone test 2026-09-22). The
threshold is `GRADUATION_NOUL_THRESHOLD`, 0.8:

| Shape | Noul |
|---|---|
| One question per Run, as #514 shipped it | 0.51 |
| One question per Run, plus each week's Zone 2 seconds | 0.43 |
| **One question over the whole Stage** | **0.87** |

The middle row is the finding worth keeping. Enriching the record made the per-Run question **worse**
rather than better: a question about one Run, handed four weeks of evidence, is a question whose own
subject is the smallest part of what it is being asked about.

## Decision

**The judge is asked one question per evaluation, about the Stage's training as a whole.**

`requirement_met` is a single Noul over a state holding the requirement, the Stage Training Record,
and every candidate Run keyed by its own database id. The Stage graduates when that one probability
comes back at or above the threshold.

## What this keeps

- **The model still copies nothing.** The answer has to arrive under `requirement_met`, the name the
  question was asked under. An answer keyed `runs`, `47` or `run_47` is a key lifted off the state
  the model was shown, and it answers nothing — which is the whole of what #287 was about.
- **The app still chooses the evidence.** `isStageEvidence` decides which Runs are in the request at
  all, so a Walk or an Open Run is still never in front of the judge and there is still no rule to
  forbid it.
- **Unreachable is still not a no.** One question means one way to fail rather than several: a reply
  that does not answer `requirement_met` with a probability is no judgement, and the evaluation is
  thrown away rather than read as a refusal.
- **Everything else in ADR 0023 stands** — the judge asked before the debrief, both-or-nothing, the
  threshold, and `requirementIsTheAppsToAnswer` never reaching the judge at all.

## What this gives up

**The graduation no longer names a Run.** `GraduationVerdict.Judged` carries a boolean where it
carried a set of Run ids, because the answer is about the training and not about any one Run of it.

Nothing was resting on those ids. What a graduation is guarded by is
`AiTrainingContext.sourceRunIds` — the three Runs the whole evaluation was reasoned from, re-checked
under the provenance lock before the write, and deliberately wider than the judge's answer ever was
(#287). That guard is unchanged, and it already covered every Run the judge was shown.

## The residue, named

The Runs counted in the Stage Training Record are still not in `sourceRunIds`, so a graduation can
still rest in part on a week whose Runs a later delete would not unwind. ADR 0023 accepted that for
the same three reasons, and they are unchanged by asking one question instead of several.
