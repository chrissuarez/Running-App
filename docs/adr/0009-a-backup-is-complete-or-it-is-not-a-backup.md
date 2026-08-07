# A backup is complete, or it is not a backup

Run history lives in one Room database on one phone. Every layer that protects it — the Downloads
snapshot that survives "Clear storage", the monthly archive in the runner's own folder, Android's
Auto Backup — protects it by holding a copy of that file. So the only question that matters about a
copy is whether it is all of it.

Until #191 the answer could quietly be *no*. Both snapshot paths worked by folding the write-ahead
log back into the main `.db` and then copying the file. A reader holding a read snapshot open — an
ordinary History query — blocks that fold, and the code logged a warning and copied the file anyway.
The result was **stale, not corrupt**: an older consistent database, missing the runs committed since
whenever the last fold got through. It was then promoted, counted as a backup, given a last-backup
time, and allowed to retire the copy it replaced. The newest restorable snapshot could be missing the
most recent run, and nothing anywhere said so.

## The promise

**A snapshot that is published is complete as of the moment it was taken. A snapshot that cannot be
taken is not published at all, and leaves the previous one standing.**

There is no third outcome, and in particular there is no "probably fine". A restore (#86) may assume
that whatever it is handed is a whole database as of its own timestamp; it never has to ask how far
behind that timestamp the contents might be, because the answer is always *not at all*.

What this costs when a snapshot fails is the *fresher* backup, never the backup. The Downloads write
takes the snapshot before it touches the folder, so a failure inserts nothing and retires nothing.
The archive fails while still under its `.part` name, so it is swept rather than promoted, and the
last-backup time is not recorded. Both remain best-effort in the sense that matters — a failure is
logged and swallowed, never crashing a run or blocking launch — but best-effort is about *whether*
there is a new backup, not about *what a backup contains*.

## How, and why the fold is gone entirely

SQLite writes the snapshot: `VACUUM INTO` builds a fresh database file from the pages the source
holds right now, log included. Nothing has to be folded, so nothing can block the fold; the file that
lands has no `-wal`/`-shm` siblings to be separated from, and `PRAGMA user_version` — which the
restore reads to decide eligibility — comes across with it.

Two alternatives were on the table and neither survives comparison:

- **Fail the attempt when the fold stays blocked.** Honest, and cheaper than it first looked, since
  the previous backup does stay standing. But it declines to back up in exactly the condition that
  is most ordinary — someone looking at their history — and it fixes the lie without fixing the copy.
- **Archive the `-wal`/`-shm` siblings alongside the `.db`.** Changes what an archive contains and
  what every restore has to know how to reassemble, to reach the same place `VACUUM INTO` reaches in
  one statement.

## The cost, paid deliberately: `minSdk` 30

`VACUUM INTO` needs SQLite 3.27, which is API 30. The alternative to raising `minSdk` was an
API-gated fallback — the old fold-and-copy for API 26–29 — and that is a second snapshot path in the
one routine that decides whether history survives, exercised only on hardware nobody testing this app
owns. Untested is the word that matters: the failure it hides is silent and permanent, and it would
be discovered by a runner who needed the backup.

So the app requires API 30. One snapshot path, unconditionally correct, checked on a laptop against
a real SQLite database with a real reader holding the log open (`DatabaseSnapshotTest`). The Downloads
backup was already gated to API 29+ by scoped storage, so the reach genuinely given up is API 26–29
on an app with one runner, on API 34.

## Consequences

- **Dead API gates are left standing, deliberately.** The pre-30 branches in the audio cues, the
  foreground-service notification and the permission requests can no longer be taken, and none of
  them was touched here: a backup fix is not the place to edit the cue engine. They are cleanup, and
  cleanup wants a diff of its own. The `S`, `TIRAMISU` and `UPSIDE_DOWN_CAKE` gates are all still
  live and stay.
- **`maxSdkVersion="30"` on the legacy Bluetooth permissions is still right**, and now describes a
  single release rather than a range: API 30 exactly.
- **[ADR 0001](./0001-promotion-is-derived-not-claimed.md) narrows.** Its unmeasured "API 26–30"
  band for a refused Promotion is now API 30 alone; the conclusion it reaches is unchanged.
- **The snapshot is now checked on the laptop, not only on the phone.** That needs a real SQLite
  engine in the unit tests (`org.xerial:sqlite-jdbc`, test-only), which is the first library added
  to this project for a test rather than for the app.
