# Build stage
FROM node:22-alpine AS build

WORKDIR /app

# Copy source and config
COPY . .

# Install dependencies
RUN npm ci

# Build for production
RUN npm run build

# Serve stage
FROM nginx:alpine

# envsubst (from gettext) is what the entrypoint uses to write config.json
# from environment variables at container start -- see docker-entrypoint.sh.
RUN apk add --no-cache gettext

# Copy built assets from build stage
COPY --from=build /app/dist/qoida-storefront-jizbiz/browser /usr/share/nginx/html

# Copy nginx config for SPA routing
COPY nginx.conf /etc/nginx/conf.d/default.conf

# The template config.json is generated from, and the entrypoint that does the
# generating. Runtime config templating: one image, any tenant -- the
# APP_* environment variables below are read at container start, not baked in.
COPY config.template.json /etc/storefront/config.template.json
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 80

# APP_API_BASE_URL, APP_TENANT_ID, APP_BRAND_ID, APP_DEFAULT_LOCATION_ID,
# APP_CHANNEL, APP_YANDEX_MAPS_API_KEY -- see docker-entrypoint.sh for what
# each defaults to when unset.
ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
