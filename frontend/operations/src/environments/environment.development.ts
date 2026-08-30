import type { Environment } from './environment.model';

/**
 * Local development against the platform running from the qoida-platform
 * repository's docker compose.
 *
 * The issuer matches `HORECAOS_OIDC_ISSUER_URI` in the platform's application.yml,
 * whose default is `http://localhost:8081/realms/horecaos`. If the two disagree,
 * every token this application obtains is rejected by the API with a 401 that
 * says nothing about why.
 */
export const environment: Environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8080',
  auth: {
    issuer: 'http://localhost:8081/realms/horecaos',
    clientId: 'horecaos-operations',
    redirectUri: 'http://localhost:4200/auth/callback',
    postLogoutRedirectUri: 'http://localhost:4200/',
    scope: 'openid profile email',
  },
};
