# The Acquisition is a rulebook too

Getting a Strap connected — scanning, chasing an address, backing off, giving up — was 430 lines of
private fields on an Android `Service`, and **no test touched any of it**. It is the code with the
worst history in the app: every fix in it since #110 has been a race, found by hand on a phone, and
the comments record several that shipped first. The retry counter reset every cycle, so the give-up
cap could never trip. A scan timing out could overwrite a connect the runner had just started. A
GATT abandoned two connects ago could still re-save the Strap it belonged to.

So Acquisition moves into its own module on the same terms as the Run
([ADR 0002](./0002-the-run-is-a-rulebook-not-a-service.md)): it takes events and returns the new
state plus a list of effects. It never scans, connects, closes, saves or waits, and it touches no
Android type. The service keeps the translation — callbacks in, effects out.

## Acquisition had no state; it had a sentence

The thing that made this worth doing was not the line count. It was `connectionStatus`: a free-form
`String` that was doing four jobs at once.

- The label on screen.
- The input to nine decisions — `== "Connected"`, `== "Strap not found"`, `.contains("Failed")`.
- Promotion's own predicate. [ADR 0001](./0001-promotion-is-derived-not-claimed.md) says promoted ⇔
  a Run is live or an Acquisition is in flight, and "in flight" was four substring matches.
- A database column, `hr_samples.connectionState`.

One of those strings is built by interpolating the Strap's own name — `"Connecting to $name..."` —
and then matched with `.contains("Connecting")`. **The wake lock was being held or released on a
substring match against a device name.** ADR 0001 removed the last way to forget a release; this
removes the last way to get the question wrong.

The phase is now typed, and `CONTEXT.md` had already written the vocabulary: an Acquisition is "in
flight until the Strap is connected, given up on, or blocked". Scanning, Connecting, Connected,
Retrying, Gave up, Blocked. The domain model described the machine; the code just never had it.

**The string survives at one edge, deliberately.** `hr_samples.connectionState` has stored a status
sentence since the first recorded run, and `statusLine` reproduces the vocabulary verbatim. A typed
column would make every old row disagree with every new one, and buy the runner nothing. Everywhere
the status is *reasoned about* it is typed; the sentence is generated at the edge where a sentence
is what is wanted.

