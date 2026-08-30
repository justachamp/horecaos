# Intent

A change to Qoida Platform starts here, as a problem stated in the originator's own
words — before anyone decides whether it is an ADR, a migration, or a frontend slice.

Each change gets one directory:

```text
intent/0007-courier-shift-handover/
├── intent.md   # stage 1 — the problem, by whoever noticed it
├── spec.md     # stage 2 — requirements and design, under repo policy
└── plan.md     # stage 3 — implementation plan, written in plan mode
```

Numbers are allocated in order and never reused. They are identifiers, not priorities —
the same rule ADR numbers follow.

## How to write one

You do not need to be an engineer, and you do not need to know the codebase.

1. Describe the problem to Claude conversationally. Keep going until it is concrete:
   who is blocked, what they do instead today, what "fixed" would look like.
2. Ask Claude to write it up using [`TEMPLATE.intent.md`](TEMPLATE.intent.md).
3. Read it and correct anything it misunderstood. It is your intent, not Claude's.
4. Open a PR adding `intent/NNNN-slug/intent.md`.

Describe the **problem and the outcome**, not the implementation. "Couriers lose the
handover note when a shift ends mid-delivery" is intent. "Add a `handover_note` column"
is a design decision that stage 2 gets to make, or reject.

## What happens next

Merging an `intent.md` is the first human gate: it means the problem is worth solving.
CI then opens a draft `spec.md` PR against it (`.github/workflows/sdlc-spec.yml`), written
under this repository's skills — tenant isolation, HTTP conventions, migration safety, ADR
discipline — with anything it could not resolve listed under **Open questions**.

Those open questions are the point. A spec that flags "this needs an ADR because it adds a
provider capability" has done its job; nobody should discover that in code review.

The full loop, including what each gate means and who holds it, is in
[docs/sdlc.md](../docs/sdlc.md).

## Status

An intent is live until its plan ships. When it does, add the merge commit to the
`Delivered by` line in `intent.md` and leave the directory in place. The trail from
problem → spec → plan → diff is the audit record, and stage 6 writes new intents into it
automatically when production drifts.
