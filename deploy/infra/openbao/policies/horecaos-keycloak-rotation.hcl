# The policy for rotating the realm's confidential client secrets, and nothing
# else. Used by deploy/keycloak-stage2.sh for the minutes a rotation takes, under
# a token minted for it and revoked straight after.
#
# It exists so the root token never has to leave the operator's own shell. Root
# mints this token on the host; only this token enters the ops container that
# talks to Keycloak and OpenBao.
#
# Write, so a rotated secret can be stored. Read, so it can be read back and
# compared before the next one is rotated -- a secret Keycloak holds that
# OpenBao does not is an outage for whatever uses that client. No list, no
# delete, and no path outside the four slots the platform resolves.
#
# @ENVIRONMENT@ is HORECAOS_ENVIRONMENT, filled in by whatever loads this file
# -- the runbook, keycloak-stage2.sh, unattended-boot.sh, local-smoke.sh -- so
# one file serves every environment's store. Loaded without rendering, it grants
# a path nothing uses: it fails closed, never open.

path "horecaos/data/@ENVIRONMENT@/identity_admin/keycloak/provisioning-secret" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/identity_admin/keycloak/reader-secret" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/identity_admin/keycloak/staff-login-secret" {
  capabilities = ["create", "update", "read"]
}

path "horecaos/data/@ENVIRONMENT@/identity_admin/keycloak/device-provisioning-secret" {
  capabilities = ["create", "update", "read"]
}

path "auth/token/revoke-self" {
  capabilities = ["update"]
}
