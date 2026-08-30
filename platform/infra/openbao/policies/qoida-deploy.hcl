# The policy the deploy operator's own login carries.
#
# Separate from `qoida-platform.hcl` because the two need different things and
# conflating them would give the always-running agent the ability to mint its own
# credentials — which is most of what an attacker who reached the agent would
# want.
#
# This one is used by a human, interactively, for the few minutes a deploy takes.
# It can read the startup secrets and issue an AppRole secret-id. It cannot write
# secrets, cannot change policy, cannot unseal, and cannot generate a root token:
# those need three unseal shares, which are not on the machine.

# Reading the four startup passwords, to write them onto the deploy tmpfs.
path "qoida/data/production/*" {
  capabilities = ["read"]
}

path "qoida/metadata/production/*" {
  capabilities = ["read", "list"]
}

# Issuing a fresh secret-id for the agent on every deploy, and reading the
# role-id that goes with it. `create` and `update` on the secret-id endpoint and
# nothing else on the role: this cannot change the role's policy, its token TTL,
# or its bindings.
path "auth/approle/role/qoida-platform/secret-id" {
  capabilities = ["create", "update"]
}

path "auth/approle/role/qoida-platform/role-id" {
  capabilities = ["read"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}
