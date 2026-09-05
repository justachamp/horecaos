# ADR 0061: Production runs on owned hardware first — and stays portable by construction

- Decision status: Accepted
- Implementation status: Partial — the repo-side artifacts are built and locally
  proven (wave 16): `deploy/compose.production.yml` (pinned images, Caddy edge with
  automatic TLS, three frontend containers, resource limits, hardening),
  `deploy/env.template` as the one per-environment artifact with a filled
  different-provider staging example, CI's `publish-images` job (eight images as
  of wave 55 — see below — git-SHA + rolling tags to ghcr, registry overridable by
  one variable — no deploy step by design), and `docs/runbooks/production-setup.md`,
  the bare-OS-to-running procedure the owner's devops executes without assistant or
  CI access. All of it verified end to end by `deploy/local-smoke.sh`: the real
  production compose on a fresh volume under an isolated project boots, migrates,
  imports the realm, serves all three frontends, and keeps docs/metrics unexposed —
  a run that caught and fixed a genuine boot-blocker (`OpenApiConfiguration` under
  disabled api-docs) and an issuer mismatch in the operations app. Realm hardening
  steps (localhost redirect URIs, shipped fallback secrets) are found and written
  into the runbook. **Wave 55 closed most of ADR 0023's reverse-proxy checklist
  item in `deploy/infra/caddy/Caddyfile`**: body caps throughout, and per-binding/
  per-IP rate limits via a new eighth image (`horecaos-edge`, stock Caddy
  recompiled with `caddy-ratelimit`, since the stock image this compose file
  pulled before wave 55 has no `rate_limit` directive at all) — built and
  `caddy validate`-clean locally, not yet proven through a real CI publish or
  deploy. The Payme allowlist piece is wired but not yet functional: it fails
  closed (rejects every caller, Payme included) until the owner supplies Payme's
  real published addresses — see ADR 0023's own checklist line for the full
  account. Wave 55 also found and documented, but did not resolve, the gap this
  line already flagged below (ADR 0023's observability items): the alerting,
  backup, and restore apparatus that record built is wired to
  `platform/compose.production.yaml`'s paths, not this directory's, and porting it
  is unscheduled follow-up work — `deploy/README.md`'s "Relationship" section has
  the detail.
  Not built: the Sarkor box itself, a real registry push, staging on a second
  provider, WAL archiving (only nightly logical dumps are scripted), the rest of
  ADR 0023's observability items, ADR 0056's RLS backstop, the pilot tenant, and
  the SendPulse cutover; `deploy/README.md` carries the explicit
  cannot-verify-without-the-server risk register for devops's first run.
- Date proposed: 2026-09-01
- Date decided: 2026-09-01
- Deciders: platform owner (hosting, budget, and SLO decisions — see Decision),
  Claude (architecture; researched the amended data-localization regime and the
  in-country provider landscape, 2026-09-01)
- Depends on: 0023, 0028, 0052, 0054, 0055, 0056, 0057
- Supersedes / Superseded by: —
- Open inputs: the Sarkor server's specs, OS and public IP; DNS control for
  `horecaos.uz`; the container registry choice; production Click/Payme credentials
  and the production bot token (each needed at its own step, none needed to start).
  Owner-decided 2026-09-01: there is NO direct server access for the assistant or
  CI initially — the owner's devops engineer executes a production setup runbook
  covering everything from bare OS to running platform; CI-driven deploy becomes
  possible only if and when devops installs a deploy key, and the runbook carries
  both the manual upgrade procedure and that optional step.

## Context

ADR 0055's greenfield launch reserved production deployment as its own phase, opening
with decisions only the owner can make. On 2026-09-01 the owner made them, against a
brief that laid out the in-country options (UzCloud, aHOST), the newly-plausible
foreign option, and the legal landscape:

- **Hosting**: the pilot runs on the owner's own private server in **Sarkor
  colocation** (Tashkent, TAS-IX-peered). This was not one of the brief's lettered
  options; it dominates them for the pilot — in-country like Option A, at effectively
  zero marginal cost, on hardware the owner already controls.
- **Portability is a requirement, not a preference**: the platform must be deployable
  to **UzCloud and aHOST as in-country fallbacks** at any time, and the later
  MENA/Europe scaling phase will host on **AWS, Azure, or GCP**. Whatever the pilot
  does on the Sarkor box must therefore work identically on any Linux VM anywhere.
- **Budget**: approximately zero now; the scaling phase plans around **$1,000/month**.
- **SLOs**: **99% monthly availability** is enough now (≈ 7.3 hours/month of budget);
  the later target is **99.99%** (≈ 4.4 minutes/month) — which is not a tuning knob
  but a different architecture (HA data tier, multiple nodes, at least two sites),
  deliberately assigned to the $1,000/month phase.

The legal backdrop moved just in time to matter: Uzbekistan's personal-data law
(ZRU-547) required in-country processing of citizens' personal data until the
**amendments of 2026-03-26** narrowed localization — cross-border storage is now
permitted when information-security requirements, international-standards compliance,
and Uzbek-authority oversight hold simultaneously, while biometric/genetic and
telecom-subscriber data stay in-country. HorecaOS stores neither of the still-locked
categories, but the pilot does not need the new latitude: hosting in Tashkent keeps
customer traffic and Click/Payme callbacks on the national exchange and keeps the
compliance posture trivial. The latitude matters later, for the MENA/EU phase — and
that phase adds GDPR and per-region residency questions of its own, which get their
own record when it opens.

## Decision

Production is **compose-on-VM, portable by construction**, in three phases:

- **The deployment unit is the dev stack, hardened.** One Linux host runs the same
  shape `make up` runs today: the platform JVM, PostgreSQL 18, Kafka, Keycloak, and
  an edge proxy (TLS, static Angular bundles, reverse proxy to :8080) — as pinned
  container images under docker compose. No Kubernetes for the pilot: the modulith
  is one JVM, and the operational surface a pilot can afford is the one that mirrors
  dev exactly, so the proving run doubles as the acceptance gate everywhere.
- **Portability is enforced, not assumed**: no provider primitives (no managed
  queues, no provider IAM, no cloud-specific storage APIs) may enter the deployment;
  everything provider-specific lives in one per-environment file (hosts, DNS names,
  secret references, resource sizing). The fallback claim is only real if rehearsed:
  the staging environment is deliberately provisioned on a DIFFERENT target than the
  Sarkor box (a small aHOST or UzCloud VM), so every release proves the
  deploy-anywhere property before it reaches production.
- **The pipeline is boring**: CI (already green on every push) builds and pins
  images, pushes them to the registry, and deploys over SSH — compose pull + up,
  health-gated by the actuator probes, with rollback being the previous image tags
  redeployed. Flyway keeps its append-only discipline; a release that needs a
  migration rolls forward, never back.
- **Secrets stay references** (ADR 0028): production runs its own OpenBao (or an
  equivalent file-based sealed store if OpenBao proves heavy for one box) on the
  host, holding the real values the platform's secret references resolve against.
  Values enter it once, by the owner, never through chat or git.
- **Backups leave the machine but not the country**: nightly encrypted PostgreSQL
  base backups plus WAL archiving to storage OUTSIDE the Sarkor box (an in-country
  object store or second VM — UzCloud S3 is the default candidate), retention 30
  days, restore rehearsed on staging monthly. Cross-border backup storage is
  explicitly deferred to counsel review under the amended law.
- **The SLO is enforced by what we measure**: ADR 0023's on-box probe and alert
  thresholds are the availability instrument; 99% is the declared target, the error
  budget is tracked from the probe's own uptime record, and nothing promises more
  than the single-box architecture can deliver. The 99.99% ambition is recorded as
  the scaling phase's requirement and is out of scope here.
- **Tenant isolation gates go-live**: ADR 0056's RLS backstop must be Built before a
  second real tenant onboards in production — the greenfield explicitly reserved it
  for this moment, and a pilot with one tenant is the last cheap time to land it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| UzCloud / aHOST as primary (the brief's A/B) | The Sarkor box costs nothing and is owner-controlled; both stay as rehearsed fallback targets, which preserves their whole value | The box's capacity, connectivity, or ownership changes |
| Hetzner or another EU host now | Newly legal in principle (2026-03-26 amendments) but adds a counsel dependency and moves payment callbacks cross-border for zero pilot benefit | The MENA/EU phase, with counsel |
| Kubernetes from day one | A one-JVM modulith on one box gains nothing from an orchestrator except operational surface; portability is achieved by compose + no provider primitives, not by k8s | The $1,000/month phase, if node count or team size demands it |
| A managed PaaS (Fly, Render, Railway…) | Provider primitives everywhere — the exact lock-in the portability requirement forbids; and data residency is out of our hands | Never for the UZ pilot |
| 99.9%+ SLO now | On one box it would be a number, not a promise; honesty is the platform's own discipline | The scaling phase (99.99% target, HA architecture) |

## Consequences

### Positive

- Zero hosting cost through the pilot; every som spent later buys the HA the 99.99%
  target actually needs rather than renting comfort early.
- The deploy-anywhere property is proven on every release by construction (staging on
  a different provider), so the UzCloud/aHOST fallback and the eventual hyperscaler
  move are rehearsed paths, not migration projects.
- Dev, staging, and production share one shape; the proving run is the acceptance
  gate in all three.

### Negative / accepted risks

- One box is one failure domain: power, disk, or uplink at Sarkor takes production
  down until restore-on-fallback completes. Accepted under the 99% target; the
  restore path (backups + a fallback VM) IS the DR plan and must be rehearsed.
- Owner-owned hardware means owner-performed hands-on-metal when hardware fails.
- Kafka and Keycloak on the same box as the JVM contend for it; sizing must be
  verified against the server's actual specs (open input).

## Rollout

1. Production architecture executed on the Sarkor box (compose hardening, edge TLS,
   OpenBao, backups) once access and specs arrive.
2. Staging on a small aHOST/UzCloud VM + the SSH deploy pipeline; proving run green
   on staging becomes the release gate.
3. ADR 0056 RLS backstop and ADR 0023's nine observability items land behind the
   staging gate.
4. DNS/TLS for `horecaos.uz`, production Keycloak realm hardening, production
   proving run, first pilot tenant.
5. SendPulse cutover per its runbook — it was waiting for exactly this public
   HTTPS endpoint.

Rollback at every step is the previous image tags; the pilot's DR is documented
restore-to-fallback-VM, rehearsed before the first real tenant.

## Implementation checklist

- [ ] Production compose profile: pinned images, edge proxy with TLS, resource limits, restart policies
- [ ] Per-environment config file format (hosts, DNS, secret references, sizing) — the only provider-varying artifact
- [ ] Registry + CI image publish; SSH deploy with actuator health gate and previous-tag rollback
- [ ] Production OpenBao (or sealed-store equivalent) holding real secret values; owner loads values
- [ ] Nightly encrypted base backups + WAL archiving off-box, in-country; monthly restore rehearsal on staging
- [ ] Staging environment on a different provider than production; proving run wired as the release gate
- [ ] ADR 0023 observability items against the probe; 99% error-budget tracking
- [ ] ADR 0056 RLS backstop Built before the second production tenant
- [ ] DNS + TLS for horecaos.uz; production Keycloak realm hardening
- [ ] Production proving run; pilot tenant onboarded; SendPulse cutover runbook executed

## Exit criteria

A release built by CI deploys to staging on a non-Sarkor provider and passes the
proving run there; the same release deploys to the Sarkor box and serves
`https://horecaos.uz` with valid TLS; a restore-to-fallback rehearsal has brought a
backup up on a different provider inside the RTO; the probe records availability
against the 99% budget; and the pilot tenant takes a real fiscalized order through
the production front door.
