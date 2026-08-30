---
description: Produce an implementation plan.md from an approved spec (SDLC stage 3)
argument-hint: <intent directory, e.g. intent/0007-courier-shift-handover>
---

Plan the implementation of **$ARGUMENTS**.

**Read only — change nothing.** If you are not in plan mode, say so and ask to switch
before reading further.

First check the spec is approved and no blocking open question is still open. If one is,
stop and name it; planning around an unresolved blocker wastes the plan.

Then read the actual codebase — the modules, the existing migrations, the tests that
already cover this area. Write `plan.md` beside the spec from `intent/TEMPLATE.plan.md`:

- **Files that change**, each with what changes
- **Order of work**, each step ending somewhere `make verify` passes, with independent
  steps marked — those are the ones that can run as parallel sessions in worktrees
- **Risks**, with the riskiest step named explicitly
- **Proof** — the specific tests that must exist and pass, not just `make verify`

The bar: someone who was not in this session could implement it from the file alone.

Before presenting it, interrogate your own plan and answer in the file — what breaks if
step 3 is wrong, what this assumes about production data, what happens if it is deployed
half-finished.

Then ask me to interrogate it. Do not start implementing until the plan is accepted, and
if implementation later diverges, record it in the Divergence log rather than quietly
rewriting the plan.
