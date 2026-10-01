# A graduation is refused when a delete changes its weeks

[ADR 0024](./0024-the-requirement-is-judged-over-the-training-not-run-by-run.md) made the Stage
Training Record the main evidence a judged graduation rests on. The Runs it counts were still
outside `AiTrainingContext.sourceRunIds`, so a Run from week 1 deleted during the judge's round trip
changed the weeks the graduation was granted on, and nothing refused it (#519).

## Decision

The Runs the record counted are named in `AiTrainingContext.stageTrainingRunIds`. Before a judged
graduation is written, under the provenance lock, they are re-checked beside `sourceRunIds`. If one
has left history, the graduation is refused whole — the move, the debrief and any Prescription with
it, exactly as a deleted `sourceRunIds` Run refuses it.

They are guarded for the graduation **only**. A Prescription still stands on the three Runs it was
shown ([ADR 0013](./0013-a-prescription-stands-on-the-runs-it-was-shown.md)), so neither the coach's
reply nor the hold reads `stageTrainingRunIds`, and no Prescription records them as its provenance.

## Considered

- **Leave it, and write the argument out again.** Cheapest. Rejected: under ADR 0024 the count is the
  evidence, and a graduation cannot be taken back, so refusing one in the seconds a delete can
  change it is worth one more query.
- **Put every counted Run into `sourceRunIds`.** Rejected, as ADR 0019 rejected it: a twenty-Run
  Stage would lose a sound Prescription because one old Run was deleted.
- **Re-count the record and compare.** Rejected: a re-count also moves when the day turns over or
  the finish sheet marks the just-finished Run a Walk, and neither is a delete. Asking whether the
  counted Runs still exist catches exactly the delete and nothing else.

## What stays open

A delete *after* the graduation is written unwinds nothing. That is
[ADR 0020](./0020-a-graduation-is-the-apps-to-grant-and-the-runners-to-undo.md) working: nothing
re-judges a graduation once it is granted, and the runner's own move back is how one is undone.
