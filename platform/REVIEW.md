# Review policy

How pull requests on HorecaOS Platform are reviewed, by people and by Claude. Claude reviews
every PR automatically (`.github/workflows/claude-review.yml`); this file is its
instruction set as much as it is ours.

Findings do not merge or block anything. Branch protection still requires a human code
owner's approval. The point of automated review is that the human arrives at a PR whose
mechanical problems are already found, so their attention goes to the judgement calls.

## Passes

Run all four. Report them separately — a security finding buried under naming suggestions
gets missed.

### 1. Correctness

Logic errors, unhandled edge cases, error handling, concurrency. In this codebase, most
often:

- A query with no tenant predicate
- A consumer that is not idempotent, when delivery is at-least-once
- A publish inside a business transaction instead of via the outbox
- An aggregate mutated without optimistic locking
- Money in floating point, or an instant stored without a zone
- A backfill that cannot be restarted after being killed halfway

### 2. Security

- **Cross-tenant access.** State the leak as a path: which caller, holding which token,
  reaches which row. Anything less is a suspicion — label it as one.
- Tenant context taken from a header, parameter, or body rather than the signed claim.
- A mutating endpoint with no declared capability (ADR 0025).
- A secret value where a reference belongs (ADR 0028).
- Personal data in an event, log, trace, metric, dead-letter summary, or URL (ADR 0029).
- A client secret in a frontend bundle; a token in local storage.

### 3. Compliance with the spec and plan

If the PR links an `intent/NNNN-*/` directory:

- Does the diff do what `spec.md` says, and only that?
- Do the files changed match `plan.md`? Unexplained divergence belongs in the plan's
  Divergence log, not in review comments.
- Are the acceptance criteria actually proven by tests that exist?
- Is any blocking open question still open?

A PR with no intent directory is fine for a fix or a chore. For a feature, ask where the
intent went — it is usually a sign the change is larger than it looks.

### 4. Architecture and decisions

- Does it build a module-local version of something ADRs 0025–0033 already own? This is
  the single most common real finding.
- Does it create a table or aggregate absent from `docs/domains`?
- Does it import another module's internals, or pull framework or provider types into
  domain code?
- Does it contradict an `Accepted` ADR? That requires a superseding ADR, never an
  in-place edit. Check the `## Alternatives considered` revisit trigger and say whether it
  has actually fired.
- Does it touch a legacy capability with an unresolved `DECIDE` in
  `docs/migration-coverage.md`?

## Important vs. Nit

**Important** — would cause a defect, a leak, data loss, or an unrecoverable deploy; or
contradicts an accepted decision. Say what breaks and how.

**Nit** — style, naming, structure, and preference. Prefix with `nit:`. **At most five
per PR**, and none at all if there is an Important finding outstanding: a wall of nits
next to a tenant leak trains people to skim the whole review.

Anything you are not confident about is a **question**, not a finding. "Is this reachable
with a stale token?" is useful. A confident-sounding wrong finding costs more than
silence, because someone spends an hour disproving it.

## Out of scope

Do not comment on:

- Generated files — OpenAPI clients, `target/`, `legacy-archive/`
- Anything CI already enforces: `make lint`, `make verify`, hygiene checks. If CI is red,
  say "CI is red" once and stop.
- Formatting that no tool enforces
- Rewriting working code to a different but equivalent style
- ADR decisions themselves. Review the implementation; argue the decision in a new ADR.

## Responding to review

Tag `@claude` on a review comment and it will push a fix to the branch. Read the fix —
you own the merge, not the agent.

## The feedback loop

This is the part that compounds: **when the same finding appears twice, it stops being a
review comment.**

- A recurring convention mistake → a line in [CLAUDE.md](CLAUDE.md)
- A recurring policy mistake → a rule in the relevant `.claude/skills/*/SKILL.md`
- A mistake that can be detected deterministically → a check in
  `tools/checks/repo_hygiene.py`, or a hook in `.claude/hooks/`
- A defect that reached production → an eval in `evals/cases/`, so it cannot come back
  silently

A review comment written for the third time is a process failure, not a code failure.
