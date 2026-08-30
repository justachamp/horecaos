# Control band breached

**Morning item** unless the metric's own runbook says otherwise.
**Last executed:** never — this is a draft.

A control band escalated and Claude wrote an `intent.md`. This is how to triage it.

Related: [docs/sdlc.md](../sdlc.md) stage 6, [ops/bands.yaml](../../ops/bands.yaml).

## What happened

`ops/control_band_watch.py` sampled a metric and found it outside its band. Detection is
arithmetic — mean and standard deviation over a rolling window, plus the Western Electric
run rule. A model never decides whether a breach happened, only what it means.

| Tier | Meaning | What already happened |
|---|---|---|
| 1σ | one sample outside the band | recorded in `ops/state/<metric>.jsonl`, nothing else |
| 2σ, or 8 consecutive samples one side of the mean | drift or a clear excursion | Claude diagnosed it **read-only** and wrote `intent/NNNN-<metric>-breach/intent.md` |
| 3σ | severe | as above, and Claude may have opened a PR or run a pre-approved runbook step |

Nothing reached production. `.claude/hooks/deploy-gate.sh` requires
`QOIDA_RELEASE_APPROVAL` regardless of what invoked the command.

## Triage

**1. Read the intent, and separate verified from inferred.**

The diagnosis is instructed to mark which is which and to state its confidence. Check that
it did. An honest "I could not determine the cause, here are the three things that would"
is a good result; a confident narrative with no quoted evidence is not, and should be
treated as unverified.

**2. Confirm the breach is real before acting on the diagnosis.**

```bash
python3 ops/control_band_watch.py --status
python3 ops/control_band_watch.py --sample --only <metric> --dry-run
tail -20 ops/state/<metric>.jsonl
```

Ask first whether the *baseline* is wrong. A deploy that legitimately changed a metric's
normal level produces breaches for a full window afterwards. That is a bands problem, not
an incident — go to "Tuning" below.

**3. Follow the metric's runbook.**

`ops/bands.yaml` names one per metric where it exists:

| Metric | Runbook |
|---|---|
| `outbox-backlog` | [outbox-not-draining.md](outbox-not-draining.md) |
| `dead-letter-arrivals` | [dead-letter-decision.md](dead-letter-decision.md) |
| `payment-callback-failures` | [payment-callback-failing.md](payment-callback-failing.md) |

**4. Route it.**

- **Real and urgent** → incident response. The intent becomes the incident record.
- **Real, not urgent** → leave the intent in the queue for the product owner. It is
  already in the right format for stage 2.
- **Not real** → set `Status: Withdrawn` in the intent and say why in one line. Then tune
  the band, because it will fire again tonight.

## Close the loop

**When a fix ships, add an eval.** This is the step that stops the same class of incident
recurring silently, and it is the one most often skipped.

```bash
$EDITOR evals/cases/<incident-slug>.json    # see evals/README.md
make eval
```

The `why` field names this incident. A case whose justification nobody can state should be
deleted, not carried.

Then ask which layer should have caught it:

| If it was | Put it in |
|---|---|
| deterministically detectable | `tools/checks/repo_hygiene.py`, or a hook |
| a policy the agent should have applied | the relevant `.claude/skills/*/SKILL.md` |
| a convention Claude keeps missing | [CLAUDE.md](../../CLAUDE.md) |
| a genuine design gap | a new ADR |

## Tuning a band

Bands are version-controlled. Change them in a PR, with the reason in the commit message —
a threshold quietly widened after a noisy week is how monitoring dies.

```yaml
- id: outbox-backlog
  direction: high        # high | low | both — which way is bad
  window: 30             # samples in the rolling baseline
  min_samples: 10        # below this, only record
  consecutive_drift: 8   # Western Electric run length
  tiers:
    1: log
    2: diagnose
    3: act               # use `diagnose` where an agent must never act alone
```

Symptoms and fixes:

- **Fires constantly** → the window is too short to hold the real cycle, or `direction`
  is `both` when only one way is bad.
- **Fired for a week after a deploy** → expected. The baseline is relearning. If the new
  level is permanent, that is not a reason to widen the band.
- **Missed a slow degradation** → lower `consecutive_drift`. The run rule, not sigma, is
  what catches drift.
- **Money, identity, or anything irreversible** → set tier 3 to `diagnose`, not `act`, as
  `payment-callback-failures` does. An agent diagnoses; a person acts.

## Adding a metric

Any command printing one number on stdout works — that is the entire contract.

```yaml
- id: my-metric
  description: >
    What it measures, and what a breach would actually mean for a user. Write the
    second half: it is what the diagnosis will be reasoning from at 3am.
  direction: high
  unit: rows
  runbook: docs/runbooks/....md
  source:
    command: psql "$QOIDA_DB_URL" -tAc "select count(*) from ..."
```

Then let it collect for a week with `--dry-run` before allowing it to escalate. Detection
on a three-sample baseline is noise, and a band that cries wolf in its first week never
gets trusted afterwards.

```bash
python3 ops/test_control_band.py    # the detection arithmetic has its own tests
```
