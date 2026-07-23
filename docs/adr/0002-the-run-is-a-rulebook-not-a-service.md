# The Run is a rulebook, not a service

A Run's logic — its clock, Phases, Intervals, and per-second accounting — is
pure arithmetic, but it was welded to an Android `Service`, so the only way to
exercise it was to go for a run. `HrForegroundService.kt` grew to 2,706 lines
holding ~25 responsibilities across 63 mutable fields touched by five threads,
was changed in 34 of the last 40 commits, and **no test touched any of it**.
Every one of the ~30 race fixes from #110's review loop shipped unverified.

So the Run moves into its own module, and it is a **decision, not an actor**:
it takes events and returns the new state plus a list of effects to perform.
It never speaks, writes, notifies, or touches Android. The service keeps only
the translation — callbacks in, effects out. This is the same inversion as
[ADR 0001](./0001-promotion-is-derived-not-claimed.md), one level up: there,
nobody holds a Promotion; here, nobody performs an action mid-decision.

## Considered options

An **engine calling out through a host interface** — `ForegroundPromotion`'s own
pattern — was the obvious extrapolation, and was rejected at this size. It
works there because Promotion has three effects and one bit of state. A Run has
a dozen effects and thirty pieces of state, and firing an effect in the middle
of a calculation makes ordering implicit: "does the interval cue come before or
after the sample is written" stops being answerable by reading a returned list
and becomes a matter of where the call happens to sit. Returning effects as
values makes the whole per-second decision one assertable object.

**Extracting only the tick arithmetic** was the low-risk version, and it treats
the symptom. The bugs have not been in the arithmetic; they have been in the
interleavings — STOP arriving during START, a pulse landing after the
notification was removed, a dropout spanning a walk step. Leaving lifecycle in
the service leaves every one of those untested.

## Consequences

- **The database row's id arrives as an event.** A pure module cannot await
  Room. "Create the row" is an effect; when the insert answers, the id comes
  back in as an event. The Run therefore has an explicit early state — started,
  not yet recordable — and buffers what it produces until the id lands. This is
  what retires `sessionCreationLock`, `stopDuringSessionCreation` and the
  post-commit gate: STOP arriving in that window is now a list of four events
  with an expected result, not a lock. Samples from the first seconds stop being
  dropped, because there is somewhere to keep them.
- **The Run's numbers are pinned at START** (#131): Max HR, target zone, run
  mode, Workout, warm-up and cool-down, and the AI-sharing consent. A Run is
  recorded entirely under the settings in force when it began. Max HR was
  previously read live on every second of zone accounting, and Settings is
  reachable mid-run. This does not close #137, it changes its shape: instead of
  one Run banking a few seconds against each number, a Run spanning the first
  deliberate set keeps the old number for its whole length — and is finalized
  after the retally has already swept the finished Runs, leaving it the single
  Run inconsistent with the history around it. #137 must be re-read once this
  lands.
- **Controls stay live, but arrive as events.** Coaching on/off, auto-pause and
  split announcements are things the runner flips *during* a Run (#109 built the
  first one deliberately), so they are not pinned. They are delivered as events
  rather than read from a settings object, because a module that reaches out for
  a value is not a function of its inputs.
- **The Run returns whole states, never patches.** One `_hrState` write site for
  the Run's fields, where there were about thirty of the file's 42. The Strap's
  own sites stay as they are; they are #128's. The screen still reads one
  `HrState`, so no UI changes and nothing in Compose to re-verify.
- **One thread touches the Run.** `SessionTrackingThread` becomes its only
  inbox; Bluetooth callbacks, GPS callbacks, taps and the tick all post to it.
  Nothing inside the module needs a lock or `@Volatile` — the discipline is
  structural rather than per-field. The dedicated thread is kept rather than
  folding onto main so that a busy UI still cannot stall the run clock.
- **It is built complete before it is used.** The module and its tests land
  wired to nothing; a second change deletes the service's run fields and
  delegates in the same commit. There is deliberately no interval during which
  both hold the Run's numbers, because two sources of truth for
  `secondsRunning` is the failure this whole move exists to prevent.
- **The same module can drive Simulation**, and eventually a replay of a
  recorded Run — the leverage that comes free once the Run is a function.
