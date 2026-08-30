---
description: Turn an intent into a reviewed spec.md under repo policy (SDLC stage 2)
argument-hint: <intent directory, e.g. intent/0007-courier-shift-handover>
---

Write the spec for **$ARGUMENTS**.

Use the `spec-author` skill, and apply every policy skill in `.claude/skills/` —
tenant-isolation, flyway-migration, http-api-conventions, event-contract, secrets-and-pii,
adr-discipline — filling the policy review table with a real verdict per row.

Ground it in what exists: AGENTS.md, the relevant ADRs, `docs/domains`, and
`docs/migration-coverage.md`. Most specs shrink once you find the mechanism already built.

Write `spec.md` beside the intent, from `intent/TEMPLATE.spec.md`.

Flag rather than guess. Anything needing a human decision goes in **Open questions** with
a named owner and a blocking flag. Three sharp blocking questions beat three invented
answers — surfacing them before code exists is the entire point of this stage.

Finish by telling the approver which open questions block stage 3, and who owns each.
