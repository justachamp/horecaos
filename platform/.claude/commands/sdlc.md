---
description: Show where a change is in the SDLC and what the next gate is
argument-hint: [intent directory, or blank for the current branch]
---

Report the SDLC state of **$ARGUMENTS** (if blank, infer it from the current branch and
working tree).

Determine, by reading the files rather than assuming:

1. **Stage** — which of intent.md / spec.md / plan.md exist, and their status fields
2. **The open gate** — who has to do what next, by name where the file says so
3. **Blocking open questions** — from the spec, with owners
4. **Divergence** — does the working tree match what plan.md says would change?
5. **Verification** — has `make verify` passed on the current tree, or is that unknown?
   Say "unknown", never assume.

Finish with a single next action. If the change has skipped a stage — code exists but no
plan, a plan with no approved spec — say so plainly and say what recovering looks like.
That is a real situation, not a failure; the loop is meant to be re-entered.
