# Production setup: bare OS to a running platform

**Last executed:** never — this is a draft. Rehearse it on the staging VM
first (section 10); a runbook that has only ever been read is not proven.

**Written for a devops engineer with no access to this conversation, no
Claude session, and — per [ADR
0061](../adr/partial/0061-production-deployment-pilot-on-owned-hardware-portable-by-construction.md)'s
Open Inputs — no standing SSH relationship with anyone who does.** CI builds
and publishes pinned images; it never deploys them. Every command below is
something you run yourself, on the box, as the person who holds it. Where a
value only the platform owner can supply is needed (a domain, a payment
credential, a real secret value), it says so and names who supplies it.

**Rollback at every numbered section below is "undo just that section."**
There is no single rollback for the whole runbook — see each section's own
"Rollback" note. The one truly irreversible step is in section 4
(revoking the Keycloak bootstrap admin); everything before it is safe to
re-run or abandon.

Related documents, so you know which one you are in:

- [`deploy/README.md`](../../../deploy/README.md) — what `deploy/` contains and why, including this runbook's relationship to the older [`platform/docs/runbooks/deploy.md`](deploy.md), which covers day-to-day releases, rollback, and Flyway incidents once the platform is already running. Read **this** file first; switch to `deploy.md`'s sections 2–5 once section 8 (Upgrade) below is where you actually are.
- [`restore.md`](restore.md) — a full database loss, not covered again here.
- [`sendpulse-cutover.md`](sendpulse-cutover.md) — moving a bot's live traffic onto this platform, which section 7's Telegram check only prepares for.

---

## 1. Prerequisites

### Assumed OS

**Ubuntu 24.04 LTS.** Package manager commands below are `apt`; on a
different distribution, swap those for the equivalent (Docker's own install
docs cover Debian, RHEL/Alma/Rocky, and SUSE) — nothing else in this runbook
is Ubuntu-specific.

### Sizing, against the ADR's own open risk

ADR 0061 names this explicitly: **Kafka, Keycloak, PostgreSQL, and the JVM
all share one box.** `deploy/env.template`'s "Sizing" section defaults assume
a 16 GB / 8 vCPU host and add up to roughly 12.5 GB of hard limits, leaving
headroom for the kernel, PostgreSQL's page cache, and a deploy that briefly
runs an old and a new application container together. **Verify the Sarkor
box's actual specs before first boot** and adjust `deploy/env.template`'s
sizing variables if they differ — do not deploy against a guess. If the box
has less than 16 GB, lower `HORECAOS_DB_SHARED_BUFFERS` and
`HORECAOS_APP_MEMORY_LIMIT` first; PostgreSQL degrades more gracefully than
a JVM that gets OOM-killed mid-transaction.

### Install Docker Engine and the Compose plugin

```bash
curl -fsSL https://get.docker.com | sh
apt-get install -y docker-compose-plugin git
docker compose version   # v2.20 or later
```

**Check:** `docker run --rm hello-world` prints the welcome message.

### Swap off, permanently

OpenBao 2.x removed `mlock` support entirely and refuses to start if the
option is even present in its config. The protection that used to provide —
keeping an unsealed master key out of swap — is now this host's own
responsibility, and it is the one OS-level setting this design depends on:

```bash
swapoff -a
sed -i '/ swap /d' /etc/fstab
```

**Check:** `free -h` shows `0` in the Swap row, and it stays `0` after a
reboot.

### Firewall: only 80, 443, and SSH-for-devops in

```bash
apt-get install -y ufw
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp    comment 'devops SSH — restrict to your own IP if it is static'
ufw allow 80/tcp    comment 'Caddy: ACME HTTP-01 challenge and HTTP->HTTPS redirect'
ufw allow 443/tcp   comment 'Caddy: the platform'
ufw enable
ufw status verbose
```

**Check:** `ufw status verbose` lists exactly three allowed rules. Nothing
else — not 5432, not 9092, not 8200 — is ever published; `deploy/compose.production.yml`'s
own `core` network has no route out for exactly this reason, and a firewall
rule cannot undo a design that already keeps those ports off the host's
public interface, but it is the backstop for a `ports:` line added by
mistake in some future edit.

**Rollback:** `ufw disable`. Only for diagnosing a firewall-caused problem —
put it back before walking away.

### DNS — the owner holds this, named as such

The domain's A records point at this box's public IP. **The platform owner
controls `horecaos.uz`'s DNS**, not devops; request these records before
starting section 3, because Caddy requests a certificate the first time each
site starts and the ACME HTTP-01 challenge fails if the name does not
resolve here yet:

| Name | Type | Value |
|---|---|---|
| `api.horecaos.uz` | A | this box's public IP |
| `auth.horecaos.uz` | A | this box's public IP |
| `media.horecaos.uz` | A | this box's public IP |
| `horecaos.uz` | A | this box's public IP |
| `operations.horecaos.uz` | A | this box's public IP |
| `admin.horecaos.uz` | A | this box's public IP |

**Check:** `dig +short api.horecaos.uz` (from a machine that is not this
box) returns the box's IP, for all six names.

---

## 2. Fetch

Two paths. Either works; the first is simpler and is what this runbook
assumes from here on.

### Path A — clone the repository (simplest)

