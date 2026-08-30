# The HorecaOS SDLC

How a change moves from someone noticing a problem to running in production, and where a
person has to make a decision.

This repository already had the hard part: [AGENTS.md](../AGENTS.md), an
[ADR set](adr/README.md), an approved domain model, and a migration coverage
register. What the SDLC adds is the machinery that
makes those rules apply *while* code is being written rather than after — and that turns a
review comment written three times into a check that runs every time.

Code is not the bottleneck here. The human-speed steps around it are: writing the idea
down, deciding what to build, reviewing, and authorising a release. Those are where the
gates sit, and they stay human.

## The loop

```text
  1 PLAN        intent.md          the problem, in the originator's words
       |                           ── human gate: is this worth solving?
  2 DESIGN      spec.md            requirements + design, under repo policy
       |                           ── human gate: product owner approves
  3 BUILD       plan.md + diff     plan mode first, then implementation
       |
  4 TEST        make verify        the session proves its own work
       |
  5 DEPLOY      PR + release       Claude reviews; a person merges and authorises
       |                           ── human gate: code owner, then release manager
  6 MAINTAIN    bands + intent.md  production drift reopens the loop
       └──────────────────────────────────────────────────────────┘
```

Four gates. Everything between them is mechanical, and the agent does it.

---

## Stage 1 — Plan

**Artifact:** `intent/NNNN-slug/intent.md` · **Gate:** merging it

Anyone can open one, engineer or not. Describe the problem to Claude, keep going until it
is concrete, then:

```bash
/intent couriers lose the handover note when a shift ends mid-delivery
```

The intent states a **problem and an outcome**, never an implementation. See
[intent/README.md](../intent/README.md).

Merging it means the problem is worth solving. It does not approve any solution.

## Stage 2 — Design

**Artifact:** `spec.md` · **Gate:** product owner approval

On merge, `.github/workflows/sdlc-spec.yml` drafts the spec and opens it as a PR. Or run
it by hand:

```bash
/spec intent/0007-courier-shift-handover
```

The spec is written under this repository's policy skills and grounded in the ADRs and the
approved domain model — so most specs come back **smaller** than expected, because the
mechanism already exists.

The point of this stage is not the design. It is the **Open questions** table: what a
human must decide before code exists. A spec ending in three sharp blocking questions has
done its job better than one that invented three answers.

Higher-risk changes — payments, ordering migration, anything touching an accepted ADR —
route to the technical lead rather than the product owner.

## Stage 3 — Build

**Artifacts:** `plan.md`, the diff · **Gate:** none — you own your branch

Planning happens before implementation, in plan mode, where Claude reads the codebase
without changing it:

```bash
/plan intent/0007-courier-shift-handover
```

Interrogate the plan before accepting it — what breaks if step 3 is wrong, what it assumes
about production data, what happens if it ships half-finished. The bar: someone who was
not in the session could implement from the file alone. When implementation diverges, that
goes in the plan's Divergence log; a plan quietly rewritten to match the code is fiction.

**Independent steps run in parallel.** `plan.md` marks which steps touch different files:

```bash
claude --worktree feature-courier-handover
```

Start with two or three sessions. Add more only if review keeps up — unreviewed diffs are
not progress.

### What shapes the work

| | Where | What it does |
|---|---|---|
| Conventions | [CLAUDE.md](../CLAUDE.md) | Commands and the mistakes Claude repeats here. Under a page, on purpose. |
| Architecture | [AGENTS.md](../AGENTS.md) | The full rules. CLAUDE.md points at it rather than duplicating it. |
| Policy | `.claude/skills/` | Advisory, applied while code is written |
| Guardrails | `.claude/hooks/` | Deterministic. Block, not advise. |
| Helpers | `.claude/agents/` | Scoped subagents with their own context |

**Skills advise; hooks block.** That distinction is the whole governance model.

`.claude/hooks/protect-paths.sh` refuses to edit a committed migration, refuses to rewrite
an `Accepted` ADR's decision (while allowing its Implementation status to advance),
and refuses to touch `legacy-archive/` or `.env`. It fails with the reason and the correct
alternative, so the session recovers instead of arguing.

## Stage 4 — Test

**Artifact:** test output · **Gate:** none — this is what makes the next gate cheap

One command, exits non-zero on failure:

```bash
make verify
```

A session verifies its own work before a person sees it. Never ask a human to check
something you could run.

```bash
/verify
```

That runs the `verifier` subagent in its own context — so verification is not rationalised
by the reasoning that produced the code — plus the auditors relevant to the diff:
`tenant-isolation-auditor`, `migration-safety`, `adr-compliance`.

For a bug fix: **write the failing test first and commit it**, then fix. The test proves
the bug existed, not just that the code passes now.

### Two suites, two purposes

```bash
make verify   # is the code correct?
make lint     # do the repository's own rules hold?   (< 1 minute, no JVM)
make eval     # does the agent configuration still steer behaviour?
```

`make lint` runs [`tools/checks/repo_hygiene.py`](../tools/checks/repo_hygiene.py) —
deterministic rules drawn from real bugs in this repo, including the migration-order-aware
GRANT check that V0035 had to exist to repair — plus the hook and control-band test
suites. The rule count is whatever `CHECKS` in that file currently holds; read the file
rather than a number here.

