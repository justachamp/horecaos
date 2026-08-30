#!/usr/bin/env bash
#
# First-time setup of the colocated production host. Run once, ever.
#
# Everything this does is irreversible in the sense that matters: it generates
# the unseal key shares and the root token, prints them once, and never stores
# them. If they are lost before they are written down, the only remedy is to
# delete the OpenBao volume and start again — which on a host that already holds
# data means restoring from backup.
#
# Read docs/runbooks/deploy.md, section "Bootstrapping a new host", before
# running this. Do not run it over an existing OpenBao volume.
#
#   sudo QOIDA_ENV_FILE=/etc/qoida/production.env infra/production/bootstrap.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/compose.production.yaml"
ENV_FILE="${QOIDA_ENV_FILE:-/etc/qoida/production.env}"
SECRET_DIR="${QOIDA_SECRET_DIR:-/run/qoida/secrets}"

say()  { printf '\n==> %s\n' "$*"; }
die()  { printf '\n!!  %s\n' "$*" >&2; exit 1; }

compose() {
    docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"
}

[ -r "${ENV_FILE}" ] || die "${ENV_FILE} not readable. Copy infra/production/production.env.example and edit it."

export QOIDA_SECRET_DIR="${SECRET_DIR}"
export QOIDA_IMAGE_TAG="${QOIDA_IMAGE_TAG:-bootstrap}"

# The compose file will not parse without these files existing, and at bootstrap
# time none of them do yet. Placeholders are created and then immediately
# overwritten with real values below; nothing that survives this script is a
# placeholder.
mkdir -p "${SECRET_DIR}"
chmod 0700 "${SECRET_DIR}"


# -----------------------------------------------------------------------------
# 1. OpenBao, initialised and unsealed
# -----------------------------------------------------------------------------

say "Starting OpenBao"
compose up -d openbao
sleep 5

if compose exec -T openbao bao status 2>&1 | grep -q 'Initialized.*true'; then
    die "This OpenBao is already initialised. Bootstrap is a once-per-host
    operation; if you are trying to recover an existing host, you want the
    unseal procedure in docs/runbooks/deploy.md, not this script."
fi

cat <<-'EOF'

	The next command prints five unseal key shares and one root token.

	They are printed once and stored nowhere. Before you press Enter:

	  * Have somewhere to write them that is not this machine and not a file
	    on it. Three of the five shares can unseal OpenBao, so three shares in
	    one place is the same as no encryption at all.
	  * The intended split is: two shares in the operator's password manager,
	    two in a sealed envelope held off-site, one with the business owner.
	  * The root token is used for the next few minutes and then revoked at the
	    end of this script. It is not a login you keep.

	If the terminal scrollback of this session is being recorded anywhere, stop
	now and run this again from a session that is not.

EOF
read -r -p "Press Enter when you are ready to capture the keys. "

say "Initialising OpenBao"
compose exec -T openbao bao operator init -key-shares=5 -key-threshold=3

cat <<-'EOF'

	Copy the shares and the root token out of the terminal now.

EOF
read -r -p "Press Enter once they are safely recorded. "

say "Unseal OpenBao: run the following three times, with three different shares"
printf '    docker compose -f compose.production.yaml exec openbao bao operator unseal\n\n'
read -r -p "Press Enter once 'Sealed' reads false. "

compose exec -T openbao bao status >/dev/null 2>&1 \
    || die "OpenBao is still sealed. Unseal it and run this script again."


# -----------------------------------------------------------------------------
# 2. Mount, policy, AppRole
# -----------------------------------------------------------------------------

printf 'Root token (input hidden): '
read -r -s ROOT_TOKEN
printf '\n'
[ -n "${ROOT_TOKEN}" ] || die "No token given."

bao_run() {
    printf '%s' "${ROOT_TOKEN}" \
        | compose exec -T openbao sh -c 'BAO_TOKEN="$(cat)"; export BAO_TOKEN; "$@"' _ "$@"
}

say "Enabling the qoida KV v2 mount"
bao_run bao secrets enable -path=qoida -version=2 kv 2>/dev/null || true

