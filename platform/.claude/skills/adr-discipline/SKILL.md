---
name: adr-discipline
description: Use whenever making, changing, or implementing an architectural decision in Qoida Platform — choosing a technology, adding a module, changing ownership or a boundary, or working from any file in docs/adr. Encodes ADR 0000's process and status model.
---

# ADR discipline

Consequential choices are recorded **before** implementation, using
[docs/adr/TEMPLATE.md](../../../docs/adr/TEMPLATE.md).

## Read both statuses

Every ADR carries a **decision status** and an **implementation status**. They are
independent and both matter.

- **`Decision status: Accepted`** — settled. Build it; do not reopen the argument. Read
  the `## Alternatives considered` table first: it records what was rejected and the
  trigger that would make it win again. If you want a different approach, check whether
  the trigger has actually fired.
- **`Decision status: Proposed`** — an `Open inputs` item could still change the
  structure.

Before implementing: close the `Open inputs` or record why the work proceeds without
them, set `Implementation status: In progress`, and update the canonical domain documents
the change requires.

## Execution order

Order lives **only** in the roadmap in [docs/adr/README.md](../../../docs/adr/README.md).
ADR numbers are identifiers, not sequence numbers. The roadmap is a dependency graph with
parallel tracks. Check [docs/minimum-viable-cutover.md](../../../docs/minimum-viable-cutover.md)
before assuming a capability is needed for the first release.

## Never edit an Accepted decision in place

Write a new ADR and set `Superseded by` on the old one. The record of what was believed,
and when, is the point.

## A new ADR is incomplete without

- Both status fields
- An `Open inputs` list with a **named owner** for each
- An `## Alternatives considered` table with revisit triggers
- A `## Consequences` section that includes **negative** consequences

An ADR presenting one option as if it were the only one is not an ADR.

## Do not re-decide these

Cross-cutting decisions already made, listed in AGENTS.md: authorization capabilities
(0025), provider installations and bindings (0026), audit and approval (0027), secrets
(0028), PII (0029), policy resolution (0030), HTTP conventions (0031), event governance
(0032), caching (0033). Reuse the shared mechanism rather than building a module-local
one — V0012 exists because `ordering` built its own policy table before 0030 landed.

## Before saying it is done

- [ ] Decision recorded before implementation, not after
- [ ] Both statuses set correctly
- [ ] `Open inputs` closed or explicitly deferred with an owner
- [ ] Canonical domain documents updated
- [ ] `python3 tools/checks/repo_hygiene.py` passes
