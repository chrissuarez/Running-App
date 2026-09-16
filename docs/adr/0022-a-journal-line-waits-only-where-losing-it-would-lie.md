# A journal line waits only where losing it would lie

The Run Journal (#310) waits for some lines to reach disk before `RunJournal.write` returns and only
queues the rest. The waited set was first described as "the lines whose absence is read". Review
after review then found one more line whose loss a reader would feel — `run-started` in PR #311,
three rounds running — and by that test every line in the enum qualifies. #312.

## The criterion

Every absence inference the journal licenses has the shape **"X with no Y"**: an opening line whose
presence is read, and a missing line whose absence is.

**An event waits exactly when it is the missing half of a documented inference.**

- Lose a missing-half line and the journal states the opposite of what happened. A `run-stopped` lost
  with the process makes a Run that was stopped read as a Run that died still recording.
- Lose an opening line and its inference goes with it. With no `run-started` there is no Run to find
  a missing stop after, so the journal is silent about that Run. Silence is a gap. Every unwaited line
  already risks a gap; a synchronous write is paid only to prevent a lie.

The inferences themselves are listed in the KDoc of `RunJournalEvent` and in `RunJournalTest`. A new
event waits only by first adding an inference to that list, never on the argument that losing it
would leave the journal thinner.

## What it decides

- The six waited lines stay as they are: `service-destroyed`, `run-row-created`, `run-row-discarded`,
  `run-stopped`, `run-finalized`, `demoted`.
- `run-started` and `service-created` do not wait. Each opens an inference and is the missing half of
  none. (Several waited lines open an inference too; being the missing half of any one is enough
  to wait.) The ~40 ms between `run-started` and its waited `run-row-created` (10:18:57.568 and
  10:18:57.607 on the phone) stays open on purpose: a process that dies inside it leaves no journal of
  that Run, which is silence, not a false statement.
- `promoted` and `promotion-refused` do not wait. They are read by which one is present above a
  `demoted`, never by absence. The `demoted` they are read against waits, and the writer is FIFO, so
  it lands them before itself. They can only be lost with no `demoted` after them to misread.

The inferences run one way only. The journal does not license "a `service-created` with a
`service-destroyed` above it means the service before it shut down cleanly", so a lost opening line
cannot make that reading false.

## Considered

**Mark `run-started` as well.** It closes the 40 ms window. It was declined because the same argument
applies to every line in the enum, and following it turns the journal into a synchronous write on
every event, Strap and status lines included. Since the set does not widen, no wait cost was
measured.
