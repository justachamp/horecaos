# What an unattended restart may do, and nothing else (ADR 0080).
#
# Held by the horecaos-boot AppRole, whose secret-id is sealed to this host's
# TPM and handed by systemd to horecaos-boot.service alone. Because it sits on
# the machine, it is narrower than horecaos-deploy, which a person holds and
# which reads every secret in this environment: this reads the four the stack needs
# before the agent can run -- by exact path, not by prefix -- and issues the
# agent's credential. It cannot read anything else in the store, and it cannot
# issue a secret-id for its own role.
#
# The role binds both its secret-id and its tokens to 127.0.0.1/32. Every call
# the restart makes runs inside the openbao container, where BAO_ADDR is
# loopback; the same credential presented from any other container is refused.
#
# @ENVIRONMENT@ is HORECAOS_ENVIRONMENT, filled in by whatever loads this file
# -- the runbook, keycloak-stage2.sh, unattended-boot.sh, local-smoke.sh -- so
# one file serves every environment's store. Loaded without rendering, it grants
# a path nothing uses: it fails closed, never open.

path "horecaos/data/@ENVIRONMENT@/database/platform/migrator-password" {
  capabilities = ["read"]
}

path "horecaos/data/@ENVIRONMENT@/database/platform/app-password" {
  capabilities = ["read"]
}

path "horecaos/data/@ENVIRONMENT@/database/keycloak/password" {
  capabilities = ["read"]
}

path "horecaos/data/@ENVIRONMENT@/object_storage/platform/root-password" {
  capabilities = ["read"]
}

path "auth/approle/role/horecaos-platform/secret-id" {
  capabilities = ["create", "update"]
}

path "auth/approle/role/horecaos-platform/role-id" {
  capabilities = ["read"]
}
