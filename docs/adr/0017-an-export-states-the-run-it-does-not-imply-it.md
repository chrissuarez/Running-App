# An export states the Run; it does not imply it

The app has exported a Run as GPX since #84. A GPX file is a list of places and times: position,
height, a stamp, and a heart rate hung on each one. Everything a runner actually reads off a Run —
how far it went, how long it took, how long of that was moving, where its kilometres fell — is
absent, and every reader works it out again from the fixes.

Working it out again gives a different answer, and it always will.

## The four disagreements

- **Distance.** The app banks ground as it runs, refuses the leg across a Pause, and carries the
  straight line across an Outage ([ADR 0010](0010-the-track-is-the-record-of-a-break.md)). A reader
  given only the fixes joins them all up.
- **Moving time.** The app judges each leg against a threshold and hands back a rest spell that
  outlasts its window ([ADR 0012](0012-an-outage-is-a-leg-like-any-other.md)). A reader has never
  heard of that window.
- **Splits.** The app cuts kilometres at the moment the marker was crossed, dividing a leg in
  proportion, and quotes each one against moving time. A reader cuts its own, its own way.
- **A Run with no GPS.** A GPX trackpoint is required to carry a latitude and a longitude, so a
  treadmill Run and a Run that never found the sky export as an empty track — the heart-rate trace,
  the only thing recorded, has nowhere to go.

The first three make the same Run read differently in two places. The fourth loses a Run entirely.

## The rule

**An exported Run states its own summary, its own laps and its own moments; a reader is asked to
believe it rather than to re-derive it (#218).**

FIT is the format that allows this, so it is what the app writes for anywhere that reads it — Garmin
Connect first among them. The file carries `session` (the Run's distance, its Duration, its Moving
time, its heart rates, its climb), one `lap` per split the Run's own page shows, and one `record`
per second anything was recorded for. A `record` may carry a heart rate and no position, which is
what makes the fourth case a whole file rather than an empty one.

GPX stays, unchanged, as the portable option. It is still the file that anything will open, and it
is still lossy; the choice between them is the runner's, at the moment they share.

## What follows

- **A number in the file is a number off the page.** A field that cannot be filled from the page is
  left out rather than filled with a plausible derivation — an omitted field is a reader falling back
  to its own arithmetic, which is honest; a wrong one is the app lying quietly.
- **The file agrees with itself, not only with the page.** The app has two clocks and FIT has three,
  and the three are a question each rather than three names for one number: the wall clock start to
  finish, the time the timer was running, and the time that was moving. So the Run's Duration is the
  timer time, its Moving time is the moving time, and the wall clock is written as measured. Stating
  the Moving time as the timer time instead — which this first did — left a file whose own timer
  events said the timer had run for longer than its summary claimed. A reader that checks one number
  against another is entitled to believe the events, and then none of the app's numbers survive. A
  statement that contradicts another statement in the same file is not a statement.
- **A lap comes from the splits walk, not from the exporter.** `Split` carries the wall-clock window
  it covers and the moving time its pace was quoted against, because only that walk knows where a
  kilometre was crossed. An exporter that re-derived them would let the file and the table drift, and
  not drifting is the whole point.
- **The encoder is Garmin's own.** FIT is a binary format with CRCs, definition messages and
  per-field scaling; its licence asks that the protocol be followed exactly, and the SDK is the only
  place that is guaranteed. What this app decides is which messages a Run becomes, not how a byte is
  laid down — and the SDK's own decoder reads every golden file back in test, which is what proves
  the file is one the format's readers will accept.
- **A treadmill Run states no distance against its fixes.** Its distance came off a console (#231),
  not off ground the phone measured. It is stated once, in the summary, and nowhere else.
