# The graduation is a typed judgement; the debrief is prose

One model was doing two jobs that want opposite things.

`evaluateProgress` asked Gemini for a JSON object holding a prescription, a debrief **and**
`graduatedToNextStage`. Everything fragile in `AiCoachClient` grew around that last field:

1. A markdown fence stripped off the reply with two `String.replace` calls.
2. A Gson parse where any failure at all became `null` — no debrief, no prescription, the whole
   evaluation gone.
3. `GraduationEvidenceTimestampsAdapter`, a hand-written `JsonDeserializer`, existing purely because
   the model sometimes sent a bare number where a list was asked for — and a throw on that one field
   landed on the whole parse.
4. Roughly a dozen `CRITICAL RULE` lines in `buildEvaluationPrompt`, most of which only said "that
   row is not evidence": not a Walk (#275), not an Open Run, not a Goal (#82), not a weekly Effort
   total (#66), not the Workout (#246), not this record's rows (#289).
5. `SessionRepository.evidenceRunIdsNamedBy`, re-checking all of it afterwards, because a sentence in
   a prompt is a promise the code has to keep.

The worst of it was the naming rule (#287). To prove a graduation rested on a Run the app agreed
could answer the Stage, the coach was made to copy that Run's `timestamp` **digit for digit** out of
the prompt so the app could resolve it back to a row. A miscopied digit was indistinguishable from a
refusal, two Runs sharing a start had to be dropped as ambiguous, and a graduation cannot be taken
back.

## Decision

**The graduation is decided by a typed judgement, before the debrief is written. The debrief is then
told the answer.**

TypeSafe's System One (`POST https://api.typesafe.ai/v1/systemone`, the `jev-latest` model) is asked
one Noul **per candidate Run**, keyed by that Run's own database id. The candidates are exactly the
Runs `isStageEvidence` already accepts — a structured Run recorded under the Stage that the runner
did not mark a Walk and did not keep from the coach. A Run answers the requirement when its
probability comes back at or above `GRADUATION_NOUL_THRESHOLD`, and the Stage graduates when at
least one does.

Gemini keeps the debrief and the prescription, which is what a generative model is right for, and is
handed `graduating` as a fact about the runner rather than a flag to set.

## What this deletes

- `GraduationEvidenceTimestampsAdapter`, and the whole class of failure where one unreadable field
  cost the runner their debrief and their prescription.
- `AiCoachResponse.graduationEvidenceRunTimestamps` and `AiCoachResponse.graduatedToNextStage`.
- `AiTrainingContext.requirementEvidenceRunIdsByTimestamp` and `evidenceRunIdsNamedBy` — the map
  keyed by a shown timestamp, the all-or-nothing resolve, and the shared-start ambiguity with it.
- Every prompt rule whose only job was fencing something out of `graduatedToNextStage`. **The model
  never copies a number**, so the app asked about run 47 and gets run 47's answer back; and a Walk is
  never put in a question, so there is nothing to forbid answering from.

## What this does not change

- **`requirementIsTheAppsToAnswer` is untouched** (#290, [ADR 0016](./0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)).
  A distance in a time is still the app's own, measured in Kotlin, and such a Stage is never put to
  the judge at all. Two paths able to grant the same graduation would be one of them granting it
  twice.
- **The app still counts and the judge still judges** ([ADR 0019](./0019-the-app-counts-the-training-the-coach-judges-the-consistency.md)).
  The Stage Training Record travels in the judge's state, and the full weeks are handed over as a
  field rather than as rows with a rule about not counting them.
- **A graduation is still granted forwards and never withdrawn** ([ADR 0020](./0020-a-graduation-is-the-apps-to-grant-and-the-runners-to-undo.md)).

## Both or nothing

Two services now, so two ways to be unreachable. The judge is asked **first**, and an unreachable
judge ends the evaluation exactly where an unreachable coach ends it: the standing prescription is
held at the Stage's Workout (#248) and nothing else is written. A judgement nobody made must not be
read as a no, and it must not be read as a yes.

An empty answer is different and is not a failure. It is a judgement — these Runs do not meet the
requirement — and the evaluation carries on to write the debrief and the prescription under the
Stage the runner is still in. That is the answer on nearly every run.

A build with **no** `TYPESAFE_API_KEY` is different again, and follows the rule #76 already set: "we
cannot ask" and "we asked and got nothing" are two different answers. No Stage graduates, and the
coach goes on writing debriefs as before.

## The threshold

`GRADUATION_NOUL_THRESHOLD` is 0.8 to begin with, and it is a real number rather than a vibe because
Jev is calibrated. It sits high because the two errors are not equal: a graduation granted wrongly is
granted for good, while one refused wrongly costs a single Run and the next evaluation asks again. It
is to be moved against Chris's own runs.

## Costs accepted

- **A hand-written HTTP call.** TypeSafe ships Python and JavaScript SDKs and no Kotlin one, so this
  is `HttpURLConnection` and Gson, in the shape `WeatherClient` already uses here.
- **A second round trip per evaluation**, sequential rather than parallel, because the debrief has to
  be told the verdict. An evaluation is a background after-Run task and nothing waits on it.
