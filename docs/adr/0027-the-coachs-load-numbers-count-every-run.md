# The coach's load numbers count every Run

The recent-Run list sent to the coach is filtered to AI-shared Runs: the AI-eligible queries
(`getLast3AiEligibleRunsOfStage` and its siblings) require `includeInAiTraining`. The Fitness,
Fatigue and Form figures, and the four weekly Effort Score totals, are not filtered. They come from
`getScoredRunsFlow` and `getRunVolumesFlow`, which have no `includeInAiTraining` condition, so they
include Runs recorded with AI sharing off or Testing mode on. The device test of #66 showed it: two
Simulation Runs moved Fatigue from 25 to 27, and 27 went to the coach (#245).

## Decision

Leave the curve and the weekly totals unfiltered. Every finished Run counts towards them.

Three reasons:

1. **One set of numbers.** The coach and the Progress screen must quote the same Fitness, Fatigue and
   Form. A curve filtered to AI-shared Runs disagrees with Progress by construction.
2. **Dropping Runs understates load.** A tired runner (Form -18, say) could look fresh to the coach
   and be given harder work. Missing load is the worse error.
3. **Consent is present at the ask.** No evaluation runs unless AI sharing is on for the Run that
   triggered it, and the three figures disclose no single Run.

The residue, stated plainly: in a week holding exactly one opted-out Run, that week's weekly total is
close to that Run's own Effort Score, so the coach can read it back. Chris chose to leave the weekly
totals as they are, accepting this risk, to keep the coach's full picture of load.

## Considered

- **Filter the curve and the weekly totals to AI-shared Runs.** Rejected, for reasons 1 and 2 above.
- **Coarsen the weekly totals** (round or bucket them). Rejected by Chris's choice: the coach keeps
  the full picture of load.
- **Stop sending the weekly totals.** Rejected by Chris's choice, for the same reason.

## Simulation Runs

Settled by #560: a Simulation Run is kept in History but counts toward nothing, so "every Run" here
means every real Run. It is out of the curve, the weekly totals and every other count, and it is
never AI-eligible.
