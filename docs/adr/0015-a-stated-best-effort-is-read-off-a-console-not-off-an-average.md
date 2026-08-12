# A Stated Best Effort is read off a console, not off an average

[ADR 0008](./0008-a-stated-distance-is-a-real-distance.md) let a treadmill Run state how far it
went, and closed the door on the five fastest-\* Records: "there is no stretch to find, so there is
no Best Effort, so those five Records are unreachable and stay so." That conclusion was right about
the app and wrong about the runner. **The app** has no way to find a stretch inside a treadmill Run.
**The console** shows one — it counts the laps and it shows the split — and the runner is standing
in front of it reading the number off.

So a treadmill Run can state a Best Effort: **one of the five Record distances, and the time the
console showed for it.** It is ranked in the record book like a measured one and can take a Medal.
A winter of treadmill training stops being invisible to the fastest five, which is the same hole
ADR 0008 was closing at the longest distance.

## This is not the derivation ADR 0008 rejected

ADR 0008 rejected deriving a 5K time from distance ÷ duration, and every word of that rejection
stands. The two are not the same act, and the difference is not a matter of degree:

- **A derived effort is manufactured by the app.** Nobody measured it, nobody saw it, and nobody
  agreed to it. It over-credits a fast start and under-credits a negative split, and once it is
  written down nothing downstream can tell it from a stretch somebody ran.
- **A stated effort is a measurement, made by the machine and reported by the runner.** It is the
  same act as a Stated Distance, and the same act as the two heart rates every Zone edge in the app
  is built from ([ADR 0004](./0004-zones-from-heart-rate-reserve.md)). The app does not work it out;
  it is told.

The test that separates them, and the one line of this decision that must not be crossed: **the app
never computes a Best Effort it was not either measured or told.** A 6 km treadmill Run in 30:00
claims no 5 km, and stating the 5 km is the *only* way that Run ever holds one. Nothing reads a
statement out of a total, and no total is quietly turned into a statement because the runner did not
make one.

## A Run may hold several, and none of them derives another

A console shows lap times, so a single Run can honestly report a 1 km and a 5 km. They are two
separate claims about two separate stretches, and neither says anything about the other — the 5 km
does not contain a claimed 1 km, and the 1 km does not bound the 5 km. So: **one statement per
Record distance per Run**, and a Run may carry up to five.

This is what a Stated Distance did not need and is why this one costs a table where that one cost
nothing. ADR 0008 got away with a single column because a Run has exactly one distance and `runMode`
was enough to say where it came from. `runMode` is still enough to say where *these* come from — only
a treadmill Run can state one — but "up to five, each naming its distance" does not fit in a column,
so the statements are stored in their own right and the schema moves.

## Impossible is refused; unlikely is believed

A stated Best Effort is checked against the Run it belongs to, and refused when the Run could not
have contained it:

- **A time longer than the Run's own duration.** A 5 km inside a 20-minute Run did not take 25
  minutes. There is no reading of the Run under which the claim is true.
- **A distance longer than the Run's Stated Distance**, where the Run has one. A Run that covered
  4 km has no 5 km in it.

That is not the app doubting the runner, which it does nowhere else and must not start doing here. A
4:30 that should have been 14:30 is *implausible*, and implausible is believed — the same way a
treadmill Run of 300 km is believed, and corrected afterwards. What is refused is only the
arithmetically impossible: a claim about a stretch the Run does not contain is not a claim about
this Run at all.

The distance check is skipped where there is no Stated Distance, because **the two statements are
independent**. A runner who noted the 5 km split and never looked at the total has said something
true, and requiring the total first would throw it away. A Best Effort is offered on any finished
treadmill Run, distance or no distance.

Independent in what they *require* of each other, though, and not in what they may contradict. A
Stated Distance can be corrected, and a correction is the one way a claim that was possible when it
was made stops being one: state a 5 km, then correct the Run down to 3 km, and the claim is now
about a stretch the Run does not contain. **The correction takes it with it**, and mends the record
it held a place in. The alternative is a Medal standing on an impossible claim with nothing left in
the app that would ever look at it again — the refusal above would have caught it at the door and
then be powerless a minute later, which is not a rule so much as a coincidence of ordering. The cost
is real and is accepted: a runner who typed 3 where they meant 30 loses the times they typed and
enters them again. Withdrawing the distance entirely orphans nothing, because a Run with no distance
contradicts no claim at all.

## Outdoor Runs are untouched

An outdoor Run's Best Efforts are measured, and it is offered no statement — the same rule, for the
same reason, that keeps a Stated Distance to treadmill Runs. This keeps the two classes from ever
meeting: no Run holds a measured effort and a stated one at the same Record, so nothing has to
decide which of them wins, and there is never a mixed claim to explain. An outdoor Run whose GPS
recorded nothing is still not rescued this way.

## What correcting and withdrawing one does

Exactly what ADR 0008 settled for a Stated Distance, because it is the same problem:

- Stating one, or correcting one **downward** — to a faster time — re-scores that Run and refreshes
  the history backup.
- Correcting one **upward**, or withdrawing it, rebuilds that Record from all of history instead.
  Only the top three are ever banked, so the Run that should move up behind a demoted Medal exists
  nowhere but in the sessions, and re-scoring this Run alone cannot find it. That is the mend a
  deletion already owes, and it goes through the same door.

Note that "the correction that mends" runs the opposite way from a Stated Distance: there, a
*smaller* distance is a worse claim; here, a *longer* time is. The rule is the same rule — a claim
made worse can demote a Medal — and it is the direction of the Record that flips, which is already
the one thing `RecordType.lowerIsBetter` exists to say.

The Stage evaluation is not re-run, for the reasons ADR 0008 gives at length. Nothing about a stated
Best Effort changes that: it is a judgement made once, about one Run, and there is nothing to replay
it from.

## Consequences

- **ADR 0008's "those five Records are unreachable and stay so" is superseded**, and only that
  sentence. Its rejection of derivation is not weakened — it is the load-bearing rule this decision
  is built on.
- **`Records.kt`'s eligibility prose changes again.** The fastest five are barred to a treadmill Run
  that has stated nothing, and open to one that has. The reason given must stay "there is no stretch
  to find", not "the number cannot be trusted" — the first is still true and the second was never
  the argument.
- **A stated Best Effort is shown as a measured one is shown.** The record book does not mark them
  apart, because the runner is not being asked to keep two classes of Record in their head. A Run's
  page is where the statement is made and corrected, and that is where it is visible as a statement.
- **The card is absent where there is nothing it could hold.** A treadmill Run stated at 600 metres
  contests no record distance, and a card offering five chips it must refuse would exist only to say
  no. It appears the moment there is either a distance to claim or a claim already made — and a claim
  already made always shows, even one a later correction should have taken away, because a row the
  runner cannot see is a row they cannot withdraw.
- **Splits and a Route are still out of reach.** Both need a track, and typed numbers do not make
  one. A Run holding five stated Best Efforts still has no route and no splits table.