```bash
mkdir -p /opt/horecaos
git clone https://github.com/justachamp/horecaos.git /opt/horecaos/horecaos
cd /opt/horecaos/horecaos
```

You now have `deploy/`, the Keycloak realm file, and the backup scripts in
one checkout. This devops engineer never runs `mvn`, never runs a frontend
build, and never runs anything under `platform/` except copying a couple of
its scripts — the checkout exists so that `deploy/` is never orphaned from
the realm file and the backup tooling it references by relative path.

### Path B — a release tarball of just `deploy/` (lighter)

If cloning the whole monorepo is undesirable (a smaller footprint, or a
policy against a full checkout on a production box), fetch only this
directory as a tarball from a tagged release:

```bash
mkdir -p /opt/horecaos/horecaos
curl -fsSL "https://github.com/justachamp/horecaos/archive/refs/tags/<release-tag>.tar.gz" \
  | tar -xz -C /opt/horecaos/horecaos --strip-components=1 \
    "horecaos-<release-tag>/deploy" \
    "horecaos-<release-tag>/platform/infra/keycloak/realm" \
    "horecaos-<release-tag>/platform/infra/backup" \
    "horecaos-<release-tag>/platform/infra/production/audit-grants.sql" \
    "horecaos-<release-tag>/platform/infra/production/ops/bao-get.sh"
cd /opt/horecaos/horecaos
```

**Check, either path:** `deploy/compose.production.yml` and
`platform/infra/keycloak/realm/horecaos-realm.json` both exist under
`/opt/horecaos/horecaos`.

### Authenticate Docker to ghcr

Only needed if the repository (and therefore its published images) is
**private**. If it is public, skip straight to the Check below — an
anonymous `docker pull` already works.

```bash
# A GitHub personal access token with `read:packages`, created by whoever
# administers the GitHub org — devops does not need write access to anything.
echo "<token>" | docker login ghcr.io -u <your-github-username> --password-stdin
```

**Check:**

```bash
docker pull ghcr.io/justachamp/horecaos/horecaos-platform:main
```

pulls without a permission error. If it 404s instead, no image has been
published yet — confirm CI's `publish-images` job has run at least once on
`main` (`.github/workflows/ci.yml`).

**Rollback:** `docker logout ghcr.io`. Nothing else in this step touched
anything.

---

## 3. Configure

### Fill the environment file

```bash
mkdir -p /etc/horecaos
cp deploy/env.template /etc/horecaos/production.env
chmod 0600 /etc/horecaos/production.env
$EDITOR /etc/horecaos/production.env
```

Fill in every blank `deploy/env.template`'s own comments name — the six
public origins, the ACME email, `HORECAOS_KAFKA_CLUSTER_ID` (generate it
now, see below), `HORECAOS_MINIO_ROOT_USER`, the backup off-site endpoint
and bucket, and the three `HORECAOS_STOREFRONT_*` ids once the pilot tenant
exists (section 5 covers that; the storefront container can wait until
then). Leave `HORECAOS_TLS_MODE` **empty** — that is what makes Caddy request
a real certificate rather than its own internal one.

```bash
docker run --rm apache/kafka:4.3.1 /opt/kafka/bin/kafka-storage.sh random-uuid
```

Paste the output into `HORECAOS_KAFKA_CLUSTER_ID`. Generate this exactly
once, ever, per host — the broker refuses to start against a data directory
formatted with a different cluster id, and the failure does not say why.

**Check:**

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env config >/dev/null
```

exits zero, naming any variable you missed. It will not catch
`HORECAOS_STOREFRONT_TENANT_ID` et al. being placeholder-empty if you left
them so on purpose to defer starting `storefront-web` — that is fine; start
every other service first and add `storefront-web` in section 5.

### Generate the platform's internal credentials

Nothing here is typed by a human and nothing here is ever written to this
host's disk outside a RAM-backed secret directory a deploy creates and a
reboot destroys — see `platform/infra/production/README.md`'s secret
delivery diagram, which applies unchanged to `deploy/compose.production.yml`.
Two roles, generated once, matching how `platform/infra/production/postgres-init/10-application-role.sh`
(copied verbatim into `deploy/infra/postgres-init/`) provisions them on a
fresh volume: **`horecaos_migrator`** owns every object and is the only role
that can change the schema (it is what `POSTGRES_USER` is set to, so
PostgreSQL creates it automatically on first boot); **`horecaos_app`** is
created by that init script, owns nothing, and holds only what a migration
GRANTs it by name.

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d openbao
```

Wait for it to report healthy (`docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps openbao`), then initialise and unseal it:

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec openbao bao operator init -key-shares=5 -key-threshold=3
```

**Stop. Before you press Enter on the command above, have somewhere to write
five key shares and a root token that is not this machine.** The intended
split: two shares in the operator's password manager, two in a sealed
envelope held off-site, one with the business owner. Losing three of five
means the OpenBao volume — and every credential behind it — is unrecoverable.

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec openbao bao operator unseal      # run three times, three different shares
```

**Check:** the third run reports `Sealed: false`.

Enable the secrets engine, load the two least-privilege policies this
repository ships, and create the AppRole the running application
authenticates with:

