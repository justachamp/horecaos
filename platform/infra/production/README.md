# Production deployment (superseded for NEW deploys — read this before using it)

**For a new production deploy, use `deploy/` at the repository root instead**,
following `docs/runbooks/production-setup.md`. That is ADR 0061's
registry-pull model — the one the owner actually decided on 2026-09-01 — and
it is the only one of the two that serves the current three-frontend
architecture; this tree's compose file has no `control-plane-web` or
`operations-web` service at all, because it predates both.

This tree is not deleted, and is still genuinely load-bearing: ADR 0023's
built alerting, backup, and restore apparatus —
`platform/infra/observability/horecaos-probe.sh`'s default `COMPOSE_FILE`,
`platform/infra/backup/README.md`, and six incident runbooks (`restore.md`,
`postgresql-down.md`, `outbox-not-draining.md`, `container-crash-loop.md`,
`onboarding-run-stalled.md`, `payment-callback-failing.md`) — was built and
verified against exactly this tree's paths
(`/opt/horecaos/horecaos-platform`, `infra/production/*.sh`) and has not
been ported to `deploy/`'s layout. Porting it (or formally retiring this
tree once that porting is done) is open, unscheduled work, not something
wave 55's edge-hardening pass resolves.

Two of this Dockerfiles below are shared, not duplicated: `deploy/`'s CI
publish job builds `horecaos-platform-migrate` and `horecaos-platform-ops`
from `migrate/Dockerfile` and `ops/Dockerfile` in this directory, because
those images have nothing tree-specific in them. `compose.production.yaml`,
`caddy/Caddyfile`, `deploy.sh`, `bootstrap.sh`, `run-backup.sh`, and
`heartbeat.sh` are this tree's own orchestration and are what a new deploy
should not use.

Everything needed to run HorecaOS on the colocated server in Uzbekistan, and
nothing that runs anywhere else.

| Path | What it is |
|---|---|
| `../../Dockerfile` | The application image |
| `../../compose.production.yaml` | The stack (superseded for new deploys — see above) |
| `../app/entrypoint.sh` | How the application receives its secrets |
| `../openbao/` | OpenBao server config, agent config, and the two policies |
| `bootstrap.sh` | Once per host. Initialises OpenBao and generates the credentials |
| `deploy.sh` | Every release (superseded for new deploys — see above) |
| `run-backup.sh` | The nightly backup, as cron runs it |
| `heartbeat.sh` | The whole of the alerting |
| `caddy/Caddyfile` | TLS, routing, rate limits, body caps, and the Payme allowlist (ADR 0023) — kept in parity with `deploy/infra/caddy/Caddyfile`, see that file's own note |
| `caddy/Dockerfile` | Stock `caddy:2.10-alpine` recompiled with `caddy-ratelimit`, since the stock image cannot run this Caddyfile's `rate_limit` directives. Shared: `deploy/`'s CI job publishes this as `horecaos-edge` too |
| `migrate/Dockerfile` | The one-shot Flyway job. Shared with `deploy/`'s CI publish job |
| `ops/` | The operator's shell: pg_dump, psql, mc, openssl. `ops/Dockerfile` is shared with `deploy/`'s CI publish job |
| `postgres-init/` | Creates the least-privilege application login |
| `audit-grants.sql` | Fails the deploy if a table exists the application cannot read |

Procedures are in [`docs/runbooks/`](../../docs/runbooks/). This file is the map;
the runbooks are the instructions.

## Three networks, not two

`public` has a route out; `core` has none and holds PostgreSQL, Kafka, MinIO and
OpenBao. A third, `media`, holds exactly the edge and MinIO.

It exists because presigned media URLs are handed to browsers and therefore have
to be signed for the public HTTPS origin, which means the edge has to reach the
object store. Neither of the two obvious ways to arrange that is acceptable:
putting MinIO on `public` gives the store that holds the backups a route to the
internet, and putting the edge on `core` gives the one internet-facing process a
path to PostgreSQL and OpenBao. A two-member link costs nothing and gives up
neither property.

