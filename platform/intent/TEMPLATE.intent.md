# Intent: <short title>

- **Originator:** <name>
- **Date:** <YYYY-MM-DD>
- **Status:** Draft | Accepted | Superseded | Withdrawn
- **Delivered by:** <commit or PR, once shipped>

## The problem

What is wrong or missing, in plain language. Who hits it, how often, and what they do
instead today. Write this as the person who noticed it, not as a solution.

## Evidence

What makes this real rather than suspected — a support thread, a metric, a legacy
behaviour in `../milliy`, a runbook that keeps getting used, an incident. Link it.

## What "solved" looks like

The outcome, observable from outside the system. Someone should be able to tell whether
this happened without reading the diff.

## Scope

- **In:** …
- **Out:** …

Naming what is out is what keeps stage 2 from designing a platform.

## Affected areas

Best guess only — stage 2 corrects this. Modules (`ordering`, `fulfillment`, …), tenant-
facing surfaces, providers, or legacy capabilities from
[docs/migration-coverage.md](../../docs/migration-coverage.md).

## Constraints and open questions

Deadlines, regulatory or contractual limits, decisions someone else owns. Anything you
know you do not know — name the person who does.

## Why not do nothing

The cost of leaving it. If the honest answer is "not much", say so; that is a legitimate
result and cheaper here than after a spec.
