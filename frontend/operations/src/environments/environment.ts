/**
 * Production configuration.
 *
 * Nothing secret goes here and nothing secret can: a browser bundle is
 * public, so a value in this file is a value the world has. That guarantee
 * used to matter twice over — the Keycloak client was a public client with
 * PKCE precisely because no secret could be kept here. ADR 0062 removes the
 * Keycloak client from this bundle's configuration surface entirely: this
 * application no longer holds an issuer, a client id, or a redirect URI,
 * because it no longer talks to Keycloak at all. The platform backend does,
 * over a confidential client this bundle never sees (ADR 0028).
 *
 * Baked in at build time, unlike the storefront and control-plane consoles
 * (see deploy/compose.production.yml's `operations-web` note): this file is
 * this application's whole configuration surface today, so the value below
 * must already be the real production one rather than a placeholder a
 * deploy step overwrites.
 */

import type { Environment } from './environment.model';

export const environment: Environment = {
  production: true,
  apiBaseUrl: '',
};