```bash
export BAO_TOKEN=<the root token from init, typed once, this session only>
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao secrets enable -path=horecaos -version=2 kv

for policy in horecaos-platform horecaos-deploy; do
  docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
    cp "deploy/infra/openbao/policies/${policy}.hcl" "openbao:/tmp/${policy}.hcl"
  docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
    exec -e BAO_TOKEN openbao bao policy write "${policy}" "/tmp/${policy}.hcl"
done

docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao auth enable approle
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao write auth/approle/role/horecaos-platform \
    token_policies=horecaos-platform token_ttl=1h token_period=1h \
    secret_id_ttl=720h secret_id_num_uses=0 bind_secret_id=true
```

Generate the four startup credentials inside OpenBao's own container, so no
human ever sees them and none can be reused, weak, or leaked in a shell
history:

```bash
for secret_path in \
    database/platform/migrator-password \
    database/platform/app-password \
    database/keycloak/password \
    object_storage/platform/root-password
do
  docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
    exec -e BAO_TOKEN openbao sh -c "
      value=\$(head -c 32 /dev/urandom | base64 | tr -d '=+/' | head -c 40)
      bao kv put horecaos/production/${secret_path} value=\"\${value}\""
  echo "    horecaos/production/${secret_path}"
done
```

**Check:**

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao kv get horecaos/production/database/platform/app-password
```

returns a value.

### Initialise OpenBao and load real secret VALUES — owner/devops, never chat or git

Three more values have no generator — they either come from outside OpenBao
or, per [ADR 0028](../adr/partial/0028-secrets-management-and-credential-lifecycle.md),
must be supplied by a human because a script generating them would still be
a script that knew them:

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao sh -c '
    bao kv put horecaos/production/data_encryption/platform/kek \
      value="'"$(head -c 32 /dev/urandom | base64)"'"
    bao kv put horecaos/production/data_encryption/platform/backup-passphrase \
      value="'"$(head -c 32 /dev/urandom | base64)"'"
    bao kv put horecaos/production/data_encryption/platform/handover-pepper \
      value="'"$(head -c 32 /dev/urandom | base64)"'"'
```

**Print and seal both the KEK and the backup passphrase, in an envelope kept
somewhere other than this building.** Losing the KEK makes every
ADR 0029-encrypted personal-data column permanently unreadable. Losing the
backup passphrase makes every backup permanently unreadable. Neither has a
second copy anywhere else.

**This is why ADR 0028's reference model exists, stated for whoever is
reading this at 3am and wondering why a value cannot just be pasted into
this file:** the database, this repository, and every chat transcript that
ever touches this platform store a *reference string*
(`horecaos:production:database:platform:app-password`), never a value. A
value lives in exactly one place — OpenBao's raft volume — and reaches a
running container only at start time, through the agent, into RAM, gone on
reboot. Nothing above this line, and nothing below it, is safe to paste into
a chat with an AI assistant, a support ticket, or a commit message. If you
are about to do that, stop and use `bao kv put` instead.

The remaining secrets need Keycloak and MinIO to exist first — sections 4
and 5 create them and come back to this list:

```text
horecaos/production/identity_admin/keycloak/provisioning-secret
horecaos/production/identity_admin/keycloak/reader-secret
horecaos/production/object_storage/platform/media-access-key
horecaos/production/object_storage/platform/media-secret-key
horecaos/production/object_storage/platform/backup-access-key
horecaos/production/object_storage/platform/backup-secret-key
horecaos/production/object_storage/platform/backup-offsite-access-key
horecaos/production/object_storage/platform/backup-offsite-secret-key
```

**Rollback for this whole section:** none past the unseal — an initialised
OpenBao cannot be un-initialised without destroying its volume. If something
here goes wrong before any real secret is loaded, `docker compose down -v`
this project and start section 3 again on a clean volume.

---

## 4. First boot

### Pull the pinned images

```bash
export HORECAOS_IMAGE_TAG=<the short git sha CI published, or "main" for the rolling tag>
export HORECAOS_FRONTEND_IMAGE_TAG="${HORECAOS_IMAGE_TAG}"
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env pull
```

**Check:** no error. `docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env images`
lists all seven pinned images with the tag you set.

### Issue the AppRole credential and start dependencies

```bash
role_id="$(docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao read -field=role_id auth/approle/role/horecaos-platform/role-id)"
secret_id="$(docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -e BAO_TOKEN openbao bao write -field=secret_id -f auth/approle/role/horecaos-platform/secret-id)"

mkdir -p /run/horecaos/secrets
mount -t tmpfs -o size=1m,mode=0700,noexec,nosuid,nodev tmpfs /run/horecaos/secrets
export HORECAOS_SECRET_DIR=/run/horecaos/secrets

for name_path in \
    platform-db-migrator-password:database/platform/migrator-password \
    platform-db-app-password:database/platform/app-password \
    keycloak-db-password:database/keycloak/password \
    minio-root-password:object_storage/platform/root-password
do
  name="${name_path%%:*}"; path="${name_path##*:}"
  value="$(docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
    exec -e BAO_TOKEN openbao bao kv get -field=value "horecaos/production/${path}")"
  ( umask 133; printf '%s' "${value}" > "/run/horecaos/secrets/${name}" )
  chmod 0444 "/run/horecaos/secrets/${name}"
done
( umask 133; printf '%s' "${role_id}"   > /run/horecaos/secrets/openbao-role-id )
( umask 133; printf '%s' "${secret_id}" > /run/horecaos/secrets/openbao-secret-id )
chmod 0444 /run/horecaos/secrets/openbao-role-id /run/horecaos/secrets/openbao-secret-id
unset role_id secret_id BAO_TOKEN

docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d platform-db keycloak-db kafka minio openbao-agent
```

