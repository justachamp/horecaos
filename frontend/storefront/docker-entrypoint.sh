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

# Brand identity (AppConfig.brand -- see src/app/core/config/app-config.ts).
# Defaults are the same neutral identity `load-config.ts` falls back to when
# "brand" is absent from config.json entirely, so a container started with
# none of these set looks deliberately generic rather than quietly wearing
# whatever tenant these defaults last happened to be. APP_BRAND_LOGO_URL
# defaults to empty, which load-config.ts treats the same as "not set" --
# the mark renders as text instead of a broken <img>.
: "${APP_BRAND_DISPLAY_NAME:=Storefront}"
: "${APP_BRAND_LOGO_URL:=}"
: "${APP_BRAND_ACCENT:=#52525b}"
: "${APP_BRAND_ACCENT_DEEP:=#3f3f46}"

export APP_API_BASE_URL APP_TENANT_ID APP_BRAND_ID APP_DEFAULT_LOCATION_ID APP_CHANNEL APP_YANDEX_MAPS_API_KEY \
  APP_BRAND_DISPLAY_NAME APP_BRAND_LOGO_URL APP_BRAND_ACCENT APP_BRAND_ACCENT_DEEP

TEMPLATE=/etc/storefront/config.template.json
OUTPUT=/usr/share/nginx/html/config.json

# The explicit variable list is what stops envsubst substituting every other
# variable in this process's environment (PATH, HOME, and so on) into any
# stray "${...}" it might find -- there is none in this template today, but a
# future edit to it should not turn into an accidental leak.
envsubst '${APP_API_BASE_URL} ${APP_TENANT_ID} ${APP_BRAND_ID} ${APP_DEFAULT_LOCATION_ID} ${APP_CHANNEL} ${APP_YANDEX_MAPS_API_KEY} ${APP_BRAND_DISPLAY_NAME} ${APP_BRAND_LOGO_URL} ${APP_BRAND_ACCENT} ${APP_BRAND_ACCENT_DEEP}' \
  < "$TEMPLATE" > "$OUTPUT"

exec "$@"
