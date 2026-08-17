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

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root (created lazily by `/domain-modeling`). See `docs/agents/domain.md`.

When reporting information to me, be extremely concise and sacrifice grammar for sake of concision.