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

# Copy built assets from build stage
COPY --from=build /app/dist/qoida-storefront-jizbiz/browser /usr/share/nginx/html

# Copy nginx config for SPA routing
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
