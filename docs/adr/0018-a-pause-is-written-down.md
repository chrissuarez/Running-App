# A Pause is written down

The recorder writes one row per Pause of a Run — the instant the Run's clock stopped and the instant
it started again (`RunPause`, `run_pauses`). It writes it as the Pause ends, whether or not the Run
had GPS, and it writes it for a Pause the runner held down and for one the app reached itself.

This overturns one paragraph of [ADR 0010](0010-the-track-is-the-record-of-a-break.md), which
considered exactly this table and rejected it. What follows is why that rejection was right for the
question it was asked, and why it does not answer this one.

## What ADR 0010 decided, and on what evidence

ADR 0010 is about what a *Break* is worth: a hole in the track, and the metres and seconds it
carries. Its rejection of a Pause table reads:

> The evidence already exists on the track — every Pause, held down or automatic, is stamped onto
> the fix that resumed the Run (`TrackPoint.startsAfterPause`) — and a second record of the same
> fact is a second record to keep in step.

Every word of that is true **of a Run that recorded fixes**. The readers it was written for —
the map, the Splits, the charts, the elevation total — only ever run over a track, so a Run with no
track raises none of their questions.

## The evidence does not exist for a Run with no GPS

The export (#218) asks a question those readers never ask: *where in this Run did its clock stop?*
The `.fit` file states it as a timer stop and a timer start, so a reader shows the Run the app shows
instead of treating a paused stretch as running.

`PauseMark` puts the mark on the fix that resumed the Run. A treadmill Run starts no GPS at all, so
it has no fixes, so there is no fix for the mark to sit on. Its Pauses were therefore recorded
**nowhere**: the Run's own two clocks say how many seconds it spent paused in total, and nothing
anywhere says when. There is no second record to disagree with, because there was no first one.

The mark is also weaker than it looks on the Runs that do carry it. GPS is torn down for a Pause, so
the mark stands between the last fix before it and whenever the next fix landed after it — the fixes
either side, not the boundaries themselves.

So: **the recorder writes down what only the recorder knows.** The Run is the one thing present at
the moment its own clock stops, on every kind of Run, and that moment is what is recorded.

## Keeping the two records from disagreeing

ADR 0010's real warning is the one worth honouring: *the day the two disagreed the app would have no
way to say which was right.* Three rules answer it, and they are the whole of the design.

- **The rows answer one question, and the track keeps every other.** Nothing that reads the *shape*
  of a Run reads `run_pauses`. A Break is still read off the track: no line is drawn across one, no
  climb is banked through one, no window of pace reaches through one. ADR 0010 stands unchanged for
  all of it.
- **One reader, one precedence.** The FIT export is the only reader. Where the recorder wrote rows,
  the rows are the Run's answer; where it wrote none, the mark on the track is read exactly as it was
  before. They are never merged — a merge is one Pause stated twice, which is the disagreement
  [ADR 0017](0017-an-export-states-the-run-it-does-not-imply-it.md) exists to end.
- **Nothing is backfilled.** Every Run already in history has no rows and will never have any. A
  backfill could only turn the mark's approximation into a row that claims to be a measurement, and
  it would still leave every treadmill Run in history empty — the very case the table exists for.

## Consequences

- **A Pause on a Run with no GPS is stateable for the first time.** A treadmill Run exports with its
  timer stopping and starting where the runner stopped and started it, rather than running straight
  through a rest the file's own summary can only be arithmetic'd into implying.
- **A recorded Pause is exact.** Its two instants are the Run's own, so the stretches the file leaves
  the timer running total the Run's Duration precisely, rather than to within a fix either side.
- **A Run stopped while paused keeps its last Pause**, ending at the STOP. The runner never came
  back; leaving it open would lose it.
- **An auto-pause (#39) is recorded too.** The Run's clock stops for a standstill exactly as it does
  for the button, so the file has the same reason to say where.
- **The choice of source is per Run, not per Pause.** A Run that has any rows is answered from its
  rows alone, and the marks on its track are not consulted — including in the narrow case where a
  row's write did not survive a process death but the fix carrying its mark did, which exports that
  Run one Pause short. Reaching for the marks to fill such a gap is the merge this design refuses:
  the two records describe the same Pauses to different precisions, so a merge would state some of
  them twice, and no rule could say which of a near-identical pair was the duplicate.

- **A Pause is lost if the process dies inside it**, because a Pause is written down when it ends.
  The alternative — writing the near side on the way in and patching the far side on the way out —
  buys a row for a Run that is being rescued rather than exported, and pays for it with a half-row
  every reader would have to have a rule for.
- **The archive's `archive.json` does not carry Pauses.** It never carried the track either. The
  archive's `database/` entry is the whole database and is what a restore reads, so a restored Run
  comes back with its Pauses (#85, #191).
