---
description: Capture a problem as intent/NNNN-slug/intent.md (SDLC stage 1)
argument-hint: [the problem, in your own words]
---

Capture this as an intent for HorecaOS Platform: **$ARGUMENTS**

Stage 1 of [docs/sdlc.md](docs/sdlc.md). The originator may not be an engineer, and does
not need to be.

1. If the problem is not yet concrete, ask about it before writing anything. Who is
   blocked, how often, what they do instead today, and what "fixed" looks like from
   outside the system. Ask about scope — especially what is *out*. Stop asking once you
   could explain the problem to someone else; do not interrogate.
2. Allocate the next free number: `ls intent/` and take the highest + 1.
3. Write `intent/NNNN-slug/intent.md` from `intent/TEMPLATE.intent.md`, in the
   originator's framing and vocabulary, not translated into platform terms.
4. Do **not** design the solution. "Couriers lose the handover note when a shift ends
   mid-delivery" is intent; "add a `handover_note` column" is a stage 2 decision.
5. Show it and ask what you got wrong. It is their intent.

Then tell them the next step: open a PR with it. Merging is the first human gate — it
means the problem is worth solving, not that any particular solution is approved.
