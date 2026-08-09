# A Prescription stands on the Runs it was shown

A Prescription records which Runs the coach was shown to arrive at it, and when
one of those Runs is deleted the Prescription is taken back — the coach's
previous Prescription standing up in its place, and the Stage's own Workout only
once there is nothing left standing (#156).

Deleting a Run took the row out of the database and left the coaching reasoned
from it on the card: intervals dialled back because of Runs the runner had just
thrown away, under a debrief explaining a Run nobody has any more. A Prescription
was dropped by testing mode, a Stage advancing, a plan changing, and its own
14-day expiry; "the Run it was about is gone" was not on the list, so the
workaround was to cycle testing mode and turn AI sharing back on by hand.

Falling back to the Stage's own Workout was the obvious fix and the wrong one.
The Prescription standing is progression the runner earned over several Runs, and
most of those Runs are still in history — resetting to day one of the Stage
throws away work that was never in question because of a Run that was.

So the coach's last two Prescriptions are kept, each with the Runs it stood on,
and a delete unwinds one step. What replaces a Prescription is the best coaching
the remaining history still supports, and that is decided from storage rather
than by asking the coach again: re-asking would put a network round trip inside a
delete, and — because a recorded Run does not write down its Run Type — would
have the coach judging a Run it cannot classify.

## Consequences

- **Two generations, and no deeper.** The Runs a Prescription stands on are the
  last three of the Stage, so a third Prescription back was reasoned from Runs that
  have mostly been superseded anyway. Once both are poisoned by one delete — which
  a single *older* Run can do, because consecutive Prescriptions are reasoned from
  an overlapping three — the Stage's Workout is the honest floor.
- **A promoted Prescription keeps its own date.** One written a fortnight ago
  reads as nothing standing, and the plan runs as written. Re-stamping it would
  quietly grant a fortnight-old judgement another fortnight.
- **The debrief and the numbers are one write.** They were two, which was safe
  while both were only ever dropped together; a rollback that moved one and not
  the other would leave a card whose text and intervals came from different
  evaluations.
- **A graduation's debrief is not coaching about a Run.** "You have finished this
  stage" stands with no Prescription under it by design, and a rollback leaves it
  alone: nothing standing is nothing to take back, which is also what stops a
  graduation's own message being wiped by the next unrelated delete.
- **A Prescription that recorded no provenance is taken back by any delete of a
  shareable Run.** Every Prescription written before this is such a one, and
  guessing the other way would have the app claim coaching survived a delete it
  can know nothing about. It costs one standing Prescription, once, on the first
  delete after the upgrade.
- **Deleting a Run kept out of AI training disturbs nothing.** It was never
  evidence, so no Prescription can have stood on it — asked of the row before it
  goes, because afterwards there is nothing left to ask.
- **A Run that only moved Fitness and Fatigue is not provenance.** The curves are
  42 days of arithmetic that one Run nudges; the three Runs the coach read the
  intervals off are the evidence. Counting the curves would have almost every
  delete cost the runner their progression.
- **A correction is not a deletion.** A Stated Distance arriving later leaves the
  coaching alone, exactly as it leaves the Run's own Stage evaluation alone
  ([ADR 0008](0008-a-stated-distance-is-a-real-distance.md)).
- **The hold is not a new Prescription.** Paring a standing Prescription back to the
  Workout when the coach cannot be reached ([ADR 0006](0006-the-coach-adjusts-the-long-run-only.md), #248)
  keeps its debrief, its date and its provenance, so it is still taken back by the
  Runs it stood on.
- **An evaluation is refused whole once its evidence has gone.** The coach takes
  seconds to answer, and the runner can spend them deleting one of the Runs it was
  shown. All three endings — the reply written down, the hold when the coach could
  not be reached, and the graduation with its message — ask again whether the
  evidence is still in history, and turn themselves away entirely if it is not.
  Applying any of them to whatever the delete left would write down a Prescription no
  later delete can answer for, because a Run cannot be deleted twice. The graduation
  is the one that can least afford it and the easiest to be sure about: it rests on
  the same three Runs, most of a Stage's requirement is answered by one Run or two of
  them, and there is no un-graduate — a Prescription records the Runs it stood on and
  a later delete unwinds it, while a Stage only ever advances. So a graduation refused
  is the conservative direction: it comes late, on the next Run, rather than being
  granted for good on evidence the runner has already thrown away.
