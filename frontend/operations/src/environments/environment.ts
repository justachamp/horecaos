/**
 * Production configuration.
 *
 * Nothing secret goes here and nothing secret can: a browser bundle is public,
 * so a value in this file is a value the world has. The Keycloak client is a
 * public client with PKCE precisely so that no secret is needed (ADR 0003).
 *
 * Baked in at build time, unlike the storefront and control-plane consoles
 * (see deploy/compose.production.yml's `operations-web` note): this file is
 * this application's whole configuration surface today, so the values below
 * must already be the real production ones rather than placeholders a
 * deploy step overwrites. `issuer` matches `HORECAOS_AUTH_ORIGIN` in
 * deploy/env.template — every other part of this platform (the compose
 * stack, the Caddyfile, the production-setup runbook) names that origin
 * `auth.horecaos.uz`, and this file previously named a different host
 * (`id.horecaos.uz`) that nothing in the stack serves; fixed to match
 * (ADR 0061 production-deployment wave) rather than left to fail Keycloak
 * discovery on first login.
 */

import type { Environment } from './environment.model';

export const environment: Environment = {
  production: true,
  apiBaseUrl: '',
  auth: {
    issuer: 'https://auth.horecaos.uz/realms/horecaos',
    clientId: 'horecaos-operations',
    redirectUri: 'https://operations.horecaos.uz/auth/callback',
    postLogoutRedirectUri: 'https://operations.horecaos.uz/',
    scope: 'openid profile email',
  },
};
