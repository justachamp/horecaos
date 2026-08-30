/**
 * Production configuration.
 *
 * Nothing secret goes here and nothing secret can: a browser bundle is public,
 * so a value in this file is a value the world has. The Keycloak client is a
 * public client with PKCE precisely so that no secret is needed (ADR 0003).
 *
 * The production values are placeholders on purpose. They are overwritten at
 * deploy time by the environment the application is deployed into; committing a
 * real production issuer here would make this repository the source of truth for
 * a value that operations owns.
 */

import type { Environment } from './environment.model';

export const environment: Environment = {
  production: true,
  apiBaseUrl: '',
  auth: {
    issuer: 'https://id.qoida.uz/realms/qoida',
    clientId: 'qoida-operations',
    redirectUri: 'https://operations.qoida.uz/auth/callback',
    postLogoutRedirectUri: 'https://operations.qoida.uz/',
    scope: 'openid profile email',
  },
};
