# The policy the deploy operator's own login carries.
#
# Separate from `horecaos-platform.hcl` because the two need different things and
# conflating them would give the always-running agent the ability to mint its own
# credentials — which is most of what an attacker who reached the agent would
# want.
#
# This one is used by a human, interactively, for the few minutes a deploy takes.
# It can read the startup secrets and issue an AppRole secret-id. It cannot write
# secrets, cannot change policy, cannot unseal, and cannot generate a root token:
# those need three unseal shares, which are not on the machine.
#
# Read-only is enough for a routine deploy, and deliberately not enough for the
# one that provisions the object-store service accounts (infra/production/
# deploy.sh, Phase 6a): storing a minted media or backup key is a KV write. A
# deploy that would mint asks OpenBao what its token may do BEFORE it asks the
# object store for anything, and stops with the four paths named if the token
# cannot create and update them. That deploy needs a token that carries write
# access to those paths as well (bootstrap.sh's closing note), not this policy
# alone -- this file is not widened for it.

# Reading the four startup passwords, to write them onto the deploy tmpfs.
path "horecaos/data/production/*" {
  capabilities = ["read"]
}

path "horecaos/metadata/production/*" {
  capabilities = ["read", "list"]
}

# Issuing a fresh secret-id for the agent on every deploy, and reading the
# role-id that goes with it. `create` and `update` on the secret-id endpoint and
# nothing else on the role: this cannot change the role's policy, its token TTL,
# or its bindings.
path "auth/approle/role/horecaos-platform/secret-id" {
  capabilities = ["create", "update"]
}

path "auth/approle/role/horecaos-platform/role-id" {
  capabilities = ["read"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}

# deploy.sh asks what this token may do before it mints anything. The default
# policy grants the same; naming it here keeps that check working for a token
# created without the default policy, and grants nothing beyond a question about
# the token's own capabilities.
path "sys/capabilities-self" {
  capabilities = ["update"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}
