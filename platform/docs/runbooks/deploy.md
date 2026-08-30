# Deploy

Everything in this file assumes you are on the colocated production host, in
`/opt/qoida/qoida-platform`, as root.

Two shortcuts used throughout, so that no command in this file is longer than a
line:

```bash
cd /opt/qoida/qoida-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/qoida/production.env'
```

---

## 1. The normal deploy

### Before you start

Run the suite. The image build does not run tests, deliberately, and this is the
step that would otherwise be skipped.

```bash
git pull --ff-only
tools/mvn-serial verify
```

**Check:** `BUILD SUCCESS`. If it is red, stop. There is no version of this
where deploying a red build is the right call at night.

### Deploy

```bash
sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/deploy.sh
```

It asks once for your OpenBao token. Everything else is automatic.

**What it does, in order** — worth knowing because the failure messages name
these phases:

| Phase | What it does | Why it can stop here |
|---|---|---|
| 0 | Refuses a dirty working tree | The image tag is the git sha; a dirty tree makes the tag a lie and rollback meaningless |
| 1 | Mounts a tmpfs at `/run/qoida/secrets` | Not root |
| 2 | Authenticates you to OpenBao | Sealed, or wrong token |
| 3 | Writes four startup passwords onto the tmpfs | A secret is missing from OpenBao |
| 4 | Issues a fresh AppRole secret-id | The AppRole does not exist — the host was never bootstrapped |
| 5 | Builds `qoida/platform:<sha>` and `qoida/platform-migrate:<sha>` | Compile failure, or the disk is full |
| 6 | Starts dependencies, **runs Flyway**, then starts the application | See section 3 |
| 6b | Audits the application role | A migration created a table without granting the application access — see section 3 |
| 7 | Waits for the application to report healthy | See section 4 |

### What success looks like

```bash
qc ps
```

Every service `running`, and every service with a health check `(healthy)`:
`edge`, `platform-app`, `platform-db`, `keycloak`, `keycloak-db`, `kafka`,
`minio`, `openbao`, `openbao-agent`, `autoheal`.

Then, from a machine that is **not** this one:

```bash
curl -fsS https://api.qoida.uz/actuator/health/readiness
```

**Check:** `{"status":"UP"}`. Doing this from your laptop rather than from the
server tests DNS, the certificate, the colocation provider's routing, and the
edge — none of which `qc ps` can see.

And confirm the right thing is running:

```bash
qc images platform-app
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  "$(qc ps -q platform-app)"
```

**Check:** the revision label is the sha you deployed.

---

## 2. Rolling back

The previous image is still on the host, tagged `qoida/platform:previous`. It was
tagged by the deploy script before the new one replaced it.

That tag is only trustworthy because the deploy refuses to leave it stale. If the
application container is not running when a deploy starts, the script cannot tell
which image it is replacing, so it stops:

```text
!!  platform-app is not running, so the image this deploy replaces cannot be
    identified — and qoida/platform:previous already points at an older release.
```

Start the release that is supposed to be running and deploy again, or name the
image being replaced yourself with `docker image tag`. `QOIDA_NO_ROLLBACK_TARGET=1`
proceeds without a rollback target and says so — for a rebuilt host, where there
genuinely is none.

```bash
QOIDA_IMAGE_TAG=previous \
QOIDA_SECRET_DIR=/run/qoida/secrets \
qc up -d --no-deps platform-app
```

**Check:** `qc ps platform-app` shows `(healthy)` within about ninety seconds,
and the readiness curl above answers `UP`.

### The rule that decides whether you may

**A rollback of code is always safe. A rollback of schema is not, and this
deployment cannot do it.**

Flyway here is forward-only. There are no down-migrations, and there is no
`undo`. So:

- If the deploy failed **before** the migration phase, nothing changed. Roll
  back freely.
