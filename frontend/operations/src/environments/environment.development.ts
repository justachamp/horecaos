import type { Environment } from './environment.model';

/**
 * Local development against the platform running from the qoida-platform
 * repository's docker compose.
 *
 * There used to be an `issuer` here too, matching `HORECAOS_OIDC_ISSUER_URI`
 * in the platform's `application.yml`. ADR 0062 removes it along with the
 * redirect flow it configured: this console POSTs credentials to its own
 * `/api/v1/operations/auth/sessions`, and the platform is the only thing
 * that resolves a Keycloak issuer at all now.
 */
export const environment: Environment = {
  production: false,
  apiBaseUrl: '',
};
