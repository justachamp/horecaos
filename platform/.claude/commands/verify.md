---
description: Run the full verification loop and report honestly (SDLC stage 4)
---

Verify the current change before a human sees it.

Use the `verifier` subagent so verification runs in its own context and cannot be
rationalised by the reasoning that produced the code.

Then, based on what the diff touches, run the relevant auditors — in parallel:

- `tenant-isolation-auditor` — any tenant-owned data
- `migration-safety` — anything under `src/main/resources/db/migration`
- `adr-compliance` — schema, module boundaries, integration, authorization, or migration

Report to me:

1. **PASS or FAIL**, first line, based on actual exit codes
2. Failures quoted verbatim with `file:line` — never paraphrased
3. Blocking findings from the auditors
4. Coverage gaps: what this change needed a test for and does not have

If it fails, fix and re-verify. Do not report PASS on a command you did not run, and do
not ask me to check something manually that you could have run yourself.
