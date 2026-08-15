---
name: codex-loop
description: Run the Codex review loop on a PR until it is clean or the findings left the ticket.
disable-model-invocation: true
---

# Codex loop

Drive a PR through Codex review — round after round — until Codex reports clean on the current HEAD,
or every remaining finding has become its own ticket. Fixes are dispatched to subagents; you triage,
verify, push and re-tag.

Argument: a PR number. With none, the PR for the current branch (`gh pr view --json number`).

## The round

Repeat until a **stop condition** below fires. One round is steps 1–6.

### 1. Ask for the review

```
gh pr comment <N> --body "@codex review"
```

Codex never re-reviews a new push on its own. Every round starts with this comment, including the
first — and record the **UTC timestamp** you sent it (`date -u +%Y-%m-%dT%H:%M:%SZ`), because that
is what tells this round's answers from the last one's. Not the comment id: see step 2.

### 2. Poll for the verdict

Codex answers in **two** places, and a round can finish in either:

- **Findings** — inline review comments:
  `gh api repos/chrissuarez/Running-App/pulls/<N>/comments --jq '.[]|select(.user.login=="chatgpt-codex-connector[bot]")|"\(.id) \(.path):\(.line)\n\(.body)"'`
- **Clean** — a top-level issue comment, *"Codex Review: Didn't find any major issues"*, carrying a
  **Reviewed commit** sha: `gh api repos/chrissuarez/Running-App/issues/<N>/comments`

Watch both. A poll that watches only the inline list sleeps through a clean verdict; a poll that
counts comments without filtering to the bot is fooled by your own replies.

**Separate this round's answers by `created_at`, never by comment id.** The two endpoints number
their comments from **different sequences**: an inline review comment's id (`3790247978`) is nowhere
near the issue comment id of the `@codex review` that asked for it (`5304184714`). Anchoring the
inline poll on the tag comment's id — `select(.id > <tag id>)` — therefore excludes *every* finding
for ever, and the poll runs its full 20 minutes and reports clean while a P1 sits on the PR. That
happened on #307. Filter both endpoints on the step-1 timestamp instead, and `--paginate` so a long
thread does not hide the newest page:

```
SINCE=<the step-1 timestamp>
gh api repos/chrissuarez/Running-App/pulls/<N>/comments --paginate \
  --jq ".[]|select(.user.login==\"chatgpt-codex-connector[bot]\")|select(.created_at > \"$SINCE\")|\"\(.id) \(.path):\(.line)\n\(.body)\""
gh api repos/chrissuarez/Running-App/issues/<N>/comments --paginate \
  --jq ".[]|select(.user.login==\"chatgpt-codex-connector[bot]\")|select(.created_at > \"$SINCE\")|.body"
```

Poll in the background (`run_in_background: true`) with a `sleep 30` loop, up to 20 minutes, and
report the round's outcome to Chris as soon as it lands. Codex typically answers in 4–10 minutes.

A poll that finds nothing is a claim you have to be able to defend. Before reporting a round clean
on a timeout, run the unfiltered inline query once — `select(.user.login=="chatgpt-codex-connector[bot]")`
and nothing else — and look at what comes back. Silence from a filter is not silence from Codex.

Check the **Reviewed commit sha against HEAD** (`git rev-parse --short=10 HEAD`). A verdict on an
older commit is a verdict on code you have already changed — re-tag and poll again.

### 3. Triage every finding

Sort each one into exactly one bucket before any code is written:

- **Real** — the defect exists in the current file, and it is about this ticket's subject. → step 4.
- **Stale re-report** — already fixed in an earlier round and raised again, often *verbatim* on
  fresh line numbers. Tell: `line` comes back `null`, and the current file already has the fix.
  Read the file before believing round N+1. → reply saying where it was fixed, write no code.
- **Off-ticket** — true, but about something the ticket never touched, or needing a change the
  ticket's scope cannot carry (a schema migration, a repo-wide convention). → step 5.

### 4. Fix the real ones with subagents

One subagent per real finding. Give each: the finding verbatim, the file and line, the ticket number
and what it is about, and this instruction — **verify the defect against the current code first, and
report back "not a defect" rather than writing a fix for something that is already right.**

Dispatch in parallel only where the findings touch **different files**. Two subagents editing one
file race and lose each other's work; run those sequentially.

Every fix carries a test that fails without it. Then, once:

```
./gradlew testDebugUnitTest
```

Connected Android tests stay unrun — they uninstall the app and wipe Chris's run history.

### 5. Split the off-ticket ones

`gh issue create --label needs-triage`, with: what happens, why it matters, what a fix needs, and
that it was split out of this PR. Reply to the Codex comment saying it is real and where it went.

A finding that has left the ticket's subject is the signal that the loop is **done**, not that the
PR is unfinished.

### 6. Push and answer

Commit in this repo's voice — a title that states the rule the change makes true, a body that says
what went wrong and why the fix belongs where it landed. Then reply to **every** finding of the
round:

```
gh api repos/chrissuarez/Running-App/pulls/<N>/comments/<comment-id>/replies -f body='...'
```

Real ones: what was wrong and how it is fixed. Stale: where it was already fixed. Off-ticket: the
issue number and why it is not this PR's to carry. Then back to step 1.

## Stop conditions

Stop, and report to Chris, when any of these fires:

- Codex reports clean on a Reviewed commit sha equal to HEAD.
- Every remaining finding is off-ticket and now has a ticket.
- Rounds are re-finding **one fault a level deeper** each time. Three instances of the same fault is
  the rule not being stated once — state it in one place instead of patching instance four, and if
  the next round finds instance five, split it.
- An ADR- or docs-only PR reaches ~2 rounds. Each decision closes one gap and opens the next; these
  never converge.

Then write the loop up as a memory (`pr<N>-codex-loop`, a line in `MEMORY.md` under Review process):
how many rounds, how many real, and the one rule worth carrying to the next PR.

## Merging

Ask Chris before merging — it is his call, not the loop's. When he says yes: **rebase-merge**, and
never `--delete-branch` on a PR another branch is stacked on; it auto-closes and locks the child.