**Check:** `docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps`
shows all five `(healthy)`, including `openbao-agent` — its healthcheck
requires both the renewed token and the rendered `horecaos.env` to exist,
which is proof the AppRole credential above actually worked.

### Migrations run via the pinned Flyway image

```bash
export FLYWAY_PASSWORD="$(cat /run/horecaos/secrets/platform-db-migrator-password)"
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm platform-migrate migrate
unset FLYWAY_PASSWORD
```

**Check:** the last line reads `Successfully applied <N> migrations`. Then
confirm nothing was left ungranted:

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -T platform-db psql -U horecaos_migrator -d horecaos -v ON_ERROR_STOP=1 -q \
  < platform/infra/production/audit-grants.sql
```

No output and exit zero means every table a migration created also has the
application's GRANT. If this fails, it names the table — the fix is a new
migration adding the GRANT, never a manual statement here (a future restore
with `--no-privileges` would silently drop a hand-run GRANT).

**Rollback:** none needed on a fresh database — nothing was serving traffic
yet. If this is instead an upgrade on an already-running host, see section 8.

### Import the Keycloak realm

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm --no-TTY \
  --volume "$(pwd)/platform/infra/keycloak/realm:/opt/keycloak/data/import:ro" \
  keycloak import --dir /opt/keycloak/data/import
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env up -d keycloak
```

**Check:** the import ends with `Keycloak stopped`, no `ERROR` line, then
once the `keycloak` service reports healthy:

```bash
curl -fsS https://auth.horecaos.uz/realms/horecaos/.well-known/openid-configuration | jq -r .issuer
```

reads exactly `https://auth.horecaos.uz/realms/horecaos`. `--import-realm` is
deliberately absent from `deploy/compose.production.yml`: leaving it on
would let this repository's realm file silently overwrite production
identity configuration on every restart.

### Production hardening — strip and rotate what the realm file leaves dev-shaped

The checked-in realm carries three things that must not survive into
production, found by reading `platform/infra/keycloak/realm/horecaos-realm.json`
directly rather than assumed:

**1. The retired public redirect clients must not exist.** ADR 0062 (wave 18)
replaced the browser-redirect sign-in with the backend-validated direct grant on
`horecaos-staff-login`; `horecaos-operations` and `horecaos-control-plane` are
gone from the realm file, and a realm imported fresh from it never has them. If
this realm was imported from an OLDER file (or upgraded in place), delete both
— from the admin console (`Clients` → each → delete) or the Admin REST API:

```bash
read -rsp 'Keycloak bootstrap admin password: ' KC_ADMIN_PASSWORD; echo
token="$(curl -sf -X POST https://auth.horecaos.uz/realms/master/protocol/openid-connect/token \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=${KC_ADMIN_PASSWORD}" \
  | jq -r .access_token)"

for client in horecaos-operations horecaos-control-plane; do
  id="$(curl -sf -H "Authorization: Bearer ${token}" \
    "https://auth.horecaos.uz/admin/realms/horecaos/clients?clientId=${client}" | jq -r '.[0].id // empty')"
  [ -n "${id}" ] && curl -sf -X DELETE -H "Authorization: Bearer ${token}" \
    "https://auth.horecaos.uz/admin/realms/horecaos/clients/${id}" && echo "deleted ${client}"
done
unset KC_ADMIN_PASSWORD
```

**Check:** `curl ... /clients?clientId=horecaos-operations` answers `[]`.

