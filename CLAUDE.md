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

### The user handbook — update it with every shipped feature

`docs/guide/index.html` is the plain-English handbook: what every feature does and where in the app
to find it. It is written for Chris, who is not a developer — no ticket numbers, no class names, no
implementation detail.

**Whenever a user-visible feature ships (merged and phone-tested), update it in the same sitting:**

1. Edit `docs/guide/index.html` — add or amend the entry in the chapter it belongs to, refresh the
   "Newest thing" box at the top, and move both date stamps (masthead kicker + footer).
2. Republish to the **same URL** so Chris's bookmark keeps working:
   `Artifact` with `file_path: docs/guide/index.html` and
   `url: https://claude.ai/code/artifact/5e644405-c86f-4fe6-a798-711c224d5902`.
3. **Refresh the roadmap in the same sitting** — it is the second half of this step, not a separate
   errand. Rebuild it from live `gh issue list` (close what shipped, add every ticket the work spun
   off, re-count the columns) and republish to
   `url: https://claude.ai/code/artifact/da0c235c-48da-4b02-8754-b9f4ca99cd50`. A roadmap still
   naming a shipped ticket as the next job is worse than no roadmap.
4. Commit the file with the feature's own work where possible.

A change nobody can see from the app — refactors, test-only work, internal tickets — does not belong
in it. If a chapter is missing for the feature, add one and add its jump link.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root (created lazily by `/domain-modeling`). See `docs/agents/domain.md`.

When reporting information to me, be extremely concise and sacrifice grammar for sake of concision.