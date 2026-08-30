---
name: spec-author
description: Use when turning an intent.md into a spec.md for HorecaOS Platform — stage 2 of the AI-native SDLC. Invoked by the /spec command and by the sdlc-spec CI workflow on a merged intent.
---

# Authoring a spec

Turn `intent/NNNN-slug/intent.md` into `spec.md` beside it, using
[`intent/TEMPLATE.spec.md`](../../../intent/TEMPLATE.spec.md).

The intent states a problem. The spec decides what to build. Do not quietly widen it — if
the right answer is bigger than the intent, say so under **Open questions** and let the
approver decide.

## Method

1. **Read the intent.** Take its framing seriously; the originator saw something.
2. **Ground it in the repo.** Read [AGENTS.md](../../../AGENTS.md), the relevant ADRs, and
   [docs/domains](../../../docs/domains/README.md). Find the aggregates and modules that
   already exist. Most specs are smaller than they look because the mechanism is built.
3. **Check migration coverage.** If it touches a legacy capability, find it in
   [docs/migration-coverage.md](../../../docs/migration-coverage.md). An unresolved
   `DECIDE` disposition is an open question with an owner — never silent deletion.
4. **Apply every policy skill** and fill the policy review table. Each row gets
   *Satisfied*, *Not applicable*, or *Needs decision*. Never leave one blank; "not
   applicable" with a one-line reason is a real answer, a blank is not.
5. **Name the ADR impact.** Implementing an accepted ADR, needing a new one, or
   contradicting an accepted one — which means a superseding ADR, not an edit.
6. **Write rollout and rollback.** Migration phase, flag or cohort, and the way back. A
   change with no rollback route does not get approved.
7. **Write acceptance criteria as named tests**, so stage 4 can prove each requirement.

## Flag rather than guess

The value of this stage is surfacing what a person must decide before code exists. Put it
in **Open questions** with a named owner and a blocking flag when:

- The domain model has no home for the concept
- It contradicts an accepted ADR
- It needs a product or commercial decision (pricing, entitlement, contractual limit)
- It touches payments or ordering migration, which move late and deliberately
- Personal data classification is unclear
- A provider capability is uneven across providers

A spec that ends with three sharp blocking questions is more useful than one that invented
three answers.

## Do not

- Write implementation detail that belongs in `plan.md` — no file lists, no method names
- Invent a table or aggregate absent from the approved domain model
- Mark a policy row *Satisfied* without saying how
- Resolve a blocking open question yourself
