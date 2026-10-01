# The judge's record carries where each week's heart rate was

[ADR 0024](./0024-the-requirement-is-judged-over-the-training-not-run-by-run.md) asked the Graduation
Judge one question over the Stage's training as a whole. Measured by hand against `jev-1.13.0`, that
shape scored Chris's Stage 1 at **0.87**. The shipped app, asked on a fair replica of his training on
the phone (#527), scored **0.79**. The threshold is 0.8, so Stage 1 still could not graduate (#528).

## What the gap was

Each difference between the two requests was put to the live model on its own, five times each, on
the same staged data:

| Request | Noul |
|---|---|
| Shipped request, `jev-latest` | 0.74–0.83 |
| Shipped request, `jev-1.13.0` pinned | 0.75–0.80 |
| Hand request (#516) | 0.86–0.89 |
| Hand request, weekly Zone 2 seconds taken out | 0.71–0.75 |
| Hand request, Chris's real Runs in place of its made-up one | 0.80–0.86 |
| Shipped request, hand-test question wording | 0.45–0.52 |

- **Not one bad reading.** The #527 reading of 0.79 sat in the middle of the shipped request's
  spread, so the gap is real and not noise.
- **Not the model.** `jev-latest` already answers as `jev-1.13.0`. Pinning it changes nothing.
- **Not the wording.** The hand-test wording scores far *worse* on the shipped state.
- **The record.** The hand request's record carried each week's Zone 2 seconds and said so. The
  shipped one said it "measures nothing", and told the judge never to assume any counted Run was in
  any zone. Take the weekly seconds out of the hand request and it drops to the shipped score.

So the judge was asked whether four weeks were *consistent Zone 2 training* and handed a record that
said, in so many words, it could not tell.

## The decision

**The judge's copy of the Stage Training Record carries each week's seconds in every zone.**

`calendarWeeksSecondsInZone` sits beside `calendarWeeks`: for each listed week, the seconds that
week's qualifying Runs spent in zones 1 to 5, summed off the rows (`zone1Seconds`..`zone5Seconds`,
already measured second by second against the runner's own zones). Every zone is written, zeroes
included. `measures` now says what the record is.

**And the question says a zone requirement is about the kind of training.** One sentence: where the
requirement names a heart-rate zone, read where each week's seconds were spent, and weigh time above
that zone against it.

Both halves are needed. With the weekly zones and no sentence, Chris's real weeks scored 0.90–0.91 —
and so did the same weeks with Zone 2 and Zone 4 swapped (0.83–0.88). The judge counted the weeks and
read past the zones. A judge that passes a Stage run in Zone 4 is not judging "Zone 2".

## Measured, final request

Built by `buildGraduationRequest` from the #527 staging (17 Runs, 5 full weeks), asked live:

| Record | Noul | Verdict |
|---|---|---|
| The fair five weeks | 0.76–0.81 | on the line |
| The same weeks, Zone 2 and Zone 4 swapped | 0.66–0.75 | no |
| The Stage's first two weeks only | 0.06–0.07 | no |

The fair weeks sit on the threshold, and that is the honest reading of them, not a fault in the
question: in the weeks of 14 and 21 September about half the time was above Zone 2. Chris chose this
over the version that passes them every time (2026-10-01), because that version also passes the
swapped weeks. A refusal costs one more Long Run, which asks again. A wrong graduation is for good.

## What changes, and what does not

- **ADR 0019's fence "it counts and measures nothing" is lifted for the judge's copy only.** The
  coach's debrief prompt still renders the count and still says it measures nothing — true of what
  that prompt shows, and the debrief has no graduation to grant.
- **Which Runs are counted is unchanged.** Same query, same `isStageEvidence`, with five more
  columns read.
- **The threshold is unchanged.** Chris ruled out lowering it (#528).

## Not carried

The weekly record sends no strap-gap seconds, though each Run's own state does
(`secondsWithoutHeartRate`). A week whose strap dropped reads as less zone time, which leans towards a
no, the safe side. It was left out because every number above was measured without it.

## The residue

The record's Runs are still outside `sourceRunIds` (#519). ADR 0019 accepted that partly because the
record was "a count of Runs, not a description of one". It is now a description of each week, so
that argument is weaker. Deleting a Run still unwinds no graduation. #519 already carries that.

Since [ADR 0026](./0026-a-graduation-is-refused-when-a-delete-changes-its-weeks.md) a delete
landing during the judge's round trip refuses the graduation. One landing after it unwinds nothing.
