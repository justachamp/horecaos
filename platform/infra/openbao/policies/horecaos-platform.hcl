# The policy attached to the application's AppRole (ADR 0028, ADR 0065).
#
# This copy is what platform/infra/production/bootstrap.sh loads byte-for-byte,
# so it names `production` outright; deploy/infra/openbao/policies/ carries the
# @ENVIRONMENT@-rendered twin the runbook, unattended-boot.sh and local-smoke.sh
# load. Keep the two in step.
#
# Read everywhere in the production store; create/update only under the
# provider categories a tenant may hold a credential for; no `delete`, `list`
# or `sudo` anywhere, and nothing outside production. If the AppRole credential
# on the host is stolen, the attacker can read production secrets — which is
# bad — and can add a new version of a provider credential, but cannot destroy
# anything, cannot touch the platform-owned categories (database, object
# storage, identity admin, data encryption), cannot enumerate paths, cannot
# reach staging or local, and cannot see the audit devices that recorded them
# doing it. Rotation after such a theft is therefore a real remedy rather than
# a gesture.

path "horecaos/data/production/*" {
  capabilities = ["read"]
}

# ADR 0065's write-only secret door (Settings > Integrations > Connect provider,
# merchant-binding rotation) POSTs a value once to
# horecaos/data/production/<category>/tenant-<tenantId>/<uuid> under one of the
# tenant-writable categories (SecretCategory.tenantWritable()). KV v2 needs
# `create` for a new path and `update` for a new version. OpenBao applies the
# MOST SPECIFIC matching path, not the union, so `read` is repeated here or the
# resolver would lose the secrets the door just wrote. Platform-owned
# categories deliberately stay read-only.
path "horecaos/data/production/provider_pos/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/production/provider_payment/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/production/provider_delivery/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/production/provider_notification/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/production/provider_voice/*" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/production/provider_marketplace/*" {
  capabilities = ["create", "update", "read"]
}

# Metadata read is needed for nothing the application does today. It is granted
# so that `bao kv get` from an operator shell using this same role behaves, and
# so that a future rotation check can compare versions without a second
# credential. List is deliberately absent: enumerating every secret path is the
# first thing a stolen credential is used for.
path "horecaos/metadata/production/*" {
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
