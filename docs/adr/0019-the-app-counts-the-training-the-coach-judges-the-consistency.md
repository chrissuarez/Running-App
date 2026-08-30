# The app counts the training, the coach judges the consistency

The first Stage of the beginner plan asks for **"Complete 4 weeks of consistent Zone 2 training"**.
[ADR 0016](./0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md) left it with the
coach on purpose: what counts as *consistent* is a real judgement, and it cannot be written as a
distance in a time.

The coach was then asked that question through a keyhole. It is shown the Stage's last **three**
Runs (`getLast3AiEligibleRunsOfStage`), and every eligible session competes for those three slots —
an Easy Run, a Walk, a Quality Run — while only a **Long** Run triggers an evaluation at all
([ADR 0006](./0006-the-coach-adjusts-the-long-run-only.md)). A runner doing a Long, an Easy and a
Walk each week therefore never presents more than about a week of training, whatever they have
really done.

On 2026-08-30 that stopped being a theoretical risk. Three Long Runs into a Zone 2 block, the home
screen told Chris:

> Regarding your current stage requirement to complete 4 weeks of consistent Zone 2 training, it
> looks like this stage is only just beginning. With only two Run/Walk sessions recorded so far,
> it's too early to confirm the full 4 weeks of consistency.

The coach was not wrong about what it could see. It was answering honestly from a window that had
his Aug 16 and Aug 22 Long Runs pushed out of it by a Walk and an Easy Run. The app was reading its
own record back to him as less than he had done — and it does not improve with time: the same three
sessions a week keep the window the same width forever, so this Stage could never be confirmed from
the window it was judged in.

## The decision

**Where a requirement is about how much training happened, the app counts it and tells the coach the
number. What the coach is left to judge is the word the plan actually left open.**

`getAiEvidenceRunDaysOfStage` reads the day of **every** qualifying Run of the Stage — no limit —
and `stageTrainingRecordOf` buckets them into Monday-starting weeks. The prompt is handed the total,
the span, and the per-week counts, and asked to judge whether that is *consistent*. "Four weeks"
becomes arithmetic the app does; "consistent" stays a judgement, exactly as ADR 0016 said it should.

**The span is elapsed training, never the number of week rows.** A Monday-starting bucket is a place
to put a Run, not a week of training: a first Run on a Sunday followed by one on each of the next
three Mondays touches four buckets fifteen days in. Handed "across 4 weeks", a coach could grant the
four-week requirement a fortnight early, and a graduation cannot be taken back. So the length the
coach is told is `daysTrained` and the full seven-day weeks they make (`weeksTrained`), and the rule
beside the record refuses the rows as an answer. The bucket count survives only as
`calendarWeeksSpanned`, which decides whether the listed weeks are the whole record or its tail.

The three alternatives, and why not:

- **Show more Runs.** It grows the prompt and does not scale: four weeks of training is more than
  any window of Runs a prompt can hold, and the next requirement is longer than the last.
- **Show the last three Runs of the kind being evaluated.** Cheaper, and it does fix the crowding —
  but three Long Runs still span three weeks, so a four-week requirement stays unanswerable. It also
  takes the Easy Runs and Walks out of the debrief, which are there because a week of walking is not
  a week of rest.
- **Reword the requirement so three Runs can answer it.** Honest, and it was seriously considered —
  a requirement the app cannot verify is one the coach is guessing at. It was declined because the
  requirement is not the thing that is wrong: four weeks of consistent Zone 2 work is exactly what
  the first Stage of a beginner plan should ask for, and rewording it to fit a keyhole is fixing the
  runner's plan to suit the app's prompt.

## A qualifying Run is the same Run a graduation may rest on

Stated once, in `SessionRepository.isStageEvidence`: a structured Run, recorded under this Stage,
that the runner did not mark a Walk and did not keep from the coach. The graduation guard asks it of
the three Runs the coach was shown (#287); the record asks it of the whole Stage. Two answers to
that question would be a count telling the coach a Stage holds evidence the guard then refuses to
graduate on.

The one place they could still part is the Run that has just finished, whose stored row may predate
the finish sheet — and the sheet is where a Walk is marked (#297). The record therefore drops that
Run when the row it was handed says it is no longer evidence. Only ever a removal: the sheet turns a
Run into a Walk and never back.

## What is still fenced

- **The record names no Runs.** A graduation still names timestamps out of the three Runs shown,
  because those are the only rows a name can be resolved against (#287). A date from the record
  resolves to nothing and would refuse a graduation the runner earned.
- **It counts and measures nothing.** It says nothing about distance, time or heart rate, so it can
  never answer a requirement written as a distance in a time — which is the coach's to judge in any
  case only where ADR 0016 has not already taken it away.
- **The weekly Effort totals and the Goals stay out of the graduation**, on their old terms. Those
  measure something else. This is the same evidence, counted.

## The residue

A Prescription stands on the Runs it was shown, and deleting one of them unwinds it
([ADR 0013](./0013-a-prescription-stands-on-the-runs-it-was-shown.md), #156). The Runs behind the
record are not in that provenance, so a graduation can rest in part on a Run whose later deletion
unwinds nothing.

That is accepted, for the ADR's own reason first: what ADR 0013 excludes is a Run that only moved a
*measurement* — the Fitness and Fatigue curves — and this is a count of Runs, not a description of
one. A graduation is never taken back at all (#290), so there is nothing on that side for a deletion
to undo; what #156 unwinds is a standing Prescription, and that still stands on the three Runs it
was shown. And putting twenty counted Runs into the provenance would throw a sound Prescription away
because one of them was deleted — a worse trade than the one this leaves open.
