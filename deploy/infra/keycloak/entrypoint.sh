#!/bin/bash
#
# Keycloak entrypoint that reads the database password from a file.
#
# This exists because Keycloak 26.7 does not honour a `KC_DB_PASSWORD_FILE`
# environment variable, despite the `_FILE` convention being near-universal among
# the other images in this stack. It was tried; the server starts, fails to
# authenticate to PostgreSQL, and reports "the server requested SCRAM-based
# authentication, but no password was provided" — which does not obviously read
# as "your file was ignored".
#
# Keycloak's own answer is a config keystore (`--config-keystore`), which moves
# the problem rather than solving it: the keystore needs a password of its own,
# and that password would have to be an environment variable in the compose file.
#
# So the password is read here, exported into the process environment, and
# handed to a JVM that replaces this shell. It never appears in the compose file,
# never in `docker inspect`, and never in the image.

set -euo pipefail

password_file=/run/secrets/keycloak-db-password

if [ -r "${password_file}" ]; then
    KC_DB_PASSWORD="$(cat "${password_file}")"
    export KC_DB_PASSWORD
else
    echo "!! ${password_file} is not readable; Keycloak will fail to reach its database." >&2
    exit 1
fi

# `exec` so that kc.sh becomes pid 1 and receives SIGTERM directly. Keycloak
# takes several seconds to shut down cleanly, and a shell in between turns that
# into a SIGKILL and a Quarkus datasource left holding connections.
exec /opt/keycloak/bin/kc.sh "$@"
