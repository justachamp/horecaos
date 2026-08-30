# ADR 0000: ADR process, status model, and numbering

- Decision status: Accepted
- Implementation status: Not applicable — this record governs documents, not
  code. Verified in `docs/adr/`: `TEMPLATE.md` exists, all 57 numbered records
  carry both status fields, `Open inputs`, `## Alternatives considered` and
  `## Consequences`, and the roadmap table lives only in `README.md`.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: none
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The first twenty-four Qoida decision records were written as detailed
specifications. They describe the chosen design precisely, but they do not
record which options were rejected, why, or under what conditions the rejection
should be revisited. A reader six months from now cannot distinguish a
considered rejection from an unexamined default, so every settled question is
open to re-litigation.

A second problem is status. The original set used `To Do` for twenty of
twenty-four records. That single label covered two unrelated facts: "the
decision is settled but no code exists yet" and "the decision itself is still
open because product, legal, or a provider has not answered". Planning,
onboarding, and review all depend on telling those apart.

## Decision

- Every ADR uses [`TEMPLATE.md`](../TEMPLATE.md) and carries two independent
  status fields.
  - **Decision status** describes the decision: `Proposed`, `Accepted`,
    `Rejected`, or `Superseded`.
  - **Implementation status** describes the code: `Not started`,
    `Partial`, `Built`, or `Not applicable`.
- `Accepted` plus `Not started` is a normal and common state. It means the
  design is settled and may be built without reopening the argument.
- `Proposed` means the structure could still change because an `Open inputs`
  item is unresolved. Any ADR with a structural open input stays `Proposed`.
- Every ADR lists `Open inputs` explicitly, or `none`. An empty list is a
  claim that no external answer is required.
- Every ADR has an `## Alternatives considered` section naming the real
  options, why each lost, and the trigger that would make it win again. "No
  alternative existed" is not an acceptable entry.
- Every ADR has a `## Consequences` section including negative consequences and
  accepted trade-offs.
- ADR numbers are immutable identifiers assigned in creation order. They are
  not execution order. Execution order lives only in the roadmap table in
  [`README.md`](../../../README.md), which is the single authoritative sequence.
- A decision that changes is not edited in place once `Accepted`. Write a new
  ADR and set `Superseded by` on the old one.
- Deciders are named. Where product, legal, finance, or security must own an
  input, that owner is named in `Open inputs`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep one `Status` field with kanban values (`To Do`, `In Progress`, `Done`) | Conflates decision maturity with build progress; a reader cannot tell an unbuilt settled decision from an unsettled one, which is the exact failure this ADR exists to fix | Never; the two facts are independent by nature |
| Adopt MADR verbatim | MADR's single `status` field has the same conflation, and its short form discourages the operational specification these records legitimately carry | If the specification content moves to separate design docs, leaving ADRs short |
| Renumber ADRs so number equals execution order | Numbers become unstable identifiers; every cross-reference in code comments, commits, and prior records breaks whenever the plan is resequenced, which happens often | Never; identifier stability is worth more than a tidy sequence |
| Keep specifications out of ADRs and write separate design documents | Doubles the documents to keep synchronized for a team this size, and the ADR would lose the constraints that justify the decision | When more than one team owns separate capabilities and the records diverge in audience |

## Consequences

### Positive

- A reader can tell in one line whether an argument is open or closed.
- Rejected options and their revisit triggers are recorded once, so a later
  proposal to "just use X" is answered by the record rather than by memory.
- Planning can filter on implementation status without touching decisions.

### Negative

- Two status fields must both be maintained; a stale `Implementation status` is
  now possible and was not before.
- Writing genuine alternatives is slower than writing the chosen design alone.

### Accepted trade-offs

- ADR numbers no longer suggest reading order. The roadmap table becomes a
  document that must be kept correct, because nothing else conveys sequence.

## Implementation checklist

- [x] Add `TEMPLATE.md`.
- [x] Convert ADRs 0001–0024 to the two-field status model.
- [x] Add `## Alternatives considered` to every existing ADR.
- [x] Add `## Consequences` to ADRs 0005–0024.
- [x] Move execution order into the roadmap table only.

## Exit criteria

Every file in `docs/adr` carries both status fields, an alternatives section
with revisit triggers, and a consequences section, and no ADR states its own
execution position as fact.
