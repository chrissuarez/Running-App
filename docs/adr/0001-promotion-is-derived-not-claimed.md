# Promotion is derived from state, not claimed by callers

Promotion (the foreground notification and wake lock) used to be taken once at the
top of `onStartCommand` and released by the caller at eleven scattered exits.
Missing one stranded a wake lock indefinitely, which happened four times in forty
commits. Issue #129 proposed inverting it so callers hold a named claim and the
module releases when the last claim drops.

We went further: **nothing holds a claim.** One rule derives promotion from state
the app already publishes — `promoted ⇔ a Run is live || an Acquisition is in
flight` — and re-decides whenever that state changes. No caller acquires or
releases Promotion, so there is nothing to forget. (`onDestroy` still releases
the wake lock directly. Destruction can be system-initiated and arrive with no
demotion at all, and a wake lock must not outlive its service — that one is a
safety net, not a second owner of the decision.)

## Considered options

Seven of the eleven release sites were the same call on the same journey: a
dead-end in Acquisition. Handing those callers a `release()` instead of a
`demote()` would have left seven release sites wearing a new name and the same
forget-one bug. All seven already publish a connection status on their way out,
so the state needed to decide was already there.

## Consequences

- **Two claims, not three.** Simulation is not a claim. `isSimulationEnabled` is
  never cleared by STOP, so treating it as one would re-promote a service with no
  Run in it after every simulated run — the original leak, rebuilt.
- **The rule is a pure function of published state.** It reads no locks and no
  private fields. This is what makes it unit-testable, which is the point: the
  four historical leaks are now four test cases.
- **Simulation must publish `RUNNING` before creating its database row**, matching
  the order START already uses. Otherwise there is a window during simulation
  start where a Run is being created but nothing published says so, and the rule
  would demote (and `stopSelf()`) mid-creation.
- **The rule is edge-triggered.** It acts only when its answer changes. Level-
  triggering it would call `stopSelf()` on every heartbeat update while a bare
  Strap is connected. One exception, below: an unchanged answer of "not promoted"
  still unwinds an outstanding start — once, because the unwind clears the debt
  that armed it.
- **Only the module touches notification ID 1.** It exposes "show this text" and
  ignores the call when not promoted, so a notification that nothing owns cannot
  be posted. What the text *says* stays with the Run.
- **The kill switch keeps only its strap teardown.** Stopping scanning and
  disconnecting publishes a connection status; the rule demotes in response. Stop
  clears the notification a beat later than it does today rather than in the same
  instant.
- **Promotion can now be requested at moments the old code never promoted** — for
  example a pre-run reconnect starting on its own. Android 12+ rejects starting a
  foreground service from the background, so the module must *report* that refusal
  rather than crash or swallow it: recording a refused promotion as successful
  would post run updates to a notification that was never created, and
  `stopForeground(REMOVE)` would not clear them. A refusal leaves the rule
  un-promoted, so the next state change retries.
- **A refused promotion still owes a stop.** The `startForegroundService()` that
  asked for it lands whether or not the platform grants it, and `demote()` —
  `stopSelf()` included — is the only thing in the app that stops the service. So
  the rule remembers an unpromoted start and unwinds it the moment nothing earns
  the Promotion, rather than leaving a started service with no notification. A
  live Run is never stopped this way: a run without its notification is degraded,
  a run without its service is over.

- **Unwinding on the next unearned state is soon enough; it does not have to beat
  Android's start deadline.** Measured, because the docs do not say (#135): the
  `startForegroundService()` watchdog is armed only for a **background-initiated**
  start. Every `startForegroundService()` in this app is called from
  `MainActivity`, and the one that could plausibly run backgrounded — the
  auto-connect reach for a saved Strap — is gated on the screen being resumed and
  catches the refusal if it is not. So every start this app makes is
  foreground-initiated, and no start it makes is on the clock.

  Two arms on a Pixel 8a, Android 17 (API 37), app `targetSdk` 34, both with the
  Promotion earned throughout by an in-flight Acquisition so the rule never
  unwound:

  1. A genuine platform refusal — claim `FOREGROUND_SERVICE_TYPE_LOCATION` with
     the location permissions revoked, so the refusal is thrown from inside
     Android's own service bookkeeping rather than by us.
  2. The control — never call `startForeground()` at all.

  Both survived indefinitely: `startForegroundCount=0`, `createdFromFg=true`,
  `startingBgTimeout=--` from the first reading, no
  `ForegroundServiceDidNotStartInTimeException`, no ANR. Even *never promoting*
  does not crash a foreground-initiated start.

  **The invariant this rests on is that every start is foreground-initiated.**
  Anything that ever starts the service from the background — a receiver, a
  scheduled job, a start under a temporary allowlist — puts that start on the
  ~5-second clock and brings the question back. A refused promotion would then
  have to be answered inside the deadline rather than at the next unearned state,
  and the only correct answer is the ordinary stop path (`stopRun()` → finalize →
  `STOPPED` → the rule unwinds), never a bare `stopSelf()`: `onDestroy` finalizes
  no Run, and run history exists only in Room.

  Note also what the obvious experiment cannot tell you. Patching the app's own
  `startForeground` wrapper to throw bypasses the platform, so the pending-start
  obligation is never cleared and the watchdog fires by construction — a
  guaranteed "armed" answer that proves nothing, and one that would argue for
  ending a runner's Run on a refused promotion. The refusal has to come from the
  platform.
