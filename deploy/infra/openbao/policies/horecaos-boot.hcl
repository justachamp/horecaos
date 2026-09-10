# What an unattended restart may do, and nothing else (ADR 0080).
#
# Held by the horecaos-boot AppRole, whose secret-id is sealed to this host's
# TPM and handed by systemd to horecaos-boot.service alone. Because it sits on
# the machine, it is narrower than horecaos-deploy, which a person holds and
# which reads every production secret: this reads the four the stack needs
# before the agent can run -- by exact path, not by prefix -- and issues the
# agent's credential. It cannot read anything else in the store, and it cannot
# issue a secret-id for its own role.
#
# The role binds both its secret-id and its tokens to 127.0.0.1/32. Every call
# the restart makes runs inside the openbao container, where BAO_ADDR is
# loopback; the same credential presented from any other container is refused.

path "horecaos/data/production/database/platform/migrator-password" {
  capabilities = ["read"]
}

path "horecaos/data/production/database/platform/app-password" {
  capabilities = ["read"]
}

path "horecaos/data/production/database/keycloak/password" {
  capabilities = ["read"]
}

path "horecaos/data/production/object_storage/platform/root-password" {
  capabilities = ["read"]
}

path "auth/approle/role/horecaos-platform/secret-id" {
  capabilities = ["create", "update"]
}

path "auth/approle/role/horecaos-platform/role-id" {
  capabilities = ["read"]
}
