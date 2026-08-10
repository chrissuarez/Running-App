# A Route is a plan, not a recording

Until #54 every line in this app was a Track: what a Run recorded of where it went. A Route is the
second kind, and it arrives looking exactly like the first — most GPX files a runner imports *are*
exported runs, carrying segment breaks, timestamps and heart rate in `<extensions>`, sometimes from
this very app. The temptation is to treat one as the other.

Two rules that hold for a Track do not hold for a Route. This is why, and what follows from it.

## A Route has no Breaks

[ADR 0010](0010-the-track-is-the-record-of-a-break.md) is emphatic that nothing reading the shape of
a Run may join across a Break: no line is drawn over one, no climb is banked across one, no window of
pace or height reaches through one. A GPX's `<trkseg>` boundaries are exactly that shape. The
exporting app put them there because its runner held Pause at a crossing.

**A Route is one unbroken line. Every segment of every track in the file is joined, in document
order, and the joins are not marked.**

The rule ADR 0010 states is about what may be *inferred* from a hole: the recording does not say
where the runner went, so no reader may pretend it does — they may have been driven up that hill.
Nothing about a Route is inferred from having been run. It is a line somebody proposes to follow, and
the stretch between one segment and the next is part of the proposal. There is no runner whose
movements are being over-claimed, because there is no runner yet.

The alternative is worse in a way that is easy to check: a file exported from a run with three pauses
would become either three Routes, or one Route with holes in the middle of it that nothing can
follow. Neither is a course anyone asked to keep.

The same reasoning settles a question this ticket did not have to answer. A file carrying both a
`<trk>` and an `<rte>` describes one outing twice — as recorded and as planned — so the track wins
outright, being the one with the detail, and the two are never run together.

## A Route's numbers are banked, and its heights are not kept

Everything a Run reports is re-derived from its stored track every time its page is opened. That is
load-bearing and hard-won: it is what let #204 change what a Break is worth, #165 change what an
Outage's seconds are worth, and #228 change which Reserve a Run's beats are banded against, each
reaching the whole of history at the next launch rather than only the Runs recorded after.

**A Route's distance and elevation gain are worked out once, at import, and stored on the row. Its
height profile is not stored at all — only the line.**

A Run re-measures because a Run keeps its *evidence*: every fix with its horizontal and vertical
accuracy, its altitude, the barometer reading beside it, and the moment it arrived. A better rule can
be applied to that later because the raw material for a better rule is still there.

An imported Route has none of that and never will. A GPX height is a bare number: no bound on how
wrong it might be, no way to tell a barometer's reading from a GPS fix's, no time to smooth over. The
two-tier model that makes a Run's elevation improvable ([`Elevation.kt`](../../app/src/main/java/com/example/runningapp/analysis/Elevation.kt))
cannot ever apply to it, so there is no better answer waiting to be re-derived — only the same
answer, recomputed. And the file it came from is gone; it was in somebody's Downloads folder, or an
email, and the read grant lapsed the moment the import finished.

So the profile is dropped and the two numbers are kept. Carrying the heights as well would treble the
size of every row to answer a question nothing will ask again.

## The #20 rules restated on the axes a Route has

The gain itself follows the GPS tier of #20 — ten metres of hysteresis above the last low point —
smoothed first, because a sum of positive differences banks every metre of noise.

A Run smooths a GPS height over five seconds, in time rather than in fixes, precisely because noise
arrives at a rate. A Route has no times: a planned one was never run, and an exported one's timestamps
belong to somebody else's afternoon. So the window is restated on the two axes a Route does have —
**five points, or fifteen metres of ground, whichever reaches further.**

Both, because a GPX arrives at either of two densities and each rule alone reads one of them wrong.
Five points alone over-smooths a file recorded many times a second. Fifteen metres alone stops
smoothing the moment a file's points are more than seven metres apart — which is most exported
tracks, since Strava and Komoot simplify the *positions* they export and keep the heights as
recorded. A simplified track is exactly the jittery case #20 exists for, and the ground rule on its
own would have handed it back untouched: sixty points of twenty-metre wobble over flat ground banked
six hundred metres of climbing.

