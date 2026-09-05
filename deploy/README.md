# `deploy/`

Everything needed to run HorecaOS on any Linux host that can run Docker
Compose — the Sarkor colocation box, a staging VM at aHOST or UzCloud, or
(for proof only) a developer's own laptop. This directory is [ADR
0061](../platform/docs/adr/not-started/0061-production-deployment-pilot-on-owned-hardware-portable-by-construction.md)'s
answer to "portable by construction": nothing provider-specific lives
anywhere in `compose.production.yml`; every value that legitimately differs
between environments lives in one file, described below.

The full runbook — bare OS to a running platform, written for a devops
engineer with no access to this conversation — is
[`platform/docs/runbooks/production-setup.md`](../platform/docs/runbooks/production-setup.md).
Read that to actually deploy something. This file is the map.

## What lives here

| Path | What it is |
|---|---|
| `compose.production.yml` | The stack: edge, three frontend bundles, the platform JVM, PostgreSQL, Kafka, Keycloak, OpenBao, backup tooling. Pulls pinned images; contains no `build:` block. The edge image is `horecaos-edge`, CI's eighth published image — stock `caddy:2.10-alpine` recompiled with `caddy-ratelimit` (ADR 0023), since the stock image cannot run this file's `rate_limit` directives. |
| `compose.local-test.override.yml` | Port remapping ONLY, applied by `local-smoke.sh`. Never used on a real host. |
| `env.template` | The per-environment file, exhaustively documented inline. Copy it, fill it, keep the result outside this repository. |
| `env.staging.example` | A filled example for a hypothetical aHOST staging VM — a different provider than production, which ADR 0061 requires. |
| `env.local-test` | What `local-smoke.sh` uses. Test values only; never copy this onto a server. |
| `local-smoke.sh` | Builds every image from source, brings the whole stack up under an isolated compose project, runs the smoke checklist, tears down. The repeatable form of "does this actually work" that needs no server. |
| `infra/caddy/Caddyfile` | TLS (Let's Encrypt in production, Caddy's own internal CA when `HORECAOS_TLS_MODE=internal`), routing, and what is not exposed. |
| `infra/postgres-init/` | Creates the least-privilege application login (`horecaos_app`) on a fresh database volume. Shared verbatim with `platform/compose.yaml`'s own copy in spirit — see the file's own header for why that sharing matters. |
| `infra/openbao/` | OpenBao server config, agent config (the one path the application reads a secret *value* rather than a *reference* for), and the two least-privilege policies. |
| `infra/keycloak/entrypoint.sh` | Reads Keycloak's database password from a file, because Keycloak 26.7 does not honour `KC_DB_PASSWORD_FILE` — see the script's own header. |

## What varies per environment

Exactly one file: `env.template`'s filled copy. Every variable in it is
documented inline with why it exists and what changing it affects. In
outline:

- **Public identity** — the six origins (API, auth, media, and the three
  frontends), their bare hostnames, the ACME contact address.
- **Image tags** — `HORECAOS_REGISTRY`, `HORECAOS_IMAGE_TAG`,
  `HORECAOS_FRONTEND_IMAGE_TAG`, `HORECAOS_POSTGRES_IMAGE_TAG`. Registry
  choice is itself one variable, so "use a different registry" never touches
  the compose file.
- **Resource sizing** — CPU/memory limits for the two heaviest services and
  PostgreSQL's own tuning knobs, because ADR 0061 names "Kafka, Keycloak and
  the JVM share one box" as the open risk most worth re-checking against
  real hardware.
