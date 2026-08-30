#!/bin/sh
# Writes /config.json from environment variables before nginx starts, so one
# built image serves any tenant/brand this container is pointed at -- see
# `AppConfig` (src/app/core/config/app-config.ts) for why the storefront reads
# this file at runtime rather than compiling a tenant in.
#
# Defaults match the platform's own local fixture tenant (see
# platform/docs/local-fixtures.md), which is what `public/config.json` already
# holds for `ng serve`. `docker run` with no env vars therefore behaves the
# same as local dev; a real deployment overrides every APP_* variable for its
# own tenant.
set -eu

: "${APP_API_BASE_URL:=/api/v1}"
: "${APP_TENANT_ID:=10000000-0000-0000-0000-000000000001}"
: "${APP_BRAND_ID:=10000000-0000-0000-0000-000000000002}"
: "${APP_DEFAULT_LOCATION_ID:=10000000-0000-0000-0000-000000000003}"
: "${APP_CHANNEL:=STOREFRONT}"
: "${APP_YANDEX_MAPS_API_KEY:=99847472-f185-464c-b2cb-7b28dd285a8c}"

export APP_API_BASE_URL APP_TENANT_ID APP_BRAND_ID APP_DEFAULT_LOCATION_ID APP_CHANNEL APP_YANDEX_MAPS_API_KEY

TEMPLATE=/etc/storefront/config.template.json
OUTPUT=/usr/share/nginx/html/config.json

# The explicit variable list is what stops envsubst substituting every other
# variable in this process's environment (PATH, HOME, and so on) into any
# stray "${...}" it might find -- there is none in this template today, but a
# future edit to it should not turn into an accidental leak.
envsubst '${APP_API_BASE_URL} ${APP_TENANT_ID} ${APP_BRAND_ID} ${APP_DEFAULT_LOCATION_ID} ${APP_CHANNEL} ${APP_YANDEX_MAPS_API_KEY}' \
  < "$TEMPLATE" > "$OUTPUT"

exec "$@"
