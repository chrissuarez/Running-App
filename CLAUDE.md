# Running App

Android BLE heart-rate coach (Kotlin, Jetpack Compose, Room). See `README.md` for features and the phone-first testing workflow, and `knowledge/Vibe Coding Guidelines.md` for the working process.

## Agent skills

### Issue tracker

Issues live in GitHub Issues for `chrissuarez/Running-App`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary — the five canonical roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`) used as-is. See `docs/agents/triage-labels.md`.

### Codex review loop

`/codex-loop [PR]` drives a PR through Codex review until it is clean or the findings have become
tickets. It is a global skill (`~/.claude/skills/codex-loop/`), shared across projects. It reads the
repo from `gh repo view`; the unit-test command it runs here is `./gradlew testDebugUnitTest`, and
connected Android tests must stay unrun — they uninstall the app and wipe Chris's run history.

### The user handbook — ship it inside the feature's own PR

`docs/guide/index.html` is the plain-English handbook: what every feature does and where in the app
to find it. It is written for Chris, who is not a developer — no ticket numbers, no class names, no
implementation detail.

**A user-visible feature is not finished until the handbook describes it, and the handbook edit
belongs in that feature's OWN branch and PR — never a follow-up PR.**

A separate handbook PR costs a second full Codex loop for a ticket that is already closed. That is
the cost this rule exists to remove. So write the handbook edit before the feature's PR opens, and
let the one Codex loop review the code and the prose together.

1. While the feature branch is still open, edit `docs/guide/index.html` — add or amend the entry in
   the chapter it belongs to, refresh the "Newest thing" box at the top, and move both date stamps
   (masthead kicker + footer). If a chapter is missing for the feature, add one and add its jump
   link.
2. Commit it with the feature's own work, on the feature's own branch.
3. After the PR is merged and phone-tested, republish to the **same URL** so Chris's bookmark keeps
   working: `Artifact` with `file_path: docs/guide/index.html` and
   `url: https://claude.ai/code/artifact/5e644405-c86f-4fe6-a798-711c224d5902`.

A change nobody can see from the app — refactors, test-only work, internal tickets — does not
belong in it, and such a ticket needs no handbook edit at all.

### The roadmap — on request only, once at the end of a session

`https://claude.ai/code/artifact/da0c235c-48da-4b02-8754-b9f4ca99cd50` is the roadmap artifact.

**Do NOT refresh it when a PR merges.** It is not part of shipping a ticket.

Refresh it when Chris asks, and offer to refresh it once when a working session is wrapping up.
Rebuild it from live `gh issue list` in one pass: close what shipped this session, add every ticket
the work spun off, re-count the columns, and republish to the same `url` above.

Between refreshes the roadmap only has to name the next few tickets — it is a plan, not a live
mirror of the tracker.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root (created lazily by `/domain-modeling`). See `docs/agents/domain.md`.

When reporting information to me, be extremely concise and sacrifice grammar for sake of concision.