**2. `horecaos-provisioning`, `horecaos-identity-reader`, and
`horecaos-staff-login` (ADR 0062's direct-grant client) hold fallback secrets
from the import file.** Rotate both
— Keycloak generates the replacement with its own CSPRNG, so no human types
or sees the value:

```bash
read -rsp 'Keycloak bootstrap admin password: ' KC_ADMIN_PASSWORD; echo
export KC_ADMIN_PASSWORD

docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm --no-TTY -e KC_ADMIN_PASSWORD -e BAO_TOKEN=<a token with write access> ops bash -s <<'SH'
set -euo pipefail
KC=http://keycloak:8080
token="$(printf 'client_id=admin-cli&grant_type=password&username=admin&password=%s' \
      "${KC_ADMIN_PASSWORD}" \
    | curl -sf -X POST "${KC}/realms/master/protocol/openid-connect/token" -d @- | jq -r .access_token)"
for pair in horecaos-provisioning:provisioning-secret horecaos-identity-reader:reader-secret horecaos-staff-login:staff-login-secret; do
    client="${pair%%:*}"; slot="${pair##*:}"
    id="$(curl -sf -H "Authorization: Bearer ${token}" \
        "${KC}/admin/realms/horecaos/clients?clientId=${client}" | jq -r '.[0].id')"
    secret="$(curl -sf -X POST -H "Authorization: Bearer ${token}" \
        "${KC}/admin/realms/horecaos/clients/${id}/client-secret" | jq -r .value)"
    bao kv put "horecaos/production/identity_admin/keycloak/${slot}" value="${secret}"
    printf '    rotated %s -> %s\n' "${client}" "${slot}"
done
SH
unset KC_ADMIN_PASSWORD
```

**Check:** re-running the loop's own GET against each client's secret no
longer returns the literal `development-only-not-a-secret-...` string (the
Admin API does not return secret values directly for that comparison — the
practical check is that `assign-service-account-roles.sh` below succeeds
with `HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1`, which refuses to report
success while either secret still matches the import file's fallback).

```bash
read -rsp 'Keycloak bootstrap admin password: ' HORECAOS_KEYCLOAK_ADMIN_PASSWORD; echo
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm --no-TTY \
  --volume "$(pwd)/platform/infra/keycloak:/keycloak:ro" \
  -e HORECAOS_KEYCLOAK_URL=http://keycloak:8080 \
  -e HORECAOS_KEYCLOAK_ADMIN_PASSWORD \
  -e HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1 \
  ops bash /keycloak/assign-service-account-roles.sh
unset HORECAOS_KEYCLOAK_ADMIN_PASSWORD
```

**Check:** prints the two role lines and `==> Done`.

**3. There is no bootstrap-admin realm role or fake user baked into the realm
file** (`horecaos-realm.json` carries no `users` array) — the one thing left
to remove is Keycloak's own `admin` **master-realm** bootstrap account, which
this file's import does not create but the base Keycloak image does on first
boot from `KC_BOOTSTRAP_ADMIN_*`. `deploy/compose.production.yml` sets
neither variable specifically to avoid this, so if the two admin API calls
above worked, that account either never existed on this host or is one you
created by hand for exactly this bootstrap — **create a named admin account
with a second factor now, and delete or disable the master-realm `admin`
account**, from the admin console: `Users` (master realm) → `admin` →
disable, after confirming the named replacement can sign in.

**Check:** signing in to the Keycloak admin console as the old `admin`
account fails; the named account succeeds and prompts for its second factor.

**Rollback:** none of the three steps above touch application data. If a
redirect URI or a rotated secret is wrong, repeat the relevant step with
corrected values — nothing here needs to be undone first.

### Profiles: no local fixtures in production

`deploy/compose.production.yml` sets `SPRING_PROFILES_ACTIVE: production`
and nothing else. This matters because of exactly one line in
[`docs/local-fixtures.md`](../local-fixtures.md): the `local` profile is
what adds `classpath:db/local-fixtures` to Flyway's scan locations, and nothing
outside that profile ever creates the demo tenant, the preset-OTP phone
number, or the fake CLICK payment provider. Section 4's migration run above
therefore applied only `classpath:db/migration` — production has zero
tenants, zero orders, and zero customers until the pilot tenant is onboarded
through the real API in section 5.

**Check:**

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT count(*) FROM tenant.tenants;"
```

returns `0`, and

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  exec platform-app env | grep SPRING_PROFILES_ACTIVE
```

reads `SPRING_PROFILES_ACTIVE=production`, never `local`.

---

## 5. First boot, continued: start the application and onboard the pilot tenant

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d edge autoheal platform-app
```

**Check:** `docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps platform-app`
shows `(healthy)` within about two minutes, and

```bash
curl -fsS https://api.horecaos.uz/actuator/health/readiness
```

(from a machine that is **not** this box — that also proves DNS, the
certificate, and the colocation provider's routing) answers
`{"status":"UP"}`.

Onboard the pilot tenant through the real control-plane API — never by hand
in the database — following
[`docs/runbooks/proving-run.md`](proving-run.md)'s phases 1–6 against
`https://api.horecaos.uz` instead of `localhost:8080`. Once it is `ACTIVE`,
fill the three storefront variables in `/etc/horecaos/production.env`:

```text
HORECAOS_STOREFRONT_TENANT_ID=<the tenant id>
HORECAOS_STOREFRONT_BRAND_ID=<the brand id>
HORECAOS_STOREFRONT_LOCATION_ID=<the location id>
```

Create the scoped MinIO service accounts (not the root credential — the
application must not be able to reach the backup bucket, and the backup
account must not be able to reach media):

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d minio
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm --no-TTY ops bash -c '
    mc alias set root http://minio:9000 "'"${HORECAOS_MINIO_ROOT_USER}"'" "'"$(cat /run/horecaos/secrets/minio-root-password)"'" >/dev/null
    mc mb --ignore-existing root/horecaos-media
    mc mb --ignore-existing root/horecaos-backups
    mc version enable root/horecaos-backups
    mc admin user add root media-service <(head -c 32 /dev/urandom | base64)
    mc admin policy create root media-rw /dev/stdin <<POLICY
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:*"],"Resource":["arn:aws:s3:::horecaos-media/*","arn:aws:s3:::horecaos-media"]}]}
POLICY
    mc admin policy attach root media-rw --user media-service'
```

Store the resulting media credential in OpenBao (values from the commands
above — the point of the exercise is that this pair, and the equivalent
backup pair, never touch `deploy/env.template` or this shell's history file):

```text
horecaos/production/object_storage/platform/media-access-key
horecaos/production/object_storage/platform/media-secret-key
```

Start the frontends and pull it all together:

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d storefront-web control-plane-web operations-web
```

**Check:** `https://horecaos.uz/`, `https://operations.horecaos.uz/`, and
`https://admin.horecaos.uz/` each answer 200 with the application shell.

**Rollback:** stopping any of these three (`docker compose ... stop storefront-web`)
does not affect the API or the other two — they are independent containers
on purpose.

---

## 6. Backups

**What exists today: nightly encrypted logical backups (`pg_dump`), not
continuous WAL archiving.** Said plainly because ADR 0061's Decision section
names "WAL archiving" as part of the design and its own implementation
checklist still has that line unchecked — this runbook describes what
`platform/infra/backup/backup.sh` actually does, which is a nightly
`pg_dump --format=custom`, encrypted, uploaded to two S3-compatible
destinations, read back and verified. **Recovery point is therefore up to 24
hours**, not minutes — [`docs/runbooks/README.md`](README.md) states the
same number for the same reason. Continuous archiving (`pg_basebackup` +
WAL shipping, or `pgBackRest`) would close that gap and is future work, not
this runbook's claim.

### Store the backup credentials

Generate a MinIO service account scoped to `horecaos-backups` only (same
pattern as the media account above, different bucket, different policy),
and a real credential on whichever S3-compatible provider holds the
off-site bucket — **UzCloud S3 is the default candidate** named in ADR
0061, chosen for staying in-country; any S3-compatible endpoint works
because nothing here calls a provider-specific API:

```text
horecaos/production/object_storage/platform/backup-access-key
horecaos/production/object_storage/platform/backup-secret-key
horecaos/production/object_storage/platform/backup-offsite-access-key
horecaos/production/object_storage/platform/backup-offsite-secret-key
```

**The off-site pair must not live in the same account as the backup
passphrase** (section 3) — one compromise yielding both the ciphertext and
the key is the same as no encryption. The off-site bucket needs versioning
and object-lock or a retention rule enabled on the provider's own side, so a
stolen key can add history but not delete it.

### Install the nightly job

Two equivalent options — pick whichever this host already uses elsewhere.

**cron:**

```bash
mkdir -p /var/lib/horecaos
cat > /etc/horecaos/alerting.env <<'EOF'
HORECAOS_HEARTBEAT_URL=https://hc-ping.com/<uuid>
HORECAOS_ALERT_WEBHOOK=https://api.telegram.org/bot<token>/sendMessage?chat_id=<id>
HORECAOS_BACKUP_STAMP=/var/lib/horecaos/last-backup
EOF
chmod 0600 /etc/horecaos/alerting.env

crontab -e
```

```cron
*/5 * * * * HORECAOS_COMPOSE_FILE=/opt/horecaos/horecaos/deploy/compose.production.yml /opt/horecaos/horecaos/platform/infra/production/heartbeat.sh
17 2 * * *  HORECAOS_COMPOSE_FILE=/opt/horecaos/horecaos/deploy/compose.production.yml /opt/horecaos/horecaos/platform/infra/production/run-backup.sh && touch /var/lib/horecaos/last-backup
```

**systemd timer**, equivalently:

```ini
# /etc/systemd/system/horecaos-backup.service
[Unit]
Description=HorecaOS nightly encrypted backup

[Service]
Type=oneshot
Environment=HORECAOS_COMPOSE_FILE=/opt/horecaos/horecaos/deploy/compose.production.yml
Environment=HORECAOS_ENV_FILE=/etc/horecaos/production.env
ExecStart=/opt/horecaos/horecaos/platform/infra/production/run-backup.sh
ExecStartPost=/usr/bin/touch /var/lib/horecaos/last-backup
```

```ini
# /etc/systemd/system/horecaos-backup.timer
[Unit]
Description=Run horecaos-backup nightly at 02:17

[Timer]
OnCalendar=*-*-* 02:17:00
Persistent=true

[Install]
WantedBy=timers.target
```

```bash
systemctl daemon-reload
systemctl enable --now horecaos-backup.timer
systemctl enable --now horecaos-heartbeat.timer   # same pattern, OnCalendar=*:0/5
```

**Check, either path:** run the backup once by hand before trusting the
schedule —

```bash
/opt/horecaos/horecaos/platform/infra/production/run-backup.sh
```

the last line reads `Done: horecaos-<timestamp>.dump.enc (local and
off-site)`. Anything else — including a clean exit with `No off-site
destination is configured` — means there is no backup tonight; fix the
off-site credentials before moving on, not after.

**Rollback:** `crontab -e` and delete the two lines, or
`systemctl disable --now horecaos-backup.timer horecaos-heartbeat.timer`.
No backup already taken is affected either way.

### Monthly restore rehearsal

```bash
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env exec -T \
  -e HORECAOS_BACKUP_PASSPHRASE="$(docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
      run --rm --no-TTY ops bao-get.sh production/data_encryption/platform/backup-passphrase)" \
  ops /opt/horecaos/backup/rehearse-restore.sh
```

Run this against **staging**, restoring a **production** backup object,
once a month — never against the live production database (`rehearse-restore.sh`
always creates a separate scratch database and drops it on success; the
danger is running it with production connection strings for both source and
target, which the command above avoids by running from staging's own
`ops` container against staging's own `platform-db`, pointed at a production
backup object by bucket/prefix). Record the elapsed time and the row-count
match in `docs/runbooks/README.md`'s "Recovery time" line — an unmeasured
number is a guess, not a plan.

**Check:** `REHEARSAL PASSED` and the baseline/restored counts match.

**Rollback:** none needed — the rehearsal database is dropped automatically
on success, and left for inspection (named, printed) only on failure.

---

## 7. Verify — the production smoke checklist

Run all of these from a machine that is **not** this box, except where
noted.

| Check | Command | Expected |
|---|---|---|
| API health | `curl -fsS https://api.horecaos.uz/actuator/health/readiness` | `{"status":"UP"}` |
| Customer-facing health | `curl -fsS https://api.horecaos.uz/actuator/health/customer` | `{"status":"UP"}` once the pilot tenant is `ACTIVE` |
| TLS validity | `curl -vI https://api.horecaos.uz/ 2>&1 \| grep -A2 'Server certificate'` | issuer is Let's Encrypt (or your chosen CA), not expired, `SSL certificate verify ok` |
| Auth discovery | `curl -fsS https://auth.horecaos.uz/realms/horecaos/.well-known/openid-configuration \| jq -r .issuer` | `https://auth.horecaos.uz/realms/horecaos` exactly |
| Storefront reachable | `curl -o /dev/null -w '%{http_code}\n' -s https://horecaos.uz/` | `200` |
| Operations reachable | `curl -o /dev/null -w '%{http_code}\n' -s https://operations.horecaos.uz/` | `200` |
| Control plane reachable | `curl -o /dev/null -w '%{http_code}\n' -s https://admin.horecaos.uz/` | `200` |
| No public API docs | `curl -o /dev/null -w '%{http_code}\n' -s https://api.horecaos.uz/swagger-ui/index.html` | not `200` |
| Actuator surface is narrow | `curl -o /dev/null -w '%{http_code}\n' -s https://api.horecaos.uz/actuator/metrics` | `404` (the Caddyfile allowlists only `/actuator/health/readiness` and `/actuator/health/customer`) |
| A real storefront menu | `curl -fsS "https://api.horecaos.uz/api/v1/storefront/tenants/<pilot tenant>/brands/<pilot brand>/locations/<pilot location>/menu?locale=uz"` | the published menu, once section 5's onboarding published one |
| A nonexistent tenant | same URL with a made-up UUID | see below |

### What an empty/nonexistent-tenant request actually answers

Verified against `deploy/local-smoke.sh`'s own run, captured verbatim rather
than assumed. Two shapes, and they differ:

- **A menu request naming a tenant/brand/location that does not exist**
  (`GET .../tenants/<id>/brands/<id>/locations/<id>/menu?locale=uz`) answers
  **`400 Bad Request`**, not `404` and not an empty `200`:

  ```json
  {"timestamp":"2026-09-01T13:16:30.734Z","status":400,"error":"Bad Request","path":"/api/v1/storefront/tenants/.../menu"}
  ```

  **This is Spring Boot's own default error body, not this platform's
  [Problem Details](../adr/built/0031-http-api-conventions.md) contract** —
  no `type`, `title`, `detail`, `code`, or `instance` field, which every
  other documented error response in this platform carries. That gap is
  real and is flagged in this task's implementation report as a follow-up:
  whatever validates the location path here throws before the platform's
  own `@ExceptionHandler` maps it to Problem Details. Do not treat this
  shape as intentional or rely on `status` alone to distinguish it from a
  genuine bad request elsewhere in the API.

- **`GET /api/v1/storefront/pickup-locations?lat=...&lon=...`** (no
  tenant/brand/location in the path at all) answers **`200`** with
  `{"locations":[]}` — an honest empty list, because this endpoint's
  question ("what pickup points are near here") has a real answer
  (none exist yet) rather than a not-found one.

Re-confirm both shapes here on the real box the first time this table is
executed for real, and update this section if either observed response
differs — the local proof is evidence about the code as it stood on
2026-09-01, not a permanent guarantee.

### Telegram webhook reachability

```bash
curl -fsS "https://api.horecaos.uz/providers/telegram/<installationId>/webhook" \
  -X POST -H 'Content-Type: application/json' -d '{}'
```

answers (not necessarily `200` — an empty/malformed body is expected to be
rejected) rather than timing out or connection-refusing, which is all this
step checks: the route exists and Caddy/the application are reachable on
it. **`setWebhook` itself, pointing a real bot at this endpoint, is
[`sendpulse-cutover.md`](sendpulse-cutover.md)'s job, not this runbook's** —
that procedure needs a specific bot, a specific installation row, and the
owner's own BotFather session, none of which exist yet on a freshly-booted
host.

---

## 8. Upgrade procedure

```bash
export HORECAOS_IMAGE_TAG=<new short git sha>
export HORECAOS_FRONTEND_IMAGE_TAG="${HORECAOS_IMAGE_TAG}"
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env pull

export FLYWAY_PASSWORD="$(cat /run/horecaos/secrets/platform-db-migrator-password)"
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm platform-migrate migrate
unset FLYWAY_PASSWORD

docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d --no-deps platform-app storefront-web control-plane-web operations-web
```

**Check:** `docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env ps`
all healthy, and `curl -fsS https://api.horecaos.uz/actuator/health/readiness`
answers `UP`, from off-box.

**Rollback = the previous image tags, redeployed:**

```bash
export HORECAOS_IMAGE_TAG=<the previous short git sha>
export HORECAOS_FRONTEND_IMAGE_TAG="${HORECAOS_IMAGE_TAG}"
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  up -d --no-deps platform-app storefront-web control-plane-web operations-web
```

**The Flyway roll-forward rule, stated once and binding:** Flyway is
forward-only in this stack — no down-migrations, and `platform-migrate`
carries no `undo` command. Rolling code back is always safe. Rolling schema
back is not possible; it is only safe to roll code back onto a schema a
migration already changed if that migration was additive (ADR 0023's
expand/migrate/contract discipline: a new nullable column or table is fine
to run old code against, a dropped column or a `NOT NULL` added to an
existing one is not). If unsure, `docker compose ... run --rm platform-migrate info`
and read the migration file before rolling back — `deploy.md`'s section 3
has the full decision tree for a genuinely failed migration.

---

## 9. Optional: installing a CI deploy key

**Off by default. Nothing above this line, and nothing in
`.github/workflows/ci.yml`, assumes this exists.** If and when the owner
decides to let CI deploy automatically after a successful `publish-images`
run, this is what it needs — and no more than this:

1. Generate a dedicated SSH keypair, used for nothing else:
   `ssh-keygen -t ed25519 -f ci-deploy-key -C "horecaos-ci-deploy"`.
2. Add the public key to a dedicated, least-privilege deploy user on this
   box — **not root, and not devops's own account** — whose shell is
   restricted to running one script (a wrapper around section 8's own
   `pull` + `run --rm platform-migrate migrate` + `up -d --no-deps`
   commands, nothing else, and no interactive shell).
3. Store the private key as a GitHub Actions secret
   (`Settings` → `Secrets and variables` → `Actions`), scoped to an
   **environment** with required reviewers if the owner wants a manual
   approval gate on top of the technical restriction.
4. Add a `deploy` job to `.github/workflows/ci.yml`, gated the same way
   `publish-images` is (`needs: publish-images`, `if: github.ref == 'refs/heads/main'`),
   that SSHes in as the deploy user and runs the wrapper script from step 2.
5. **The health gate and the rollback tag stay exactly as section 8
   describes them** — an automated deploy is section 8's own commands run
   by CI instead of by a human, not a different procedure. If the health
   check after `up -d` fails, the job should run section 8's rollback
   commands itself and exit non-zero, not leave a red deploy running.

**This step, if taken, is the first and only time CI gets any access to
this host.** Revoking it is deleting the deploy user and its authorized key
— no OpenBao policy, no application secret, and no other credential on this
host depends on it existing.

---

## 10. Staging variant

The same runbook, on a small VM at **aHOST or UzCloud — a different
provider than production**, per ADR 0061's own requirement: a release that
only ever deploys to the box it was designed for proves nothing about
portability, and staging exists specifically to rehearse the "deploy
anywhere" claim before production ever sees a release.

Differences from everything above:

- **Section 1 (Prerequisites):** same OS assumption, same Docker install,
  same firewall rules. Sizing follows `deploy/env.staging.example`'s smaller
  defaults (a 4 vCPU / 8 GB VM), not section 1's 16 GB assumption.
- **Section 2 (Fetch):** identical.
- **Section 3 (Configure):** copy `deploy/env.staging.example` instead of
  `deploy/env.template` to `/etc/horecaos/staging.env`, and every
  `--env-file /etc/horecaos/production.env` in the commands above becomes
  `--env-file /etc/horecaos/staging.env`. OpenBao's KV paths become
  `horecaos/staging/*` throughout (the environment segment
  `HORECAOS_ENVIRONMENT=staging` in that file is what makes the running
  application resolve secret references against that path instead of
  `horecaos/production/*` — a staging AppRole physically cannot read a
  production secret even pointed at the same OpenBao instance, because the
  policy only grants `horecaos/staging/*`; provision a parallel
  `horecaos-staging`/`horecaos-staging-deploy` policy pair, copied from
  `deploy/infra/openbao/policies/` with `production` replaced by `staging`,
  rather than reusing the production ones).
- **Section 4 (First boot) and 5:** identical procedure. Onboard a
  dedicated staging tenant (never the production pilot's ids), typically
  re-created by [`tools/proving-run`](proving-run.md) on every release
  rather than onboarded once and kept.
- **Section 6 (Backups):** staging's own nightly backup exists mainly to
  give the monthly restore rehearsal somewhere to run *from* — its
  retention is shorter (`deploy/env.staging.example`: 7 days, not 30) and
  its own data is not precious. The rehearsal itself restores a
  **production** backup object onto staging, so staging's off-site bucket
  only needs to exist, not hold anything irreplaceable.
- **Section 7 (Verify):** the same checklist, against
  `*.staging.horecaos.uz`. A green run here, on a different provider, **is
  the release gate** — ADR 0061's Rollout step 2 names this explicitly:
  "staging on a small aHOST/UzCloud VM + the SSH deploy pipeline; proving
  run green on staging becomes the release gate." Do not deploy to
  production without first getting a green
  [`proving-run.md`](proving-run.md) on staging for the same image tags.
- **Section 8 (Upgrade)** and **section 9 (optional CI deploy key)**:
  identical, pointed at the staging host and its own `staging.env`.

**Rollback for the whole staging variant:** none needed beyond section 8's
own — staging carries no production data and no production traffic, so the
worst case is re-running section 3 onto a fresh volume.