- If the deploy failed **after** the migration succeeded, the schema is new and
  the old image has to run against it. That works if — and only if — the
  migration was backward-compatible, which ADR 0023's expand/migrate/contract
  protocol requires every migration to be. Adding a nullable column, adding a
  table, adding an index: all fine. Dropping a column the old code still selects:
  not fine, and the old image will fail on its first query.

If you are unsure whether the migration was additive, look before you roll back:

```bash
qc run --rm platform-migrate info | tail -20
git show --stat HEAD -- src/main/resources/db/migration/
```

If the diff contains `DROP`, `RENAME`, or a `NOT NULL` added to an existing
column, rolling the code back is not enough and you are in section 3.

---

## 3. Flyway

### The migration failed. What now?

**First, do not start the new application image.** The deploy script has already
stopped for you. Resist the urge to "just try again" — run `info` and find out
what state the database is actually in.

```bash
export FLYWAY_PASSWORD="$(qc run --rm --no-TTY ops \
  bao-get.sh production/database/platform/migrator-password)"
qc run --rm platform-migrate info
```

That reads the password through the OpenBao agent token the `ops` container has
mounted, so it works without you holding a token and without the value reaching
your shell history.

The last rows of that table are the whole story.

**Case A — the last migration shows `Pending`.** Nothing was applied. The
failure was connection, credentials, or a lock. Fix that and re-run:

```bash
qc run --rm platform-migrate migrate
```

**Case B — the last migration shows `Failed`.** The script ran and threw
partway. What that means depends entirely on the file:

```bash
sed -n '1,200p' src/main/resources/db/migration/V00XX__*.sql
```

PostgreSQL runs each Flyway migration in a transaction, so unless the script
contains `COMMIT` or a `CREATE INDEX CONCURRENTLY`, the statements rolled back
together and the database is unchanged. Confirm rather than assume:

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c "\dt+ <the schema it touched>.*"
```

- **If the schema is unchanged:** fix the SQL, commit a *new* migration file with
  the next version number, and deploy again. Never edit a version that Flyway has
  a row for; its checksum is recorded and `validate` will reject the edited file
  on every subsequent run.
- **If the schema is partly changed** (the migration used `CONCURRENTLY`, or had
  an explicit `COMMIT`): write a new forward migration that finishes or reverses
  the partial work by hand. This is the one case that needs thought rather than a
  command, and it is why `CREATE INDEX CONCURRENTLY` should be in a migration of
  its own containing nothing else.

Once the database is genuinely consistent again, clear the failed row:

```bash
qc run --rm platform-migrate repair
qc run --rm platform-migrate info
```

**`repair` does not repair your data.** It rewrites Flyway's own history table:
it removes failed rows and realigns checksums. Running it while the schema is
still half-changed tells Flyway to forget that anything went wrong, and the next
`migrate` will build on a foundation nobody checked. That is why it is the last
step here and not the first.

### The deploy stopped on the grant audit

The message names a table that exists and that `qoida_application` cannot read.
That means a migration created it and did not grant the application access — in
development the application connects as the owner, so the omission is invisible
until the first production start.

**The fix is a GRANT in a new migration**, because Flyway is forward-only and the
migration that created the table cannot be edited. Add the grant block to a new
version and redeploy. `V0035__grant_application_access_to_ungranted_tables.sql`
is the worked example: it carries the grants nine earlier migrations omitted, and
its closing comment says why each table gets the privileges it does rather than
all four.

Narrow the privileges to what that table's code actually issues. The audit only
probes `SELECT`, so a table the application can read but not write passes here
and fails later, at the first write.

Do **not** run the GRANT by hand on the server. It works, and then the next
restore silently drops it, because grants live with the objects and a dump
restored with `--no-privileges` does not carry them.

### The migration is taking too long

An `ALTER TABLE` waiting on a lock will sit there until `lock_timeout`. See what
is blocking it:

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c \
  "SELECT pid, state, wait_event_type, left(query, 80) FROM pg_stat_activity WHERE datname='qoida' AND state <> 'idle';"
```