`make eval` is the regression suite for the agent's *configuration*. Changing `CLAUDE.md`
or a skill changes how every future session behaves, and the Maven build will not notice.
See [evals/README.md](../evals/README.md).

## Stage 5 — Deploy

**Artifacts:** PR, review findings, release · **Gates:** code owner approval, then release
authorisation

Claude reviews every PR against [REVIEW.md](../REVIEW.md) — four passes, Important vs.
Nit, a nit cap, and an explicit out-of-scope list so it does not repeat CI. Findings never
block. Branch protection still requires a human code owner.

Tag `@claude` on a review comment and it pushes a fix to the branch. You still own the
merge.

Production is authorised, never inferred:

```bash
export HORECAOS_RELEASE_APPROVAL=CHG-1042   # release manager, in their own shell
```

Without it, `.claude/hooks/deploy-gate.sh` blocks anything that runs against production,
destroys data, or rewrites shared history. It deliberately does **not** block reading those
files, or writing a document that mentions them — a gate that blocks ordinary work is a
gate people route around.

Preparing a release, opening the PR, and rehearsing rollback in staging need no approval.
Rehearse rollback regularly, in staging, before you need it.

## Stage 6 — Maintain

**Artifact:** a new `intent.md` · **Gate:** on-call triage

The loop closes itself. [`ops/bands.yaml`](../ops/bands.yaml) defines control bands;
[`ops/control_band_watch.py`](../ops/control_band_watch.py) samples them on a schedule.

Detection is arithmetic — a model decides what a breach *means*, never whether one
happened, and it cannot move a threshold to make a problem go away.

| Tier | Trigger | Response |
|---|---|---|
| 1σ | one sample outside the band | log it |
| 2σ | or 8 consecutive samples one side of the mean | Claude diagnoses, read-only, and writes an `intent.md` |
| 3σ | severe | Claude may also open a PR or run a pre-approved runbook |

The Western Electric run rule matters as much as the sigma threshold: slow degradation
never trips a single-sample test.

Tier 3 still cannot reach production — the deploy gate applies regardless of what invoked
the command.

A diagnosis arrives as an `intent.md`, which is stage 1, which is where we started. On-call
triages the queue. When the fix ships, **the incident becomes an eval** so the class cannot
return silently.

See [docs/runbooks/control-band-response.md](runbooks/control-band-response.md).

---

## The feedback loop

The part that compounds. When the same problem appears twice, it stops being a comment:

| Seen twice | Goes into |
|---|---|
| A convention mistake | [CLAUDE.md](../CLAUDE.md) |
| A policy mistake | `.claude/skills/*/SKILL.md` |
| Anything deterministically detectable | `tools/checks/repo_hygiene.py` or a hook |
| A defect that reached production | `evals/cases/` |

A review comment written for the third time is a process failure, not a code failure.

## Measuring it

Leading — these move first:

- Time from conversation to committed `intent.md` (weeks → hours)
- First-pass CI success rate for agent-written changes (`ci-first-pass-rate` is a band)
- Share of review findings resolved without a human touching the branch
- Time to first review on a PR

Lagging — these are the ones that matter:

- `intent.md` acceptance rate into stage 2
- Rework cycles per change
- Defects caught in review vs. escaped to production
- Repeat incidents of the same class — the direct measure of whether eval coverage works

## Setup

Everything in this repository is already wired. What is left is outside it:

1. **`ANTHROPIC_API_KEY`** as a GitHub Actions secret — the three Claude workflows need it.
2. **Branch protection on `main`**: require CI, require a code owner review. The agent has
   no direct push route; changes arrive as PRs.
3. **A `CODEOWNERS` file** naming who holds the stage 5 gate per area.
4. **Schedule the watcher** from the ops host, not a GitHub runner — it needs production
   database access, which should not be reachable from CI:
   ```bash
   */15 * * * * cd /srv/horecaos-platform && python3 ops/control_band_watch.py --sample
   ```
   Run it with `--dry-run` for a week first, so the baselines fill before anything
   escalates. Detection on a three-sample baseline is noise.
5. **`pip install pyyaml`** on the ops host, for `ops/bands.yaml`.
6. **Point the metric sources** in `ops/bands.yaml` at the real Prometheus and database.
7. **Grow the evals** toward 20–50 cases from real recent work.

## Reference

| Path | Stage | Purpose |
|---|---|---|
| `intent/NNNN-*/intent.md` | 1 | The problem |
| `intent/NNNN-*/spec.md` | 2 | Requirements and design |
| `intent/NNNN-*/plan.md` | 3 | Implementation plan |
| `CLAUDE.md` | 3 | Session conventions |
| `.claude/skills/` | 2–3 | Policy, advisory |
| `.claude/agents/` | 3–4 | Scoped subagents |
| `.claude/hooks/` | 3–5 | Guardrails, deterministic |
| `tools/checks/repo_hygiene.py` | 4 | Repository rules |
| `evals/` | 4 | Agent-configuration regression suite |
| `REVIEW.md` | 5 | Review policy |
| `ops/bands.yaml` | 6 | Control bands |

Commands: `/intent`, `/spec`, `/plan`, `/verify`, `/sdlc`.
