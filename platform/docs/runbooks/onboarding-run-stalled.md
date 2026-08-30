# An onboarding run has stopped

**Morning digest item.** **Last executed:** never — this is a draft.

```bash
cd /opt/horecaos/horecaos-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env'
```

## Before anything: no customer is affected

A tenant with an onboarding run is a tenant that is **not live**. Nothing is
being ordered through it, no money is in flight, and no restaurant is standing
at a screen waiting for this. That is why this arrives at 09:00 in a digest and
not at 03:00 on the phone, and it is why nothing below is worth rushing.

What is affected is a commercial promise: somebody was told their platform would
be ready. The whole run is on record, so you can say exactly where it stopped.

## 1. Which run, and which step?

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT r.id AS run_id, r.tenant_id, r.status AS run_status, r.current_phase,
          s.step_key, s.status AS step_status, s.attempt_count,
          s.last_error_code, now() - s.updated_at AS stopped_for
     FROM tenant.onboarding_steps s
     JOIN tenant.onboarding_runs r ON r.id = s.run_id
    WHERE r.status NOT IN ('ACTIVE', 'CANCELLED')
      AND s.step_key <> 'TENANT_ACTIVATE'
      AND s.status IN ('PENDING', 'FAILED')
      AND s.available_at <= now()
    ORDER BY s.updated_at"
```

That is the alert's own query. The top row is what raised it.

**Check:** `step_status`.

- `FAILED` — the step ran and gave up. `last_error_code` says why. Step 2.
- `PENDING` with a large `stopped_for` — the step is due and nothing has picked
  it up. The workers are not running. Step 3.
- **No rows at all** — it recovered between the probe run and now. Nothing to
  do; the digest is a report of the last twenty-four hours, not of this second.

`TENANT_ACTIVATE` is excluded on purpose, here and in the gauge. It is always
waiting for a person, so it is never evidence that anything is wrong. If a
tenant is sitting in `READY` and nobody has activated it, that is section 5 and
not an incident.

## 2. It is one of four things

| `last_error_code` | What happened | What you do |
|---|---|---|
| `IDENTITY_DRIFT` | Keycloak holds an organization for this tenant that does not match what the platform recorded. ADR 0009 refuses to reconcile drift, because retrying drift produces more drift | Do not resume yet. Read [deploy.md](deploy.md) on the realm, decide which side is right, correct Keycloak by hand, then resume |
| `TRANSIENT_INFRASTRUCTURE` | Five attempts of talking to Keycloak all failed | Fix Keycloak first — `qc ps keycloak`, `qc logs --tail 100 keycloak`. Resuming into an outage just spends five more attempts |
| `OWNER_NOT_SUPPLIED` | The run was started with neither an owner email nor an existing subject id | **There is no repair.** The missing fact was an argument to the start call, resuming replays the same argument, and ADR 0008's `cancel` endpoint is listed but not built — so the partial unique index on one active run per tenant means this tenant cannot be given a second run either. Record it and escalate; it needs the cancel endpoint before it needs a runbook |
| `NO_BRAND` / `NO_LOCATION` | The tenant has no brand, or no brand has a location, so it could not take an order if it were live | Create the brand or location through the ordinary tenant API, then resume. This check is doing its job — it is what stops a tenant going live broken |

Anything else is a step whose handler has changed since this was written. The
code is `tenancy.application.onboarding.OnboardingStepHandlers`, and every code
it can produce is a string literal in it.

## 3. `PENDING` and nothing is picking it up

```bash
qc ps platform-app && qc logs --tail 200 platform-app | grep -iE "onboarding|scheduled task"
```

**Check:** three outcomes, and they are different problems.

- **A repeating exception from `OnboardingScheduler.drive`** — the claim query
  itself is failing, so no run anywhere is advancing and this alert is about the
  platform rather than about one tenant. Read the SQL state in the message; it
  is a code bug and needs a fix, not a resume.
- **No onboarding activity at all** — the scheduler is a switch,
  `horecaos.onboarding.scheduler.enabled`, and it is off in that container's
  environment. A deploy mistake rather than an incident: fix the environment and
  redeploy.
- **Ordinary activity** — the workers are alive and it is one step that is
  stuck. Keep reading.

In the third case, look for a step stuck mid-flight:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT run_id, step_key, attempt_count, claimed_at, now() - claimed_at AS held_for
     FROM tenant.onboarding_steps WHERE status = 'RUNNING' ORDER BY claimed_at"
```

