# Vibe Coding Guide (VS Code + AI)

This document defines how we build software using AI-assisted development in VS Code.
The goal is to ship quickly **without** creating a messy, fragile codebase.

Use this as the default operating system for all development in this repo.

---

## 1) Core principles

### 1.1 Build in small slices
Prefer many small working steps over one big “magic” change.

**Good**
- Add a UI screen (static)
- Add data model
- Add read query
- Wire UI to data
- Add validation

**Bad**
- “Build the entire app with auth, payments, admin panel, analytics”

### 1.2 Plan first, code second
AI must propose a short plan before writing code.
No code until we agree on the next small step.

### 1.3 Small diffs only
Each change should be easy to review and revert.

If a small feature requires changes across many files:
- stop
- reduce scope
- implement the smallest viable version first

### 1.4 No surprise dependencies
Do not add new libraries unless explicitly requested.
Prefer built-in platform features and existing dependencies.

### 1.5 Always run locally
If it compiles but does not behave correctly, it is not done.
Every slice must be verified locally.

### 1.6 Maintainability beats cleverness
Prefer boring, readable code and clear patterns.

---

## 2) The development loop (repeat this)

### Step 1: Define the slice in one sentence
Example:
> “Users can favourite an item.”

### Step 2: Define success (acceptance criteria)
3 to 7 bullet points, observable outcomes.

Example:
- A user can favourite an item
- Favourites persist after refresh
- UI clearly shows favourited state
- Handles empty and error states

### Step 3: Ask AI for a plan (no code yet)
AI returns:
- assumptions
- risks
- step-by-step plan (small, incremental)

### Step 4: Implement ONE step only
- minimal code
- minimal files
- no refactors unless necessary

### Step 5: Verify locally
- run the app
- confirm acceptance criteria for this slice

### Step 6: Commit
Commit after each working step.
If a change breaks things, revert quickly.

---

## 3) Task slicing framework (how to break down features)

For most features, work in this order:

### Layer A: UI only
- Build the layout
- Add empty states
- Mobile-first behaviour
- Accessibility basics

### Layer B: Data model
- Define the data shape
- Validate inputs
- Decide relationships

### Layer C: Data access
- Read
- Create
- Update
- Delete

### Layer D: Wiring
- Connect UI to data layer
- Loading and error states
- Optimistic updates if needed

### Layer E: Quality
- Tests for the happy path
- One or two edge cases
- Docs for how to run and verify

---

## 4) Rules for AI changes

### AI must always:
- keep diffs small
- follow existing project patterns
- update types/interfaces when changing data
- add useful error handling
- preserve behaviour unless asked to change it

### AI must never:
- remove features “to simplify”
- rename large sections of code without reason
- introduce new dependencies without permission
- move files around casually
- dump lots of code without explaining how to run it

---

## 5) Prompt templates (copy/paste)

### 5.1 Plan-first prompt
> Act as a senior engineer. Propose a step-by-step plan to implement: [FEATURE].
> Keep it minimal, safe, and incremental. List assumptions and risks.
> Do not write code yet.

### 5.2 Implement one step only
> Implement Step 1 only. Keep changes small and localised.
> Do not add new libraries. After the change, tell me how to run and verify it.

### 5.3 Debug with evidence
> Debug this issue. First list likely causes and how to confirm each one.
> Only then implement the smallest fix.

### 5.4 Refactor safely
> Refactor for readability and maintainability without changing behaviour.
> Keep the diff small. No new dependencies.

### 5.5 Mobile responsive pass
> Make this UI properly mobile responsive.
> Preserve behaviour. Ensure touch targets are usable.

### 5.6 Tests only
> Add a small set of tests for [COMPONENT/FUNCTION].
> Cover the happy path and one edge case. Do not refactor existing code.

### 5.7 Documentation only
> Write concise docs for this feature: purpose, how it works, how to run it, limitations.

---

## 6) Quality bar (Definition of Done)

Before marking a slice complete:

### Behaviour
- [ ] Happy path works
- [ ] Empty states handled
- [ ] Errors handled clearly

### UX and accessibility
- [ ] Works on mobile sizes
- [ ] No horizontal scrolling
- [ ] Buttons and links are easy to tap
- [ ] Keyboard navigation works (where relevant)

### Code health
- [ ] No obvious duplication
- [ ] Logic separated from UI (where applicable)
- [ ] Clear naming and file structure

### Safety
- [ ] No secrets in the repo
- [ ] Inputs validated server-side where relevant
- [ ] Permissions are not assumed

---

## 7) Debugging framework

When something breaks:

1) Reproduce it reliably
2) Record expected vs actual behaviour
3) Collect evidence (logs, stack trace, payloads)
4) List 3 to 5 possible causes
5) Test one hypothesis at a time
6) Apply the smallest fix
7) Verify with the same reproduction steps

Avoid “shotgun fixes”.

---

## 8) Security basics (non-negotiable)

### Never:
- commit API keys or secrets
- trust client-side validation alone
- expose admin actions to non-admin users

### Always:
- use environment variables for secrets
- validate inputs on the server side (if applicable)
- apply least privilege
- avoid logging sensitive data

---

## 9) Dependency discipline

Before adding any dependency:
- confirm the project does not already solve it
- confirm it is necessary for MVP
- confirm it is actively maintained
- prefer fewer dependencies over convenience

---

## 10) Repo hygiene (recommended)

- Use consistent formatting and linting
- Keep commits small and descriptive
- Prefer simple folder structure
- Document how to run the app locally in README.md

---

## 11) “Stop points” (pause and review)

Stop and reassess if:
- a small feature changes >300 lines
- a new library is introduced unexpectedly
- multiple unrelated refactors appear
- behaviour changes without being requested

When in doubt:
- revert to last commit
- reduce scope
- implement a smaller slice

---

## 12) The promise

If we follow this guide:
- we ship fast
- we learn by building
- we keep the codebase sane

If we ignore it:
- changes become fragile
- velocity drops
- rewrites become inevitable