Worth being exact about what that column actually holds, because it is easy to assume more. In
practice the only value ever stored is `"Connected"` — 26,303 rows of real history, no exceptions.
A dropout writes no row at all: `Run.bankSecond` banks the second as no-data and returns *before*
emitting `SaveHrSample` whenever there is no reading (#110, #115), so `"Disconnected (Retrying)"`
reaches the screen and the Run, never a row. The compatibility being preserved here is therefore
narrow and worth stating plainly: old rows say `"Connected"` and new rows must keep saying
`"Connected"`. That is the whole of it, and it was confirmed against the phone rather than assumed.

## One thread, and three pieces of concurrency machinery deleted

Acquisition's events go through `SessionTrackingThread` — the Run's existing inbox, whose whole
discipline is that one thread touches the state. That single decision is what deletes:

- **`scanEpoch`**, bumped on every scan start and stop so a timeout coroutine could tell whether its
  own scan was still the live one.
- **`connectRequestSeq`**, a `@Volatile` counter making the last of two racing connects win.
- **`gattConnectLock`**, serialising the close-old/connect-new handoff.

All three existed to let something that had already happened decide it no longer counted. With the
deadline held as state and every decision made on one thread, a superseded scan or connect is simply
a phase the state is no longer in — cancelling is clearing a field. "The last request wins" stops
being a counter and becomes "the last event".

It also makes `runIsLive` exact. The give-up cap depends on whether a Run is live, and read across
threads that is a snapshot; read on the Run's own thread it is a fact.

**Rejecting a stale callback by address is what stays.** A real GATT can report itself connected
long after we stopped caring — that is Android, not our threading, and no amount of serialisation
removes it. Every callback is checked against the Strap actually being chased. It is now a test
rather than a comment.

## The pulse runs while promoted

A pure module cannot call `delay()`, so Acquisition holds its deadlines — when the scan ends, when
the retry is due — and is handed the time once a second. But the pulse stopped whenever no Run was
live, which is exactly when a pre-run scan and the three pre-run retries need it.

So the pulse now runs while the app is promoted. That is not a new rule: it is ADR 0001's rule,
`runLive || acquiring`, already written down and already the window in which the phone is awake and
holding a wake lock. One condition serving two purposes rather than two conditions that have to be
kept in agreement.

Anyone reading `HrForegroundService` will see a timer ticking with no Run in progress and reach for
it. It is deliberate, and this is the paragraph saying so.

## The adapter is the one thing that has to arrive

Everything else about Bluetooth is asked for — permissions and the adapter's state are read fresh in
`AcquisitionContext` on every decision, which is what makes a permission revoked mid-chase need no
handling at all. The adapter's *own* switch cannot work that way, and #221 is the proof: a Strap
connected when the runner turns Bluetooth off sat reading "Connected" indefinitely.

Two things have to be true at once for that, and both are. Android delivers **no GATT disconnect
callback** when the adapter goes off, so nothing arrives. And the pulse that would ask runs only
while an Acquisition is in flight — so `Connected` and `Blocked`, the two terminal phases that
outlive the tick, are exactly the two the adapter can go stale under. Nothing was asking, and
nothing was telling.

So `BluetoothStateChanged` is an event, from an `ACTION_STATE_CHANGED` receiver the app had never
registered. It carries the broadcast's own `EXTRA_STATE` rather than a fresh read, because that read
is behind BLUETOOTH_CONNECT and deliberately answers "on" when refused, while the broadcast needs no
permission and knows.

The two directions are not symmetric, and that is the decision worth recording.

- **Off** ends whatever was happening, including a `Connected` whose GATT is dead whether or not
  Android ever says so. A refused scan is overtaken by it — that block's reason is no longer what
  stands between the runner and a Strap.
- **On** clears only the block it caused, and pre-Run stops at `Idle`. It says a radio is available
  again, not that anyone asked for a Strap. `Idle` is also what the record screen's auto-connect
  waits for, so a saved Strap is picked back up by the screen that already owns the question of when
  an unasked-for connect is welcome.
- **On, with a Run live**, resumes the chase the block interrupted (#224). See below.

Three phases the adapter going off leaves exactly as it found them, and the third is the one worth
being careful about. `PermissionMissing` outranks it, because that is what the runner can act on and
because without the permission the adapter's state was never ours to read. An existing
`BluetoothUnavailable` stands because the off state arrives twice as a rule — Android sends
turning-off first — and hanging up the same GATT twice is not idempotent.

And **`GaveUp` is left alone**, which is the asymmetry biting back. `GaveUp` exists separately from
`Idle` for exactly one reason: the record screen auto-connects from `Idle`. Block a given-up chase
and the clear-to-`Idle` above hands it straight back — so switching Bluetooth off and on again would
restart the chase that had just run out of attempts, by a route neither phase's own rule can see.
"Strap not found" is no less true for the adapter being off.

### Mid-Run, `Idle` is one stop short

Stopping at `Idle` works pre-Run only because something else is waiting there. Mid-Run nothing is:
the record screen's auto-connect is gated on no session being active, so Bluetooth going off and
back on left the Run recording no heart rate for the rest of its life (#224). That cuts against the
standing rule that mid-Run reconnects are uncapped and every dropout is chased (#110) — a switch
flipped and flipped back should not be the one dropout we give up on.

So the rule has a mid-Run branch: the block hands back the Strap it interrupted and the chase starts
again, as a fresh `Connecting` with a `ConnectGatt`. Pre-Run is untouched, and so is who owns the
question — the screen still decides when an unasked-for connect is welcome, because pre-Run there is
still a screen to ask.

Three things about that resumed chase are deliberate. It **does not promote the Strap to active**:
waiting out an outage is not a tap, and a promotion surviving the bounce would let a Bluetooth
toggle overwrite the active Strap. Its **retry count starts over**, which costs nothing since the
cap is pre-Run only. And **only a block that actually interrupted something** resumes — one that
arrived while scanning, or from `Idle`, has no Strap of its own, because nothing ever auto-connects
from a scan.

The alternative was having the service re-issue a connect from the saved active Strap. It works, and
it was rejected: it moves "when is an unasked-for connect welcome" back outside the rulebook, which
is the thing this ADR exists to prevent. It would also chase the *active* Strap rather than the one
the Run was actually using.

The subtlety is where the address lives. `Blocked` now carries an `interrupted` Strap, and it is
kept **separate from `AcquisitionState.address`**, which stays null for a blocked phase. `address`
is what the GATT-closing paths and the Forget and Disconnect identity checks match on, and a blocked
phase's GATT was already closed on the way in — a non-null `address` would have Disconnect try to
hang up a dead handle and a fresh scan try to close it a second time. One is a live connection; the
other is only a name to chase again.

Forgetting that Strap during the outage takes the memory with it, or the adapter's return would
chase one the runner just removed — and the discovery behind that connect would save it straight
back. A disconnect needs no such rule: it leaves the block entirely.

The resume asks the same permission question every other connect here asks: without
BLUETOOTH_CONNECT it says `PermissionMissing` rather than connecting. That is the end of the road
rather than a pause — the Strap rides along on the new block, but nothing reads it again while the
adapter stays on, so that Run finishes strapless. It is still the honest answer: without the
permission there is no connect to be had, and granting it restarts the process, which takes the Run
with it.

## Considered options

**Leaving the string as the interface** and extracting only the logic. Cheapest, and it would have
bought the tests. Rejected because it keeps the defect: Promotion decided by substring survives the
refactor untouched, and the nine string comparisons stay exactly as fragile as they were.

**Retiring the string everywhere, including the database.** The clean end state. Rejected for now:
it costs a migration and it changes `Run`, `RunEvent` and `RunHeartRate` — some 2,600 lines of
passing, well-tested code that has no bug in it. The gain would be tidiness in the one place the
value is never reasoned about.

**Giving Acquisition its own thread.** Rejected: two queues means the order between "the Strap
dropped" and the Run hearing about it is undefined, and `runIsLive` goes back to being a snapshot —
the exact staleness the context parameter exists to avoid.

**Splitting the move in two — scanning first, then connecting.** Rejected: starting a scan aborts an
in-flight connect and connecting stops a scan, so the seam runs straight through a handoff. It would
have meant a bridge built only to be deleted, and two half-machines to reason about instead of one.

## Consequences

- **`GaveUp` is not `Idle`.** The record screen's auto-connect fires on Idle, so collapsing the two
  would restart the same doomed chase the moment it gave up. They are separate phases for that one
  reason.
- **Every branch must reach a phase that is not in flight.** In-flight means promoted, and promoted
  means a wake lock. One branch already failed this: when a retry came due with Bluetooth switched
  off, `getRemoteDevice()` returned null and nothing happened at all — the status sat on
  "Reconnecting in Ns..." indefinitely. That is now `Blocked`.
- **`Blocked` remembers a Strap but has no address.** The two are separate fields for separate
  jobs: `interrupted` is a name to chase again once the adapter returns mid-Run, `address` is a
  handle the closing and identity paths match on. Collapsing them would have Disconnect hang up a
  GATT that was closed when the block was raised.
- **A stale disconnect no longer schedules a retry.** The old check asked whether *any* Strap was
  being chased; it now asks whether the Strap that dropped is that one. Same class of fix as the
  stale-connect guard beside it, which already worked this way.
- **`promoteOnVerify` survives a retry.** A tap that drops and reconnects is still that tap. It is
  carried on the phase rather than kept in a field, so it can only ever be true of the Strap the
  phase names — the address-typing that #123 added by hand is now structural.
- **The scan results outlive the scan.** They sit beside the phase rather than inside it, because a
  scan that times out leaves its discoveries on screen to be tapped. Only a fresh scan clears them.
- **The device list is no longer made of Android objects.** The published state carried
  `BluetoothDevice`s to the screen, where reading `.name` needs BLUETOOTH_CONNECT outside any check.
  The name is read once, inside the check that covers it, and a plain address-and-name value travels
  instead.
