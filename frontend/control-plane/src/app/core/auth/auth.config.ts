import { AuthConfig } from 'angular-oauth2-oidc';

import { AppConfig } from '../config/app-config';

/**
 * Authorization Code with PKCE against the HorecaOS realm (ADR 0003, ADR 0035).
 *
 * Not the implicit flow, which returns tokens in a URL fragment that lands in
 * browser history and in any referrer a page leaks. Not a confidential client,
 * because a browser application cannot keep a secret and pretending otherwise
 * only moves the secret into a bundle anyone can read.
 */
export function buildAuthConfig(config: AppConfig): AuthConfig {
  return {
    issuer: config.issuerUrl,
    clientId: config.clientId,
    responseType: 'code',

    /**
     * Exactly one redirect URI, and it is an allowlisted exact match in the
     * Keycloak client. A wildcard redirect turns any open redirect on the
     * origin into a token exfiltration route.
     */
    redirectUri: `${window.location.origin}/`,
    postLogoutRedirectUri: `${window.location.origin}/`,

    /**
     * `openid` for the subject, `profile` for a display name, and
     * `offline_access` so the refresh token exists at all. No capability is
     * requested as a scope: capabilities come from the session-context
     * endpoint, because a role name in a token is not an authorization
     * decision and ADR 0025 says so.
     */
    scope: 'openid profile offline_access',

    /** PKCE. S256 is the library's default and the only challenge Keycloak should accept. */
    disablePKCE: false,

    /**
     * The library defaults to sending the client id in the body for public
     * clients, which is what a Keycloak public client expects.
     */
    requireHttps: config.issuerUrl.startsWith('https://'),

    /** Refresh a minute early so an in-flight request is never the one that discovers expiry. */
    clockSkewInSec: 60,

    /** Nothing about a token, a claim, or a subject reaches the console (ADR 0029). */
    showDebugInformation: false,

    /**
     * The authorization code and state are stripped from the address bar once
     * exchanged, so a copied URL cannot be replayed and history holds nothing.
     */
    clearHashAfterLogin: true,
  };
}