`HORECAOS_MEDIA_ORIGIN` and `HORECAOS_MEDIA_HOSTNAME` are required for the same reason
the API and auth ones are: a URL signed for `http://minio:9000` is unreachable
from a phone and unencrypted if it were.

## How a secret reaches a running container

This is the part worth reading in full, because it is the part that is easy to
quietly break.

**Nothing is in the repository, nothing is in an image, and nothing is on the
disk.** ADR 0028 says OpenBao; here is exactly what that means in practice.

```text
                    unseal shares            (three of five; not on this machine)
                          |
                          v
    +-----------------------------------------------+
    |  openbao   raft volume, sealed at rest,        |
    |            unsealed by a human after a reboot  |
    +-----------------------------------------------+
        |                         |
        | (1) deploy time         | (2) continuously
        v                         v
  operator's token          openbao-agent
  (typed once, in RAM)      AppRole login, renews its own token
        |                         |
        v                         v
  /run/horecaos/secrets        /run/bao   (tmpfs volume, uid 100 : gid 10001)
  tmpfs on the host           token       renewed OpenBao token
  mode 0700, root             horecaos.env   HORECAOS_DB_PASSWORD
        |                         |
        | bind-mounted as         | read by entrypoint.sh, exported to the JVM
        | compose `secrets:`      |
        v                         v
  platform-db               platform-app
  keycloak-db
  minio
```

Four things follow from that picture, and each of them is the reason for a
design decision elsewhere:

**1. Three containers get a value; the application gets a token.**
PostgreSQL, Keycloak and MinIO have no OpenBao client, so they read a password
from a file at startup. PostgreSQL and MinIO support that directly through
`POSTGRES_PASSWORD_FILE` and `MINIO_ROOT_PASSWORD_FILE`. Keycloak 26.7 does not,
despite the convention being near-universal — `KC_DB_PASSWORD_FILE` is ignored and
the server reports only that no password was provided — so it gets a four-line
entrypoint wrapper instead (`keycloak/entrypoint.sh`). Those files live on a host
tmpfs that `deploy.sh` mounts and fills, and they are gone on reboot.

The application is different. It holds a *renewed* token, not a value, so that
every other secret it needs — the ADR 0029 key-encryption key, the Keycloak
client secrets, the media credentials — is fetched at call time by reference and
can be rotated without a restart. That is what `SecretResolver` and
`SecretReference` are for, and giving the application a bag of environment
variables instead would quietly discard the whole design.

**2. Exactly one value is delivered to the application as a value.**
`HORECAOS_DB_PASSWORD`, because Spring needs a datasource before any bean exists.
`infra/openbao/agent.hcl` has one template in it and should keep having one. A
second template appearing there is a signal that something took a value where a
reference belonged.

**3. Nothing is visible in `docker inspect`.**
The compose file declares no credential, so `docker compose config` and
`docker inspect` show nothing. Values reach the JVM's process environment inside
the container, which is readable from `/proc/1/environ` by that process itself.
That is the boundary this design accepts and does not pretend to have crossed.

**4. A reboot needs a human, once.**
OpenBao comes back sealed. Nothing that needs a credential can start until three
unseal shares are entered, and those shares are deliberately not on the machine —
keeping them there would make the encryption decorative. Everything else recovers
by itself: the containers restart, the agent retries, and the application waits
two minutes, exits, and is restarted by its policy, so the moment OpenBao is
unsealed the platform comes up without any further command.

That single manual step is the price of the property that a stolen or
decommissioned disk carries no usable credential. It is stated in
`docs/runbooks/deploy.md` section 5 as the reboot procedure, and it is one
command run three times.

## What this topology cannot do

Stated so that nobody discovers it during an incident:

- **No rolling deploy.** `deploy.sh` stops the application container and starts a
  new one. The gap is the JVM's startup, and Caddy holds requests for ten seconds
  across it. Anything longer is a 502.
- **No self-healing beyond restart policies and `autoheal`.** A container that
  exits comes back; a container that goes unhealthy is restarted. A host that
  dies stays dead until somebody drives to it or rebuilds elsewhere from backup.
- **No horizontal scaling.** Capacity is edited into `deploy.resources.limits`
  and redeployed. There is one of everything.
