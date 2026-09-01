# The HorecaOS operations console — production image.
#
# No runtime configuration layer, unlike storefront and control-plane: this
# application's Keycloak issuer, client id, and redirect URIs are compiled
# into the bundle from src/environments/environment.ts at build time (see
# that file's own header comment). One consequence, worth stating rather
# than discovering during a deploy: promoting this app to a different
# origin or a different Keycloak realm needs a rebuild, not a redeploy --
# unlike its two siblings, this image is not portable across environments
# by itself. Recorded as a follow-up in platform/docs/runbooks/
# production-setup.md and in ADR 0061's implementation report rather than
# fixed here, since giving it the same runtime-config pattern as the other
# two is a real (if small) frontend change outside this task's scope.

FROM node:22-alpine AS build

WORKDIR /app

COPY . .

RUN npm ci

# `ng build`'s default configuration is `production` (angular.json).
RUN npm run build

FROM nginx:alpine

COPY --from=build /app/dist/horecaos-operations/browser /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