The application's own role has `lock_timeout = 5s` and
`statement_timeout = 60s`, so it is rarely the blocker. The migration role has
neither, on purpose — an index build is allowed to take an hour.

To abandon a migration that is stuck:

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c "SELECT pg_cancel_backend(<pid>);"
```

Then treat it as Case B.

---

## 4. It did not come up

Work down this list. Each step is cheap and rules something out.

```bash
qc ps                       # what is not healthy?
qc logs --tail 100 platform-app
```

**"waiting for the OpenBao agent to render /run/bao/qoida.env"**, then the
container exits after two minutes.
The agent has no token or no rendered secret.

```bash
qc logs --tail 50 openbao-agent
qc exec -T openbao bao status
```

If `Sealed: true`, that is your answer — go to section 5. If the agent logs
`permission denied`, the AppRole policy no longer covers the secret path; compare
`infra/openbao/policies/qoida-platform.hcl` against what is loaded.

**`Connection to platform-db:5432 refused` or authentication failure.**

```bash
qc logs --tail 50 platform-db
qc exec -T platform-db pg_isready -U qoida_migrator -d qoida
```

An authentication failure for `qoida_app` after a *rebuilt* database means the
init script did not run, or ran with a different password than the one in
OpenBao. The init script only runs on an empty data directory, so this is almost
always a restored database plus a rotated password. Fix by setting the password
to match:

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida
\getenv p QOIDA_APP_PASSWORD
ALTER ROLE qoida_app PASSWORD :'p';
```

**`Unable to resolve the Configuration with the provided Issuer`.**
Keycloak is not answering on the public auth origin. Almost always DNS, the
certificate, or `KC_HOSTNAME` disagreeing with `QOIDA_AUTH_ORIGIN`.

```bash
qc exec -T platform-app wget -qO- https://auth.qoida.uz/realms/qoida/.well-known/openid-configuration | head -c 200
```

That command runs *from inside the application container*, which is the only
place the answer matters.

**Nothing in the logs, and the container is `unhealthy` rather than exited.**
`autoheal` will restart it within fifteen seconds of the health check going red,
three times, before you should intervene. If it is still cycling:

```bash
qc exec -T platform-app wget -qO- http://127.0.0.1:8080/actuator/health/readiness
```

**Out of memory.** The JVM is configured to exit rather than limp:

```bash
qc logs platform-app | grep -i "OutOfMemory"
docker stats --no-stream
```

A heap dump is written to the container's `/tmp`, which is a 512 MB tmpfs and is
lost when the container is replaced. Copy it out first if you want it.

### Getting back

If nothing above works and orders are not being taken, roll back (section 2)
and diagnose in daylight. A production incident is not a debugging session.

---

## 5. The power came back

This is the one procedure that requires a human and cannot be automated, and
that is deliberate.

Every container has `restart: unless-stopped` and comes back by itself. OpenBao
comes back **sealed** — it has to, because the alternative is keeping the unseal
key on the same disk as the data it protects, which is the same as not
encrypting it. Nothing that needs a credential can start until it is unsealed.

```bash
qc exec openbao bao operator unseal      # run three times, three different shares
qc exec -T openbao bao status            # Sealed: false
```

Then re-issue the agent's credentials, because the tmpfs holding them did not
survive the reboot:

```bash
sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/deploy.sh
```

Running the full deploy is correct here even though nothing changed: it is
idempotent, the image is already built and cached, and it is one command instead
of four remembered ones.

**Check:** `qc ps` all healthy, and the external uptime monitor green.

Expect the platform to be down from the moment the power failed until you run
these commands. On this topology that is the honest recovery story, and the
number that matters is how long it takes you to notice — which is what the
dead-man's-switch in `infra/production/heartbeat.sh` exists to make short.

---

## 6. Bootstrapping a new host

