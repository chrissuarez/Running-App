# A requirement stated in numbers is not the coach's to judge

Two of the three Stages in the plan graduate on a 5K time: "Successfully complete a 5K under 30
minutes", and "Run a 5K in 24:59 or faster". Both were decided by the AI coach, and the prompt
carried six CRITICAL RULEs whose entire job was stopping a language model doing arithmetic badly on
a number the app had already measured — judge only from `fastest5kSeconds`, never divide a distance
by a duration, treat a null as an absence rather than a failure, and a treadmill exception with two
more rules fencing it. [#287](https://github.com/chrissuarez/Running-App/issues/287) then had to
make the coach *name* the Runs it graduated on, so that the names could be checked.

That is a great deal of machinery around a comparison. **"Is this 5K under 30 minutes" contains no
judgement.** It is arithmetic on a measured number, and every one of those rules exists because the
one thing standing between a wrong answer and an irreversible promotion was a sentence in a prompt.

So: **a Stage requirement that can be stated in numbers is stated in numbers, and the app answers
it.** `PlanStage` carries a `BestEffortRequirement` — a record distance and a time — beside the
prose. The prose stays, for the screen and for the coach's debrief. Where a Stage has one, the coach
is told in the prompt that it may not graduate, and `evaluateAndAdjustPlan` refuses a graduation
from it outright: a prompt sentence is a promise the code has to keep, and the two paths must never
both be able to grant.

Stage 1's "4 weeks of consistent Zone 2 training" keeps its prose and stays with the coach. What
counts as *consistent* is a real judgement, and writing one in code is a different decision from
this one.

## The rule, in one sentence

**Any finished Run not marked a Walk, whose Best Effort at the requirement's distance clears its
time, graduates the Stage.**

- **A Best Effort, measured or stated.** The window measured over the track, or a Stated Best Effort
  read off a treadmill console ([ADR 0015](./0015-a-stated-best-effort-is-read-off-a-console-not-off-an-average.md)).
  That ADR already says a stated effort places in the record book exactly as a measured one does;
  refusing it for a graduation while accepting it for a gold medal would be the app disagreeing with
  itself. It also retires the treadmill exception from the prompt, which only ever fired when the
  whole Run was exactly 5.0 km.
- **Never a Walk.** CONTEXT.md says a Walk holds no Best Effort, so the rule gets this for free by
  asking for one rather than measuring the distance itself — which matters, because the coach's own
  evidence map filtered Walks out separately and a rule written the other way would have let a brisk
  5 km walk graduate a Stage.
- **Any kind of Run otherwise, Open Run included.** The old bar on an Open Run is sound for a
  *structural* requirement — an Open Run followed no structure, so nothing can be said about what
  was completed — and wrong for a *time* requirement. A parkrun is the truest 5K test there is, and
  the number is the number wherever it turned up. No further carve-outs: the moment one is carved
  out we are back to the structural reasoning this narrowing exists to drop.

## The app asks first, and the coach is asked afterwards

The rule runs in the finalize path, **before** the coach is asked anything. Where a Long Run happens
to contain a qualifying 5K, both would otherwise have a view: the rule grants and moves the stored
Stage on, and the existing "the Run was recorded under a Stage the runner has since left" guard then
sees the Stage has moved and skips the coach for free. That guard was written for a graduation
landing mid-Run ([#234](https://github.com/chrissuarez/Running-App/issues/234)) and catches this case
exactly.

The order is the decision, not an implementation detail, so a test pins it. The failure mode of
losing it to a later refactor is the coach writing a Prescription into a Stage the runner has
already left.

## No lock, and no round trip

The coach's graduation is wrapped in a mutex and re-checks that its evidence still stands, because a
Gemini round trip leaves seconds in which the Run behind it can be deleted from the history screen.
A rule evaluated inside finalize has no round trip and no window: the Run exists, its effort clears,
it grants. Neither the lock nor the re-check belongs here, and copying them across out of habit would
be machinery guarding nothing.

## Forwards only, and never taken back

- **No pass over history.** A launch that silently jumped the runner two Stages, on evidence recorded
  under different rules, is the highest-stakes version of the one act the app can never undo. The
  Stage card names an already-beaten bar out loud instead
  ([#293](https://github.com/chrissuarez/Running-App/issues/293)).
- **Never revoked.** Deleting the Run that graduated, or marking it a Walk afterwards, does not
  un-graduate. CONTEXT.md already says this of the Walk mark, and the rule holds the same line for a
  delete.
- **A Stated Best Effort typed after the Run re-asks the rule.** This is the one place the rule looks
  at a Run again, and it has to: a treadmill 5K is stated after the Run has ended, so a rule that
  only ever looked at the finish would accept a measured 5K and silently refuse a stated one. It is
  the same three edges either way, and the Run's own Stage must still be the active one — a claim
  typed weeks later about an old Run graduates nothing. Note this is the app's rule and not the
  coach's evaluation, which is still never replayed
  ([ADR 0008](./0008-a-stated-distance-is-a-real-distance.md)).

## The message is the app's

"You ran 5 km in 27:12. Stage 2: Sub-30 Bridge complete." The app writes it, not the coach. The test
is a Quality workout so the coach is not called on it anyway; more to the point, handing a model a
decision that is already made is inviting it to editorialise its way into disagreeing with a fact. It
also means the graduation lands offline and with no Gemini key.

## Consequences

- **Stage 3 can graduate by running its own test.** It could not before: evaluation is Long-only and
  the 5K Peak Test is a Quality workout, so finishing it asked the coach nothing.
- **Stage 2 needs a way to attempt a 5K at all** — its Long run walks a minute in four, and a walk
  break inside a Best Effort counts against it. That is
  [#291](https://github.com/chrissuarez/Running-App/issues/291), and the two ship together: a rule
  with no test workout leaves Stage 2 as unreachable as it was, and a test workout with no rule
  graduates nothing.
- **Six prompt rules go**, and one takes their place. What is left of the 5K numbers in the prompt is
  context for the debrief, not evidence for a decision.
- **The last Stage of the plan has nowhere to graduate to**, and the rule makes that reachable for
  the first time. It writes the congratulation and deliberately leaves the standing Prescription
  alone, which is the least wrong thing available until
  [#294](https://github.com/chrissuarez/Running-App/issues/294) decides what finishing a plan
  actually offers.
- **A future requirement stated in numbers has a place to go.** A pace, a distance, a duration — each
  is a new kind of requirement and a new comparison, and none of them is a new argument about who
  decides.