**Check:** `held_for`. A claim is leased for five minutes and is handed back
automatically after that, so anything under five minutes is a step in progress.
A claim held for much longer than five minutes with nothing else moving means
the worker thread is blocked inside a handler — almost always a Keycloak call
with no answer — and the fix is Keycloak, then restart `platform-app`.

Do not clear `claim_token` by hand. The lease expiring does that safely; a
hand-cleared token lets two workers believe they own the same external side
effect.

## 4. Resume it

Resume reopens every `FAILED` step in the run and never touches a completed one.
A reopened step reconciles against its stored `external_reference` rather than
creating a second organization, which is what makes this safe to run twice.

```bash
read -rp  'run id, from section 1:    ' RUN_ID
read -rp  'tenant id, from section 1: ' TENANT_ID
read -rp  'what you fixed:            ' FIX
read -rsp 'access token:              ' TOKEN; echo

curl -fsS -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: application/json' \
  --data "$(printf '{"reason":"resumed after fixing %s"}' "${FIX}")" \
  "https://api.horecaos.uz/api/v1/control-plane/tenants/${TENANT_ID}/onboarding-runs/${RUN_ID}/resume"

unset TOKEN
```

- The `Idempotency-Key` is **required** (ADR 0031) and must be **fresh for each
  attempt**. Reusing one replays the first response and reopens nothing, which
  looks exactly like a resume that did nothing.
- `reason` is required, is audited as `tenant.onboarding_resumed`, and is the
  only record of why anyone touched this run.
- The token is for your own operator account, which needs the
  `TENANT_ONBOARDING_MANAGE` capability (ADR 0025). It is read into a variable
  rather than typed into the command line for the reason
  `infra/production/ops/bao-get.sh` states: an argument list is world-readable
  on the host. **There is deliberately no direct-grant client on production**, so
  the token comes from signing in through the front end;
  `infra/keycloak/create-local-web-client.sh` builds the equivalent client for a
  laptop and must not be pointed at this realm. On a fresh deployment the
  `platform-admin` realm role confers `IAM_GRANT_MANAGE`, so an operator can
  grant themselves the capability through the ordinary audited API first.

**Check:** the response body is `{"reopenedSteps":N}`. `N` of zero means there
was no `FAILED` step to reopen, so what you are looking at is section 3 and not
this one. Then re-run the query in section 1: within about five seconds the step
should be `RUNNING` or `COMPLETED`.

## 5. It is `READY` and nobody has activated it

That is not this alert and never will be. A run reaching `READY` means every
required check passed and the go-live decision is now a person's:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT id, tenant_id, status, updated_at FROM tenant.onboarding_runs
    WHERE status = 'READY' ORDER BY updated_at"
```

Activation is `POST .../onboarding-runs/<run_id>/activate` with the same header
requirements, and it goes through ADR 0027 approval. A tenant sitting in `READY`
for a week is a sales conversation, not an outage, and the alert stays quiet for
exactly that reason.

## 6. Never

Do not `UPDATE tenant.onboarding_steps SET status = 'COMPLETED'`. A step marked
complete by hand has no `result_snapshot` and no `external_reference`, so the
next run of the workflow will create a second Keycloak organization for the same
tenant rather than reconciling with the first — and readiness evidence that was
typed rather than gathered is exactly what ADR 0008 exists to prevent. A tenant
can then be activated without the thing the check was checking for.

The same applies to `tenant.onboarding_runs.status`. Resume and activate are the
two transitions an operator has — ADR 0008's `cancel` is listed but not built —
and both carry an actor and a reason. SQL carries neither.

## Why one hour

The scheduler polls every five seconds. A step claim is leased for five minutes,
and a step abandoned by a dead worker comes back at the first poll after that. A
retry backs off by at most sixteen seconds before the fifth attempt fails
outright. So the longest a healthy step is due and untouched is one expired lease
plus one poll — a little over five minutes — and handing the step back resets the
age the gauge reads.

An hour is ten of those. It is long enough to survive a deploy, a JVM restart and
a database maintenance window without putting a line in the digest, and the
margin costs nothing, because the message is read at 09:00 either way.
