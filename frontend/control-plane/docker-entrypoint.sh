#!/bin/sh
# Writes /config.js from environment variables before nginx starts, so one
# built image serves any environment it is pointed at -- see `AppConfig`
# (src/app/core/config/app-config.ts) for why this application reads that
# file at runtime rather than compiling an issuer/API origin in.
#
# Defaults match src/app/core/config/app-config.ts's own DEVELOPMENT_DEFAULTS,
# which is what public/config.js already holds for `ng serve`. `docker run`
# with no env vars therefore behaves the same as local dev; a real deployment
# overrides every APP_* variable for its own environment.
set -eu

: "${APP_API_BASE_URL:=http://localhost:8080}"
: "${APP_ISSUER_URL:=http://localhost:8081/realms/horecaos}"
: "${APP_CLIENT_ID:=horecaos-control-plane}"
: "${APP_DISPLAY_TIME_ZONE:=Asia/Tashkent}"

export APP_API_BASE_URL APP_ISSUER_URL APP_CLIENT_ID APP_DISPLAY_TIME_ZONE

TEMPLATE=/etc/control-plane/config.template.js
OUTPUT=/usr/share/nginx/html/config.js

# The explicit variable list stops envsubst substituting every other variable
# in this process's environment into any stray "${...}" the template might
# one day contain.
envsubst '${APP_API_BASE_URL} ${APP_ISSUER_URL} ${APP_CLIENT_ID} ${APP_DISPLAY_TIME_ZONE}' \
  < "$TEMPLATE" > "$OUTPUT"

exec "$@"
