# The policy attached to the application's AppRole (ADR 0028, ADR 0065).
#
# Read everywhere in this environment's store; create/update only under the
# provider categories a tenant may hold a credential for; no `delete`, `list`
# or `sudo` anywhere, and nothing outside this environment. If the AppRole
# credential on the host is stolen, the attacker can read this environment's
# secrets — which is bad — and can add a new version of a provider credential,
# but cannot destroy anything, cannot touch the platform-owned categories
# (database, object storage, identity admin, data encryption), cannot
# enumerate paths, cannot reach another environment's store, and cannot see
# the audit devices that recorded them doing it. Rotation after such a theft
# is therefore a real remedy rather than a gesture.
#
# @ENVIRONMENT@ is HORECAOS_ENVIRONMENT, filled in by whatever loads this file
# -- the runbook, keycloak-stage2.sh, unattended-boot.sh, local-smoke.sh -- so
# one file serves every environment's store. Loaded without rendering, it grants
# a path nothing uses: it fails closed, never open.

path "horecaos/data/@ENVIRONMENT@/*" {
  capabilities = ["read"]
}

# ADR 0065's write-only secret door. Settings > Integrations > Connect provider
# (OperationsSecretIngressController -> SecretIngressGateway -> OpenBaoSecretWriter)
# mints a fresh, platform-generated reference under one of the tenant-writable
# categories (SecretCategory.tenantWritable()) and POSTs the value once to
# horecaos/data/<env>/<category>/tenant-<tenantId>/<uuid>; merchant-binding
# rotation writes the same way. KV v2 needs `create` for a new path and `update`
# for a new version of an existing one. OpenBao applies the MOST SPECIFIC
# matching path, not the union of all matches, so each block below must repeat
# `read` or the resolver would lose the very secrets the door just wrote.
# Without these blocks the door fails closed: the console shows "Something went
# wrong" and platform-app logs SecretWriteFailedException (pre-production,
# 2026-09-16). The platform-owned categories deliberately stay read-only.
path "horecaos/data/@ENVIRONMENT@/provider_pos/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/provider_payment/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/provider_delivery/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/provider_notification/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/provider_voice/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/provider_marketplace/*" {
  capabilities = ["create", "update", "read"]
}

# Metadata read is needed for nothing the application does today. It is granted
# so that `bao kv get` from an operator shell using this same role behaves, and
# so that a future rotation check can compare versions without a second
# credential. List is deliberately absent: enumerating every secret path is the
# first thing a stolen credential is used for.
path "horecaos/metadata/@ENVIRONMENT@/*" {
  capabilities = ["read"]
}

# Token self-renewal. Without this the agent authenticates successfully and then
# watches its own token expire.
path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