Once per machine. Budget two hours and do not do it at night.

### Prepare the host

```bash
# Docker, git, and nothing else that listens on a port.
apt-get update && apt-get install -y docker.io docker-compose-plugin git

# Swap off, permanently. OpenBao 2.x removed mlock support, so nothing stops the
# kernel writing an unsealed master key into a swap partition where it survives a
# power cut. This is the one host setting the design depends on.
swapoff -a && sed -i '/ swap /d' /etc/fstab

mkdir -p /opt/qoida /etc/qoida /var/lib/qoida
git clone <repo> /opt/qoida/qoida-platform
cp /opt/qoida/qoida-platform/infra/production/production.env.example /etc/qoida/production.env
chmod 0600 /etc/qoida/production.env
$EDITOR /etc/qoida/production.env
```

Generate the Kafka cluster id once and paste it in:

```bash
docker run --rm apache/kafka:4.3.1 /opt/kafka/bin/kafka-storage.sh random-uuid
```

**Check:** `docker compose -f compose.production.yaml --env-file /etc/qoida/production.env config >/dev/null`
exits zero. It will name any variable you missed.

Two variables have **no default and no example line**, deliberately, because a
default for either is worse than a missing one. Add both by hand:

```text
# ADR 0040. The environment segment is what OpenBao's KV path is built from, so
# this must name the environment you are deploying, not `local`. The application
# refuses to start without it — see "the pepper" below.
QOIDA_HANDOVER_PEPPER_REF=qoida:production:data_encryption:platform:handover-pepper

# ADR 0034. The bucket outside this building. The nightly backup refuses to run
# without it rather than quietly keeping a copy on the machine it is backing up,
# so this is a gate rather than an option.
QOIDA_BACKUP_OFFSITE_ENDPOINT=https://<the S3-compatible endpoint you chose>
```

`config` will not catch a missing `QOIDA_HANDOVER_PEPPER_REF` — nothing reads it
until the application starts. The failure is
`Could not resolve placeholder 'QOIDA_HANDOVER_PEPPER_REF'` during startup.

### Point DNS at the host

`api.qoida.uz`, `auth.qoida.uz` and `media.qoida.uz`, all A records, before the
next step — Caddy asks Let's Encrypt for certificates the first time it starts,
and the challenge fails if the names do not resolve here yet.

### The media origin

Photos are served to browsers through presigned URLs, and a presigned URL is
signed *for one origin*. `QOIDA_MEDIA_ORIGIN` in the env file is that origin, and
it must be the public HTTPS name — a URL signed for `http://minio:9000` does not
resolve on a customer's phone, and would carry the signature and the object in
clear text if it did.

```text
QOIDA_MEDIA_ORIGIN=https://media.qoida.uz
QOIDA_MEDIA_HOSTNAME=media.qoida.uz
```

The edge has to serve that name. Add a site block to
`infra/production/caddy/Caddyfile` alongside the API and auth ones:

```caddyfile
{$QOIDA_MEDIA_ORIGIN} {
	# Objects only. The MinIO console is off, but the S3 API also carries bucket
	# creation, policy and admin paths, and none of them belong on a public
	# origin — even behind a signature check.
	handle {
		reverse_proxy minio:9000
	}

	header {
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		X-Content-Type-Options "nosniff"
		-Server
	}

	log {
		output stdout
		format json
	}
}
```

The edge reaches MinIO over the `media` network, which holds those two
containers and nothing else: MinIO must not be on `public`, because the store
that holds the backups should have no route to the internet, and the edge must
not be on `core`, because the one internet-facing process should have no path to
PostgreSQL.

**Check:** after the first deploy, upload a photo through the operations console
and open the URL it returns from a phone on mobile data. `https`, and it loads.

### Bootstrap OpenBao

```bash
sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/bootstrap.sh
```