say "Writing the policies"
# Copied in rather than pasted, so what is active is byte-for-byte the file in
# this repository and can be diffed against it later.
for policy in qoida-platform qoida-deploy; do
    compose cp "${REPO_ROOT}/infra/openbao/policies/${policy}.hcl" \
        "openbao:/tmp/${policy}.hcl"
    bao_run bao policy write "${policy}" "/tmp/${policy}.hcl"
    # Not deleted afterwards: `compose cp` writes as root and the container runs
    # as the unprivileged openbao user, so it cannot remove its own copy. A
    # policy file is public information, and it lives in the container's
    # ephemeral layer, so it goes away with the container.
    printf '    %s\n' "${policy}"
done

say "Enabling AppRole and creating the qoida-platform role"
bao_run bao auth enable approle 2>/dev/null || true
# token_period makes this a periodic token: the agent can renew it forever
# without ever hitting a max TTL, which is exactly what "the application read the
# token once at startup and it must stay valid" requires. An hour is short enough
# that a token stolen from the tmpfs stops working the same morning if the agent
# is stopped.
#
# secret_id_ttl is thirty days as a backstop only. The deploy script issues a new
# one on every deploy, so in practice it is replaced far sooner; the TTL is what
# bounds the damage if nobody deploys for a month.
bao_run bao write auth/approle/role/qoida-platform \
    token_policies=qoida-platform \
    token_ttl=1h \
    token_period=1h \
    secret_id_ttl=720h \
    secret_id_num_uses=0 \
    bind_secret_id=true


# -----------------------------------------------------------------------------
# 3. Generate and store the startup credentials
# -----------------------------------------------------------------------------
#
# Generated inside OpenBao's container with its own CSPRNG and written straight
# to the KV store. No human ever sees them, so no human can leak one, reuse one,
# or choose a weak one. They exist in exactly two places afterwards: the OpenBao
# raft volume, and the tmpfs the deploy script writes at deploy time.

say "Generating the database, Keycloak and MinIO credentials"
# Named `secret_path` rather than `path`: in zsh, `path` is tied to `PATH`, and
# an operator who pastes this loop into an interactive shell would silently lose
# every command on their PATH. It costs nothing to not do that to them.
for secret_path in \
    qoida/production/database/platform/migrator-password \
    qoida/production/database/platform/app-password \
    qoida/production/database/keycloak/password \
    qoida/production/object_storage/platform/root-password
do
    # Generated and consumed entirely inside the OpenBao container, so the value
    # never crosses onto this host: not into a shell variable, not into a log,
    # and not into this host's process table.
    printf '%s' "${ROOT_TOKEN}" | compose exec -T openbao sh -c '
        BAO_TOKEN="$(cat)"; export BAO_TOKEN
        value="$(head -c 32 /dev/urandom | base64 | tr -d "=+/" | head -c 40)"
        bao kv put "$0" value="${value}" > /dev/null
        unset value' "${secret_path}"
    printf '    %s\n' "${secret_path}"
done

cat <<-'EOF'

	The remaining secrets are not generated here, because they come from
	outside OpenBao and have to be created or copied by hand:

	  qoida/production/data_encryption/platform/kek
	      The ADR 0029 key-encryption key. Generate it, store it, and keep a
	      sealed offline copy: losing it makes every encrypted personal-data
	      column unreadable, permanently.

	  qoida/production/identity_admin/keycloak/provisioning-secret
	  qoida/production/identity_admin/keycloak/reader-secret
	      Copied from the Keycloak clients after the realm is imported.

	  qoida/production/object_storage/platform/media-access-key
	  qoida/production/object_storage/platform/media-secret-key
	      A MinIO service account scoped to the media bucket. Not the root
	      credential: the application should not be able to delete the backups.

	The backup passphrase belongs in
	  qoida/production/data_encryption/platform/backup-passphrase
	and in a sealed envelope somewhere other than the server room. It must not be
	stored in the same place as the backups themselves.

	docs/runbooks/deploy.md, "Bootstrapping a new host", walks through each.

EOF


# -----------------------------------------------------------------------------
# 4. Give the root token back
# -----------------------------------------------------------------------------

say "Revoking the root token"
# A root token that outlives its purpose is the single most valuable credential
# on the host and has no owner. A new one can be generated from three unseal
# shares whenever it is genuinely needed, which is the point of the shares.
bao_run bao token revoke -self || true
unset ROOT_TOKEN

cat <<-'EOF'

	Bootstrap complete. Next:

	  1. Store the remaining secrets listed above.
	  2. Create an operator login (userpass or OIDC) with the qoida-platform
	     policy plus whatever it needs to write secrets. The deploy script asks
	     for that token, not for a root one.
	  3. Run infra/production/deploy.sh.

EOF
