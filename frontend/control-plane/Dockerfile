# The HorecaOS control-plane console — production image.
#
# Same shape as frontend/storefront/Dockerfile: a Node build stage produces
# the static bundle, an nginx stage serves it, and a small entrypoint
# renders runtime configuration from environment variables before nginx
# starts (see config.template.js and docker-entrypoint.sh). One image runs
# in every environment; nothing about it is tenant- or environment-specific
# until the container starts.

FROM node:22-alpine AS build

WORKDIR /app

COPY . .

RUN npm ci

# `ng build`'s default configuration is `production` (angular.json) — see
# check-tokens in package.json's own `verify` script for what else that
# implies. No `--configuration` flag needed here.
RUN npm run build

FROM nginx:alpine

# envsubst (from gettext) is what the entrypoint uses to write config.js
# from environment variables at container start.
RUN apk add --no-cache gettext

COPY --from=build /app/dist/control-plane/browser /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY config.template.js /etc/control-plane/config.template.js
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 80

# APP_API_BASE_URL, APP_ISSUER_URL, APP_CLIENT_ID, APP_DISPLAY_TIME_ZONE --
# see docker-entrypoint.sh for what each defaults to when unset.
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