It will stop and wait while you record the five unseal shares and the root
token. **Have somewhere to put them before you start.** Three of five unseal
it, so:

- two shares in the operator's password manager,
- two in a sealed envelope held somewhere that is not the server room,
- one with the business owner.

The root token is revoked at the end of the script. A new one can be minted from
three shares if it is ever genuinely needed.

### Store the secrets bootstrap could not generate

```bash
qc exec -it openbao sh
export BAO_TOKEN=<a token with write access>

bao kv put qoida/production/data_encryption/platform/kek \
  value="$(head -c 32 /dev/urandom | base64)"
bao kv put qoida/production/data_encryption/platform/backup-passphrase \
  value="$(head -c 32 /dev/urandom | base64)"

# ADR 0040's handover pepper, at the path QOIDA_HANDOVER_PEPPER_REF names.
bao kv put qoida/production/data_encryption/platform/handover-pepper \
  value="$(head -c 32 /dev/urandom | base64)"
```

**The first two have no second copy anywhere.** Losing the KEK makes every
encrypted personal-data column permanently unreadable (ADR 0029). Losing the
backup passphrase makes every backup permanently unreadable. Print them, seal
them in an envelope, and store the envelope somewhere other than this building.

The pepper is recoverable in the sense that a new one can be generated, but
rotating it invalidates the hash of every handover challenge open at that
instant, so it is an ADR 0028 rotation procedure rather than a restart. The
application will not start without it: `HandoverCodeHasher` resolves it while the
context is being built, and there is no fallback value by design — a pepper with
a default is a pepper every deployment shares.

The remaining secrets come from Keycloak and MinIO and can only be created after
those services exist:

```text
qoida/production/identity_admin/keycloak/provisioning-secret
qoida/production/identity_admin/keycloak/reader-secret
qoida/production/object_storage/platform/media-access-key
qoida/production/object_storage/platform/media-secret-key
```

plus the credentials the nightly backup uses:

```text
qoida/production/object_storage/platform/backup-access-key
qoida/production/object_storage/platform/backup-secret-key
qoida/production/object_storage/platform/backup-offsite-access-key
qoida/production/object_storage/platform/backup-offsite-secret-key
```

The MinIO pairs must be **service accounts scoped to one bucket each**, not the
root credential. The application should not be able to reach the backup bucket at
all: if it can, a bug in media cleanup can delete the backups. The backup account
should not be able to reach the media bucket either.

The **off-site** pair is not a MinIO credential at all. Generate it on whichever
provider holds the off-site bucket, scoped to that one bucket, and enable
versioning plus object-lock or a retention rule on it there — a credential that
can delete the history is a backup an attacker can erase. It must not live in the
same account as the passphrase above: one compromise yielding both the ciphertext
and the key means the encryption did nothing.

**The application will not start until the two media keys exist.** It resolves
them while constructing the S3 client, and the failure reads `No secret is
configured for qoida:production:object_storage:platform:media-access-key` inside
a bean-creation stack trace that names `catalogPublicationService` at the top and
mentions nothing about secrets until nine `Caused by` lines down.

### Create the buckets

```bash
qc up -d minio
qc run --rm --no-TTY ops bash -c '
  mc alias set p http://minio:9000 \
    "$(bao-get.sh production/object_storage/platform/backup-access-key)" \
    "$(bao-get.sh production/object_storage/platform/backup-secret-key)" >/dev/null
  mc mb --ignore-existing p/qoida-backups
  mc version enable p/qoida-backups
  mc mb --ignore-existing p/qoida-media
  mc ls p/'
```

**Check:** both buckets listed, and `qoida-backups` reports versioning enabled.
Without versioning a single mistaken `mc rm` removes every backup with no undo.

### Import the Keycloak realm

Once, by hand, and never again automatically:

```bash
qc up -d keycloak-db
qc run --rm --no-TTY \
  --volume "$(pwd)/infra/keycloak/realm:/opt/keycloak/data/import:ro" \
  keycloak import --dir /opt/keycloak/data/import
qc up -d keycloak
```

