# The policy attached to the application's AppRole (ADR 0028).
#
# Read-only, production-only, and no `delete`, `create`, `update` or `sudo`
# anywhere. If the AppRole credential on the host is stolen, the attacker can
# read production secrets — which is bad — but cannot overwrite them, cannot
# destroy them, cannot reach staging or local, and cannot see the audit devices
# that recorded them doing it. Rotation after such a theft is therefore a real
# remedy rather than a gesture.

path "qoida/data/production/*" {
  capabilities = ["read"]
}

# Metadata read is needed for nothing the application does today. It is granted
# so that `bao kv get` from an operator shell using this same role behaves, and
# so that a future rotation check can compare versions without a second
# credential. List is deliberately absent: enumerating every secret path is the
# first thing a stolen credential is used for.
path "qoida/metadata/production/*" {
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