- **Backup target** — an S3-compatible endpoint and bucket outside the box
  the database runs on (ADR 0061: "backups leave the machine but not the
  country"). UzCloud S3 is the named default candidate; any S3-compatible
  endpoint works.
- **Secret references** — never values. `HORECAOS_ENVIRONMENT` is the one
  variable that changes which OpenBao path segment every reference resolves
  against (`horecaos:production:...` vs `horecaos:staging:...`); the actual
  secret *values* are loaded into each environment's own OpenBao by a human,
  per ADR 0028, and never appear in this repository, in chat, or in this
  file.

Everything else — the services, the networks, the healthchecks, the
hardening, the dependency order — is identical in every environment. That
identity is the whole portability claim, and `local-smoke.sh` is what keeps
it honest: it runs the unmodified `compose.production.yml` against a fresh
volume on a machine that is neither Sarkor nor aHOST, and a green run is
evidence the file itself has not quietly grown a provider dependency.

## Relationship to `platform/compose.production.yaml` (read this before deploying)

**Deploy from THIS directory.** This is ADR 0061's registry-pull model —
CI builds and pushes pinned images, a devops engineer pulls them by tag,
following `production-setup.md` — and it is the model the owner actually
decided on 2026-09-01: no repository checkout on the server, no Maven
toolchain there, no SSH access for CI. It is also the only compose file that
serves the current three-frontend architecture (storefront, control-plane,
operations); `platform/compose.production.yaml` predates the second and
third frontends and has no containers for them at all.

An older stack still lives at
[`platform/compose.production.yaml`](../platform/compose.production.yaml),
built for ADR 0023 before this directory existed, which clones the
repository onto the server and builds every image there over SSH — see
[`platform/docs/runbooks/deploy.md`](../platform/docs/runbooks/deploy.md).
Wave 16 documented it as "not superseded, still valid" on the reasoning that
the two files differ only in who builds the image. That reasoning undersold
two things this record now states plainly, because a devops engineer
choosing between the two needs both:

- **The old file cannot deploy today's frontends.** It has no
  `control-plane-web` or `operations-web` service, and its Caddyfile has no
  site block for either origin. Deploying from it means those two
  consoles are simply not served.
- **The old file's model is the one the owner's 2026-09-01 decision moved
  away from.** ADR 0061's "Open inputs" section states the reason in the
  owner's own words: there is no server access for CI or an assistant, so a
  deploy that needs `git pull` and a Maven build to run ON THE HOST is not
  the one that gets used.

It is kept rather than deleted for a real, load-bearing reason this
directory does NOT yet cover: ADR 0023's built alerting, backup, and restore
apparatus — `infra/observability/horecaos-probe.sh`'s default
`COMPOSE_FILE`, `infra/backup/README.md`, and six of the incident runbooks
(`restore.md`, `postgresql-down.md`, `outbox-not-draining.md`,
`container-crash-loop.md`, `onboarding-run-stalled.md`,
`payment-callback-failing.md`) — was built against and verified against the
old file's paths (`/opt/horecaos/horecaos-platform`,
`infra/production/*.sh`) and has NOT been ported to this directory's layout.
Deleting the old tree today would orphan the only operating model this
platform has actually rehearsed a restore against. Porting that apparatus
onto this directory (or formally retiring the old tree once it is ported) is
open, unscheduled work — flagged as a follow-up task in wave 55, not solved
by it.

Until that porting happens, both Caddyfiles carry the SAME ADR 0023 edge
hardening (Payme allowlist, body caps, per-binding/per-IP rate limits),
kept in parity on purpose: either file could plausibly front real traffic
depending on which runbook path an operator is following, and a hardened
edge next to an unhardened one is exactly the latent incident this
consolidation pass exists to close.

## Local smoke test

```bash
deploy/local-smoke.sh
```

Builds all eight images from this checkout, stands the entire production
stack up under compose project `horecaos-prod-smoke` (never the dev stack's
`horecaos-platform` project — see the script's own header), runs a smoke
checklist against it over real TLS (Caddy's internal CA, verified rather
than skipped), and tears itself down. Takes several minutes, most of it the
first (uncached) platform image build.

### What the local proof actually proves

- The platform image builds from source and starts as its non-root user
  with a read-only root filesystem.
- Flyway applies every migration to a **fresh** volume via the pinned
  `platform-migrate` image, and the grant audit passes.
- The Keycloak realm imports cleanly and answers OIDC discovery.
- All three frontend images build and are reachable through the edge.
- `platform-app` reaches ready and answers through Caddy over TLS that this
  script's own `curl` verifies against Caddy's internal CA root — not `-k`.
- A request against the fixture tenant/brand/location ids, with **no**
  fixture data ever loaded (this stack runs `db/migration` only, never the
  `local`-profile-only `db/local-fixtures`), gets a real, captured, honest
  answer — logged verbatim rather than assumed. The captured answer is
  `400 Bad Request` for a nonexistent tenant/brand/location's menu, in
  Spring Boot's own default error shape rather than this platform's ADR
  0031 Problem Details contract (a real gap, not a documentation choice —
  see `platform/docs/runbooks/production-setup.md`'s "Verify" section and
  this task's implementation report), and `200 {"locations":[]}` for the
  tenant-less pickup-locations endpoint.

### What it does not prove

Named here so nobody discovers the gap during a real deploy — the same list
appears in this task's implementation report:

- **TLS against a real CA.** Caddy's internal CA is exercised, not Let's
  Encrypt issuance, renewal, or the ACME HTTP-01 challenge path.
- **DNS**, the colocation router's NAT hairpinning, or anything about the
  Sarkor box specifically.
- **Scoped MinIO service accounts.** The script uses the MinIO root
  credential as the media credential, a corner production-setup.md does not
  cut.
- **Keycloak client-secret rotation** (production-setup.md's own step) — the
  smoke test leaves the realm import's fallback secrets in place.
- **A full browser OAuth round-trip** through the operations or
  control-plane console. The frontend containers are checked for a 200 at
  `/`, not a completed login.
- **Backups, restore, or the monthly rehearsal.** `platform/infra/backup`
  has its own rehearsal script for that.
- **Real load, real hardware sizing, or a reboot.** All three are
  meaningful only on the actual box.