**Check:** the import ends with `Keycloak stopped` and no `ERROR` line, then

```bash
curl -fsS -H "Host: auth.qoida.uz" http://127.0.0.1/realms/qoida/.well-known/openid-configuration | head -c 120
```

reports an `issuer` exactly equal to `QOIDA_AUTH_ORIGIN` + `/realms/qoida`. If it
differs by so much as a scheme, every token the application receives will be
rejected.

A one-off container, not `qc exec` into the running server: `kc.sh import` starts
a second JVM that binds the management port, and inside a running server's
container that fails with `Unable to start the management interface on
0.0.0.0:9000` — which does not read as "you cannot do this here".

`--import-realm` is deliberately absent from the compose file. Leaving it on
means a file in the repository silently overwrites production identity
configuration on any restart, and the restart will not look like a configuration
change to anyone.

### Rotate the service-account secrets

**Do this before anything else touches the realm.** The import file gives
`qoida-provisioning` and `qoida-identity-reader` a secret only so that a laptop
works out of the box; the fallback value is in git, and `qoida-provisioning`
holds `manage-users`. Until this step is done, a checkout of this repository is
realm-wide user administration.

Keycloak generates the replacements with its own CSPRNG, so nobody invents a
value and nobody has to be trusted to choose a good one. Neither secret is ever
typed, echoed, or written to this host.

```bash
qc up -d keycloak

read -rsp 'Keycloak bootstrap admin password: ' KC_ADMIN_PASSWORD; echo
read -rsp 'OpenBao token with write access: '   BAO_WRITE_TOKEN;   echo
export KC_ADMIN_PASSWORD BAO_WRITE_TOKEN

qc run --rm --no-TTY -e KC_ADMIN_PASSWORD -e BAO_WRITE_TOKEN ops bash -s <<'SH'
set -euo pipefail
KC=http://keycloak:8080
BAO=http://openbao:8200

# Credentials travel on stdin or in a header read from a file — never in an
# argument list. Same rule as infra/production/ops/bao-get.sh, same reason.
token="$(printf 'client_id=admin-cli&grant_type=password&username=admin&password=%s' \
      "${KC_ADMIN_PASSWORD}" \
    | curl -sf -X POST "${KC}/realms/master/protocol/openid-connect/token" -d @- \
    | jq -r .access_token)"

auth="$(mktemp)"; trap 'rm -f "${auth}"' EXIT
printf 'X-Vault-Token: %s\n' "${BAO_WRITE_TOKEN}" > "${auth}"

for pair in qoida-provisioning:provisioning-secret qoida-identity-reader:reader-secret; do
    client="${pair%%:*}"; slot="${pair##*:}"

    id="$(curl -sf -H "Authorization: Bearer ${token}" \
        "${KC}/admin/realms/qoida/clients?clientId=${client}" | jq -r '.[0].id')"

    # POST regenerates and returns the new secret in one call.
    secret="$(curl -sf -X POST -H "Authorization: Bearer ${token}" \
        "${KC}/admin/realms/qoida/clients/${id}/client-secret" | jq -r .value)"

    jq -n --arg v "${secret}" '{data:{value:$v}}' \
      | curl -sf -o /dev/null --header "@${auth}" --json @- \
        "${BAO}/v1/qoida/data/production/identity_admin/keycloak/${slot}"

    unset secret
    printf '    rotated %s -> %s\n' "${client}" "${slot}"
done
SH

unset KC_ADMIN_PASSWORD BAO_WRITE_TOKEN
```

It runs in the `ops` container because that is where `curl` and `jq` already are;
the host needs neither.

**Check:** apply the ADR 0009 roles with the rotation gate on. The script reads
each client's current secret and refuses to report success while either is still
the value from the import file. Keycloak has no published port here, so it runs
from a container on the compose network rather than from this host:

