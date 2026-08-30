# Agent evals

`CLAUDE.md`, the skills in `.claude/skills/`, and the hooks in `.claude/hooks/` are
configuration. Changing one changes how every future session behaves, and nothing in the
Maven build notices. This suite is what notices.

```bash
make eval                              # all cases
python3 evals/run.py migration-*       # by id glob
python3 evals/run.py --min-pass 0.9    # the CI gate
python3 evals/run.py <id> --verbose    # see what the agent actually said
```

Cases run **read-only** — the agent gets `Read`, `Grep`, and `Glob`, never `Edit` or
`Bash` — so a case can never mutate the repository.

## A case

```json
{
  "why": "Nine migrations omitted the GRANT for horecaos_application. The failure is invisible until the first production start; V0035 exists to repair them.",
  "prompt": "I need to add a table ... Show me the complete SQL file you would create.",
  "expect_all": ["GRANT", "horecaos_application", "tenant_id", "timestamptz"],
  "forbid": ["timestamp without time zone"]
}
```

| Field | Meaning |
|---|---|
| `why` | The real migration, ADR, or incident this protects. Required. |
| `prompt` | The task, phrased as someone would actually ask it. |
| `expect_all` | Every regex must match the response. |
| `expect_any` | At least one must match — use when several phrasings are correct. |
| `forbid` | None may match. This is where the actual bug goes. |
| `tools` | Optional tool allowlist. Defaults to `Read,Grep,Glob`. |

Assertions are regexes over the response text, case-insensitive and `.`-matches-newline.

## Writing a good case

**Assert the behaviour, not the wording.** `expect_any` with several correct phrasings
beats `expect_all` on one sentence you happened to like. A case that fails when the agent
says the right thing differently is worse than no case — it gets muted, and then so does
the rest of the suite.

**Put the real bug in `forbid`.** `expect_all: ["tenant_id"]` passes if the word appears
in a comment. `forbid: ["WHERE\\s+id\\s*=\\s*\\?\\s*$"]` catches the actual leak.

**Phrase the prompt as a person would**, including the wrong framing — "add a column to
store the API key" is the request that actually arrives. A prompt that telegraphs the
right answer tests nothing.

**Every case needs a `why` naming something real.** A case nobody can justify should be
deleted, not carried. The suite is only trusted while every failure means something.

## When to add one

- **A defect reaches production.** Its postmortem produces a case, so the class cannot
  come back silently. This is the highest-value source.
- **A review comment is written for the third time** — see the feedback loop in
  [REVIEW.md](../REVIEW.md). If it can be checked deterministically, prefer
  `tools/checks/repo_hygiene.py` or a hook; those are cheaper and cannot drift.
- **A skill changes.** Add the case that proves the new rule lands.

Target 20–50 cases drawn from real recent work.

## What a failure means

A drop in pass rate almost always means a configuration change removed guidance something
depended on. Check the diff to `CLAUDE.md`, `.claude/skills/`, and `.claude/hooks/` before
merging — that is exactly what `.github/workflows/agent-evals.yml` gates.

`ERROR` is not `FAIL`. It means the CLI could not run — not authenticated, unsupported
model, rate limited — and the suite reports itself inconclusive rather than blaming the
agent for a policy answer it never gave.

## Limits

Assertions are regexes over prose, so they check that the agent *says* the right thing.
That is a real signal about whether the configuration steers behaviour, and it is not
proof the generated code is correct. Correctness is `make verify`'s job. Keep both.
