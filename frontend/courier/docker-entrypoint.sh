#!/bin/sh
# Writes /config.json from environment variables before nginx starts, so one built image serves
# any environment it is pointed at -- see `AppConfig` (src/app/core/config/app-config.ts) for why
# the app reads that file at runtime rather than compiling an issuer or a tenant in.
#
# Defaults match public/config.json, which is what `ng serve` reads: `docker run` with no
# environment variables therefore behaves like local development, and a real deployment overrides
# every APP_* variable for its own environment. APP_TENANT_ID has the platform's local fixture
# tenant as its default; a deployment that forgets to set it points its couriers at that tenant
# and every /me answers 404 ("this account is not a courier here"), which is loud, not silent.
set -eu

: "${APP_API_BASE_URL:=/api/v1}"
: "${APP_ISSUER_URL:=http://localhost:8081/realms/horecaos}"
: "${APP_CLIENT_ID:=horecaos-courier}"
: "${APP_TENANT_ID:=10000000-0000-0000-0000-000000000001}"
: "${APP_DISPLAY_TIME_ZONE:=Asia/Tashkent}"

export APP_API_BASE_URL APP_ISSUER_URL APP_CLIENT_ID APP_TENANT_ID APP_DISPLAY_TIME_ZONE

TEMPLATE=/etc/courier/config.template.json
OUTPUT=/usr/share/nginx/html/config.json

# The explicit variable list stops envsubst substituting every other variable in this process's
# environment into any stray "${...}" the template might one day contain.
envsubst '${APP_API_BASE_URL} ${APP_ISSUER_URL} ${APP_CLIENT_ID} ${APP_TENANT_ID} ${APP_DISPLAY_TIME_ZONE}' \
  < "$TEMPLATE" > "$OUTPUT"

exec "$@"