The cost is that a widely spaced route has the shoulders of a real hill averaged off it, a five-point
mean over half a kilometre being a great deal of smoothing. That is the right way to be wrong.
Under-reporting a climb by its shoulders costs a metre or two; banking jitter reports hundreds of
metres that never happened.

## What was rejected

- **Storing a Route as a Run** — a `sessions` row with a source flag, reusing the track table, the
  map, the thumbnails, the elevation code. The shapes really are that close. Rejected because every
  reader of `sessions` would have to learn to skip it: the record book, the Effort Score backfill,
  weekly volume, the Stage's evidence, the coach's last three Runs. One that forgot would score a
  route nobody had run, and that is a defect with no natural floor — it would arrive as a wrong
  medal one month and a wrong graduation the next. A Route has no key into `sessions` and none out,
  which is what makes "deleting a route never touches past runs" true by construction rather than by
  vigilance.
- **Keeping the height profile on the row**, so gain could be re-derived. See above: there is no
  better rule that could ever be applied to heights this thin.
- **A packed polyline encoding.** A Route is stored once and read by every screen that draws it, so
  what matters is that a row can be read by eye on a phone when something looks wrong, and that
  there is no codec of our own to be subtly wrong about. `lat,lon` pairs to seven places — the same
  precision the app writes a GPX out at.
- **Marking the joins**, so a later reader could tell where the source recording broke. Rejected as
  a record of a fact about a file that is no longer relevant to anything: the Route is the line, and
  a runner following it does not care which parts of it somebody else once walked.

## Consequences

- **The library and history are unconnected in both directions.** `MIGRATION_25_26` adds a table
  with no foreign keys and touches nothing else. Emptying the library costs no Run anything, and
  deleting every Run leaves the library standing.
- **A change to the elevation rules will not reach an imported Route.** This is the price of banking
  the number, and it is a real one. The remedy is to re-import the file, and it is the runner's to
  reach for; nothing in the app will re-measure a Route behind them. If that ever stops being good
  enough, storing the profile is the change that makes re-measuring possible, and this ADR is what
  should be revisited.
- **A Route is its line, so importing the same line twice keeps one Route and re-measures it.**
  Identity is the stored `lat,lon` line exactly as written, not a likeness: the same course exported
  again by another app, simplified differently, is a different line and becomes a Route of its own.
  Two consequences follow. A file handed over a second time adds nothing — which is what makes the
  remedy above safe to reach for, and what makes Android replaying an "Open with" intent from the
  recents list after the app is killed cost the library nothing. And when that file measures the
  line differently, its numbers are written onto the Route already kept, under the name the runner
  gave it; re-importing is the remedy, so it has to actually re-measure. What it will never do is
  merge two Routes a runner has been keeping apart, because it never matches two different lines.
- **A Route imported from a paused run carries a straight line across the pause** — through a
  building, if that is where the runner stood. Nothing in #54 navigates on the line, so it costs
  only a slightly wrong distance today. It is a question the off-course rules should be made to
  answer deliberately rather than inherit: a runner following such a Route will be off the line
  exactly where the line is a fiction.
- **A Route whose file states one height reports no climb, rather than none.** A single `<ele>` says
  how high one point is, which is not something climbed between; spread over the rest of the course
  by the fill-from-neighbours rule it would make a flat line and print "0 m up", telling the runner
  a route the file said nothing about is level. Two stated heights are the least that can be a
  profile.
- **The fill-from-neighbours rule is shared with a Run's elevation** rather than copied. Written
  twice, one copy would eventually be fixed and the other left, and the same file would climb two
  different amounts depending on which door it came in by.
- **Saving a Run as a Route (#55) already has its answer.** It takes the Run's track and joins across
  its Breaks, exactly as an import does — because at the moment a runner keeps it, it stops being a
  recording of where they went and becomes a line they mean to go again.