- **No high availability anywhere.** One PostgreSQL, one Kafka broker with
  replication factor 1, one MinIO, one OpenBao node.

The revisit trigger for all of this is in
[`docs/runbooks/README.md`](../../docs/runbooks/README.md) and is deliberately a
set of observable conditions rather than a feeling about growth.

## Resource budget

Sized for a 16 GB / 8 vCPU host. The limits in `compose.production.yaml` add up
to roughly 12.5 GB, leaving headroom for the kernel, the page cache PostgreSQL
depends on, and a deploy that briefly runs an old and a new container together.

| Service | Memory | Why |
|---|---|---|
| `platform-db` | 4 GB | The availability anchor; everything else is sized around it |
| `platform-app` | 2 GB | JVM at 70% of the limit, so roughly 1.4 GB of heap |
| `kafka` | 2 GB | 1 GB heap plus page cache for the log segments |
| `keycloak` | 1 GB | JVM, mostly idle after startup |
| `minio` | 1 GB | Streams; does not buffer whole objects |
| `keycloak-db` | 512 MB | Small and almost entirely cached |
| `edge` | 256 MB | Caddy is small |
| `openbao` | 256 MB | Small and mostly idle |
| `openbao-agent` | 128 MB | One goroutine and a renewal timer |
| `autoheal` | 64 MB | A shell loop |

If a limit is raised, raise it against a measurement rather than a hunch, and
check the total still leaves the kernel room. A host that starts swapping under
PostgreSQL is slower than one with a smaller `shared_buffers`.

## What has been verified, and what has not

Verified on 2026-08-23 by building the image and bringing this entire compose
file up on a workstation, with OpenBao in production mode:

- The image builds from source, runs as uid 10001 with a read-only root
  filesystem, and starts Spring Boot 4.1 on Java 25.
- OpenBao initialises sealed, and the `bao status` health check reports unhealthy
  until three unseal shares are entered — so nothing that needs a credential can
  start against a sealed secrets manager.
- The agent authenticates with the AppRole, renders the datasource password into
  the shared tmpfs, and the value matches what is in OpenBao byte for byte.
- Dependency ordering holds: the agent waits for OpenBao to be healthy, and the
  application waits for the database, Kafka, Keycloak and the agent.
- The Flyway CLI job applies all 34 migrations as `horecaos_migrator`, and the
  application then connects as `horecaos_app` and reaches ready.
- Only 80 and 443 are published. `/actuator/health/readiness` answers through the
  edge; every other actuator path is 404; an unauthenticated API call is 401;
  Keycloak's admin console is 404.
- The `core` network has no default route — proven by the absence of one in the
  database container and the presence of one in the application container, which
  needs egress for provider calls.
- Log rotation, memory limits, CPU limits, `cap_drop: ALL` and the read-only root
  filesystem are all present on the running containers, not just in this file.
- `autoheal` detects an unhealthy container and restarts it.
- The full backup path runs: dump, integrity check, encrypt, upload, read back
  and compare, expire. The restore path then reads that object back, verifies its
  checksum, decrypts it, restores into a separate database, and verifies the
  result.

Not verified, and it cannot be until there is a server:

- **TLS.** Caddy was exercised on plain HTTP against `.local` names. Certificate
  issuance, renewal, and the HSTS header are unproven.
- **The public DNS path**, and therefore the network-alias trick that keeps
  issuer discovery from leaving the host — including the media origin, which
  relies on the same alias and has never served a signed URL to a real browser.
- **The Caddy site block for the media origin.** The compose side is in place and
  `docker compose config` resolves; whether Caddy issues a certificate for that
  name and proxies MinIO correctly is unproven until there is a host.
- **Restore time at production data volume.** The database restored here was
  effectively empty and took under a minute, which is not an estimate of
  anything.
- **The off-site backup destination.** No longer deferred: the nightly backup now
  writes a second copy and refuses to run when the destination is unset. What is
  unverified is the destination itself -- that a real remote bucket accepts the
  upload, has versioning and object-lock on, and holds credentials separate from
  the passphrase.
- **The reboot procedure end to end**, including whether the operator can find
  three unseal shares at 3am. That one is worth rehearsing on purpose.
