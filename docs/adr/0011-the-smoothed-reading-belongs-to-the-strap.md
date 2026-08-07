# The smoothed reading belongs to the Strap, not to the coach

The number the coach reasons about is not the packet the Strap just sent: it is
a five-second rolling average, so one stray beat cannot send the runner a cue.
That average was fed only by the readings that reached the coach. A reading
that arrived while the Run was not RUNNING, or was in its cool-down, or had
coaching switched off, was kept as the raw reading and then dropped on the
floor — it never joined the window, and, more quietly, it never aged the window
either.

A window that is only aged when it is fed does not decay. It freezes. Three
runs on the phone show both shapes of the same fault: session 53 recorded a
`smoothedBpm` of 131–132 for the whole of its 22 minutes while `rawBpm` swung
between 113 and 161, and sessions 54, 55 and 77 recorded a `smoothedBpm` of 0
from the first second to the last, because coaching was off from the start and
so nothing ever entered the window at all (#161).

This is not a display detail. That number is what `bandWithHysteresis` judges
the band on, what the above-target cue speaks aloud, and what the `smoothedBpm`
column of every saved sample records. Frozen, it can cue a runner into a zone
they are not in, or leave them uncued long after they have left target — and
the moment coaching is switched back on mid-Run, the coach's first decisions
are made on beats from minutes ago.

So every reading joins the window and ages it, whoever is listening. The
average is a fact about what the Strap is saying; whether anyone acts on it is
a separate question, asked separately. `read` and `heard` collapse into one
`sampled`, and the coach's three conditions — running, not in the cool-down,
coaching on — now decide only whether the coach speaks.

## Considered options

**Clearing the window when the coach stops listening** — honest about the
staleness, and it would have fixed the freeze. Rejected because it makes the
smoothed number depend on the coach twice over: it would still read 0 through
a whole coaching-off Run, which is what sessions 54, 55 and 77 already did, and
it would hand the coach an empty window at the exact moment coaching is
switched back on. The value has a well-defined meaning at every second of a Run
with a Strap on it; there is no reason to withhold it.

**Keeping the "with coaching off, the smoothed number is the last raw reading"
rule** — the behaviour the old code had, inherited from the debug read-out
being the one place the number was ever computed. Rejected: it makes
`smoothedBpm` mean two different things depending on a setting, so a saved
sample's smoothed column could not be read without knowing what the runner had
switched on at the time.

**Re-deriving the smoothed column from stored `rawBpm` afterwards** — a repair
pass over history, as the Effort Score backfill did (#62). Rejected as a
separate question, not as a bad one: nothing in the app reads the stored
`smoothedBpm` column back, so no chart, record or export changes if it stays
wrong. The GPX export deliberately carries `rawBpm` (#84).

## Consequences

- **A dropout empties the window.** The Strap going away already zeroed the
  reading so that the last one was not held as if fresh; the window goes with
  it now, because averaging across a dropout would hold those beats as fresh by
  the back door.
- **The live screen's coach line tells the truth sooner.** It read "Ready" for
  the whole of a coaching-off Run, because the window it tested for emptiness
  never filled. It now reads "Off", which is what it is — and the two questions
  swap order, so that a dropout emptying the window cannot take the word back.
- **The live screen's heart-rate number is now an average on every Run.** With
  coaching off it used to show the last raw reading; it shows the five-second
  average, the same number every other Run shows.
- **History keeps its wrong numbers.** Runs saved before this carry a frozen or
  zero `smoothedBpm`; their `rawBpm` is intact and is what everything reads.
- **The coach starts warm.** Switching coaching back on mid-Run used to hand the
  coach a window holding one beat — the stale contents aged out on the first
  reading it heard, leaving nothing to average — so its first band decision was
  made on an unsmoothed packet. The window is now already five seconds deep.