```bash
read -rsp 'Keycloak bootstrap admin password: ' QOIDA_KEYCLOAK_ADMIN_PASSWORD; echo
export QOIDA_KEYCLOAK_ADMIN_PASSWORD

qc run --rm --no-TTY \
  --volume "$(pwd)/infra/keycloak:/keycloak:ro" \
  -e QOIDA_KEYCLOAK_URL=http://keycloak:8080 \
  -e QOIDA_KEYCLOAK_ADMIN_PASSWORD \
  -e QOIDA_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1 \
  ops bash /keycloak/assign-service-account-roles.sh

unset QOIDA_KEYCLOAK_ADMIN_PASSWORD
```

It must print the two role lines and `==> Done`. If it prints
`is still using the secret from the import file`, the rotation did not take and
the realm is not safe to use yet.

Rotating again later is the same procedure. It is a Keycloak operation and an
OpenBao write; nothing in this repository changes, because the repository holds
references and never values (ADR 0028).

Then create a named admin account with a second factor and **delete the
bootstrap admin**.

### First deploy

```bash
sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/deploy.sh
```

### Set up the things that watch it

```bash
cat > /etc/qoida/alerting.env <<'EOF'
QOIDA_HEARTBEAT_URL=https://hc-ping.com/<uuid>
QOIDA_ALERT_WEBHOOK=https://api.telegram.org/bot<token>/sendMessage?chat_id=<id>
QOIDA_BACKUP_STAMP=/var/lib/qoida/last-backup
EOF
chmod 0600 /etc/qoida/alerting.env

crontab -e
```

```cron
*/5 * * * * /opt/qoida/qoida-platform/infra/production/heartbeat.sh
17 2 * * *  /opt/qoida/qoida-platform/infra/production/run-backup.sh && touch /var/lib/qoida/last-backup
```

Run the backup once by hand before trusting the schedule. It will refuse, loudly,
if the off-site destination is not configured — that refusal is the ADR 0034 gate
and not something to work around, because `&& touch` means a backup that only
existed locally would have kept the stamp file fresh and the heartbeat quiet:

```bash
/opt/qoida/qoida-platform/infra/production/run-backup.sh
```

**Check:** the last line reads `Done: qoida-<timestamp>.dump.enc (local and
off-site)`. Anything else, including a non-zero exit with `No off-site
destination is configured`, means there is no backup tonight.

Then, off this machine:

- Configure the dead-man's-switch to alert if it hears nothing for 15 minutes.
- Configure an external HTTP check on
  `https://api.qoida.uz/actuator/health/readiness`, every minute, alerting after
  two consecutive failures.

**Check both by breaking them on purpose.** Stop the application and confirm the
HTTP check pages you. Comment out the cron line and confirm the dead-man's-switch
pages you after fifteen minutes. An alert path nobody has ever seen fire is a
belief, in exactly the way an untested backup is.

---

## 7. Things that are true and easy to forget

- **`compose.yaml` is the development stack and shares nothing with production.**
  Running `docker compose up` in this directory on the server starts a second,
  wrong stack with placeholder credentials. Always `-f compose.production.yaml`.
- **Nothing but 80 and 443 is published.** There is no way to reach PostgreSQL,
  Kafka, MinIO or OpenBao from off the host, and there should never be one. Use
  `qc exec`, or an SSH tunnel if you need a GUI.
- **The application connects as `qoida_app`, which cannot change the schema and
  cannot read a table no migration granted it.** If a query fails with
  `permission denied`, the missing `GRANT` belongs in the migration that created
  the table, not in a manual statement on the server.
- **Money is integer minor units, and for UZS a minor unit is a whole som.**
  If you are reading a value out of the database during an incident, do not
  divide it by 100.
- **Logs must not contain personal data (ADR 0029).** If you find some while
  debugging, that is a bug to file, not a convenience to use.
