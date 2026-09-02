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

## A Route is stored in one form, whichever door it came in by

Added by #354. There are two doors into the library and they described the same ground differently.
A runner who saves a Run as a course (#55) and *also* shares that Run as a GPX (#84) and hands the
file back (#54) is one runner with one evening, and they were getting two rows.

**Every place is moved to the seven decimal places the row keeps it at, and then the line is thinned
to its shape — two metres — before it is stored, on both doors. Its distance is measured along that
line. Its climb is measured off the places before thinning.**

The line is a Route's identity, so the doors cannot merely be *near* to agreeing: one point of
difference is a second row of a course the runner already has. They agree by being the same code
(`courseOf`), not by two copies of a rule being kept in step, because a rule written twice is a rule
that will eventually be changed once.

Same code is not enough on its own, which is why the snapping is part of the rule and comes first. A
GPX writes a position to seven decimal places, so a Run's own file hands the importer places a
centimetre from the ones the Run holds — and thinning asks how far a place sits from a line, a
question whose answer changes in that last centimetre. A place a fraction of a centimetre inside the
two metres on one door is a fraction outside it on the other, and one line then has a point the
other does not. So every place is moved to where it will be *kept* before anything is asked of it,
and the two doors thin the very same numbers.

Thinning is the right form for both because of what a Route is *for*. A straight road recorded once
a second is a thousand points saying nothing a pair of them do not, and every one of them is carried
by every screen that draws the course and read by the off-course rules on every fix of every Run.
Two metres is finer than any corner a path turns and coarser than the wandering of a fix standing
still, so the course a runner follows is unchanged by it.

The climb is measured before thinning, on both doors, for the reason the run door already had:
thinning is a judgement about where the line *bends*, and a hill is not a bend. A road straight up
one side and down the other is two points once thinned, and its crest — the whole of the climb — is
one of the points thrown away.

### What this costs

**An imported Route's stored line is no longer the file's line.** It is the file's shape. The file
is not the thing being preserved — the app never keeps the file, and re-import is the only way back
to it — but this is a real narrowing of what "the line exactly as written" meant, and it is written
down here rather than discovered.

**An imported Route's banked distance is measured along the line that was kept**, so it can differ
from the file's own total by the corners the thinning cut. At two metres that is a handful of metres
over a long course, and it is the honest number for the question a Route answers: how far the
runner following this course will go.

**The line agrees; the numbers on the row need not.** A GPX states its heights to a tenth of a metre
and a Run holds them as it measured them, so a Run's own file handed back finds the Route that Run
saved and *re-measures* it rather than being waved through untouched. That is one row, which is what
#354 was about, and it is the remedy this ADR already names for a banked number, working as intended.
The runner is told the numbers now come from the file. Making the heights agree too would mean
rounding a Run's own measurements to the precision of a file format it may never be written to, and
the course is the thing being made canonical here, not the arithmetic about it.

**A file simplified by another app is still its own Route.** Thinning makes two lines the same only
when they were the same ground drawn at different densities by *this* app's rules. Strava's
simplification is not this one, so a course exported there and imported here remains a separate row
— exactly as it was before #354, and for the same reason.

### What was rejected

- **Leaving it, and letting the runner delete one row.** Cheapest, and nothing was broken: both rows
  were correct Routes and either could be followed. Rejected because the two rows carry the same
  name — the Run's own evening (#304) — so the runner is asked to choose between two identical-looking
  rows with no way to tell which is which.
- **Not thinning the Run-saved line**, so the doors agree the other way. That is the same one-form
  rule with the form chosen the other way round, and the argument for thinning above still stands:
  it would put a thousand needless points into the library and into every reader of a course.
- **Matching on shape or on endpoints-and-length instead of on the line.** The largest change, and it
  would have to answer what "the same course" means for two genuinely different files — a question
  nothing has asked yet. Identity stays the stored line, exactly as written.

### The rows kept before it are redrawn once, at the upgrade

Added by #399. The rule above is about the two doors, and it left a third way in: the library a
runner already had. An imported row held *every point its file held*, unthinned, and a Run-saved row
was thinned from places that had not been snapped first. A Route's identity is the exact text of its
line, so those rows are different Routes from the ones their own files make today — hand the app a
file it had already imported and it finds nothing to re-measure and keeps a second row, under the
same name (#304). The fault #354 closed, arriving by the upgrade instead of by the two doors.

**Every row is redrawn once, through `courseOf`, by the migration to v42. Its distance is measured
again along the line it now holds; its climb is left exactly as it was banked; and two rows landing
on one line become one, the lower `id` surviving.**

Redrawn through `courseOf` and not a copy of it, for the reason the two doors share it. The distance
is measured again because a Route's distance is the distance along the line kept, and a row left with
its old number would state a distance along a line it no longer has — and because re-measuring is
what makes the pass finish its job: afterwards each row is exactly what its own file would produce
today, so handing that file back is a true no-op rather than a re-measure the runner is told about.

The climb is the one number the pass cannot bring into line, and this ADR is why: the row keeps no
height profile and the file is gone. It is left standing, and a re-import re-measures it when it
arrives — the remedy named above, unchanged.

The lower `id` survives a merge because it is the tiebreak the library already uses when one line
somehow has two rows (`findRouteByPolyline` orders by `id`), so the survivor is the row an importer
would have been sent to anyway, and it is the one the runner has had longest, under the name they
have been seeing. A survivor with no climb takes the climb of a row it absorbed, which is #355's
rule: a null is silence about the course, not a claim that it is flat.

`sessions.ranAlongRouteId` (#56) is the one place outside the table where a Route's id is durably
held, and a Run that followed the losing row is pointed at the winner in the same transaction as the
drop. Nothing else holds one: the course picked for the next Run lives in Compose's saved state and
is looked up against the library each time it is drawn, so a row that has gone reads as no course
picked.

The alternative — leaving the table alone and teaching the importer to look for the old encoding as
well — was rejected because it is this ADR's own argument turned round. It would put a second way of
saying "the same course" into the library permanently, to spare a one-off pass over a table holding
a handful of rows.

## The #20 rules restated on the axes a Route has

The gain itself follows the shape of the GPS tier of #20 — hysteresis above the last low point,
smoothed first, because a sum of positive differences banks every metre of noise — but at **three
metres**, not that tier's ten.

Ten was tried first and was wrong for a reason that only shows up in a library (#419): gain is banked
in whole steps of the threshold, so ten metres made ten metres the smallest reportable climb, and
every rolling route in the library read exactly `10 m up`. The two numbers are also answering
different questions. #20's ten metres is one standard deviation of a *single* GPS fix's vertical
error, and a Run bands raw fixes; a Route's heights are smoothed before this rule sees them, so what
it judges is a mean, whose error is several times smaller. Against the nine tracks exported from this
phone, three metres over that smoothing reports twelve to forty-seven metres of climbing on runs of
two to six kilometres, where ten reported ten on almost all of them.

Three is also close to the floor, and the floor is where the accepted cost of this change sits. A
five-point mean leaves about a fifth of a per-point wobble behind, so three metres protects the
reading only from a wobble of *under* fifteen metres a point; at fifteen the residual is three
exactly, which the rule banks. Ten metres protected it up to about fifty. So the noise floor this
tier tolerates has come down, and a file noisier than fifteen metres a point now banks its noise as
climbing — the #20 defect, at a lower threshold of noise.

That was accepted rather than solved, because the two obvious solutions are both worse. Going lower
still, to the barometric tier's one or two metres, banks the residual of an ordinary file. Widening
the smoothing to cover a twenty-metre alternation takes an eleven-point mean, which on a route whose
points sit a hundred metres apart averages over a kilometre and rubs real hills out with the noise.
The evidence that the cost is affordable is the nine exported tracks above: none of them is anywhere
near that noisy, and real GPS vertical error is correlated between neighbouring fixes rather than
alternating point by point, which is the shape that defeats a mean. #424 holds the proper fix — a
second smoothing pass, which leaves a twenty-fifth rather than a fifth — and a test asserting the
wrong figure on purpose pins the limit until it lands.

Nothing recomputes the routes already in the library: `Route.elevationGainMeters` is banked once at
write time and a `RoutePolyline` stores no heights to re-read, so the old ten-metre figures stand
until each route is imported again. That is the ADR's "banked, not re-derived" rule doing what it
says, and re-importing is the only rewrite path there has ever been.

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
six hundred metres of climbing. The point rule cuts that to about a fifth — which was under the old
ten-metre threshold and is over the new three-metre one, so that particular file is no longer
rescued outright. See the noise floor argued above, and #424.

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
  What *is* stored is the course thinned to its shape, on both doors, so a file this app exported
  from a Run finds the Route that Run's own page saved (#354, above).
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
  recording of where they went and becomes a line they mean to go again. Since #354 the two doors
  share the code that does it, and are fed places snapped to the same precision first, so "exactly as
  an import does" is true of the line by construction.
