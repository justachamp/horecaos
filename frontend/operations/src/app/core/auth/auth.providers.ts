import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { AbstractSecurityStorage, LogLevel, provideAuth } from 'angular-auth-oidc-client';

import { environment } from '../../../environments/environment';
import { SplitSecurityStorage } from './security-storage';

/**
 * Authorization Code with PKCE against the HorecaOS Keycloak realm
 * (ADR 0003, ADR 0035).
 *
 * **What cannot be verified from this repository.** The realm is not reachable
 * from a developer machine without the platform's docker compose running, and it
 * is not reachable from CI at all. Everything below that depends on Keycloak's
 * actual configuration — that `horecaos-operations` exists as a *public* client,
 * that PKCE S256 is required on it, that the redirect URI is allowlisted
 * exactly, that the audience mapper puts the API in `aud` — is asserted here and
 * proven nowhere. The first person with a running realm should check those five
 * things before believing this file. The tests in `auth.guard.spec.ts` prove the
 * guard's behaviour, not the handshake.
 *
 * **No `withAppInitializerAuthCheck()`, deliberately.** That feature builds
 * `OidcSecurityService` from an `APP_INITIALIZER`, and `OidcSecurityService`
 * injects the `Router`, whose own initializer is running at the same moment —
 * Angular reports `NG0200: Circular dependency detected for OidcSecurityService`
 * and the application renders nothing. This was observed, not theorised.
 *
 * Nothing is lost by dropping it, because of the storage decision: tokens are
 * never persisted, so a cold page load has no session to restore. The only thing
 * a load ever has to process is an authorization callback, and that has its own
 * route outside the guard, which calls `checkAuth()` itself.
 */
export function provideHorecaOSAuth(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideAuth({
      config: {
        configId: 'horecaos-operations',
        authority: environment.auth.issuer,
        clientId: environment.auth.clientId,
        redirectUrl: environment.auth.redirectUri,
        postLogoutRedirectUri: environment.auth.postLogoutRedirectUri,
        scope: environment.auth.scope,

        // Authorization Code. Never `id_token token`: implicit puts an access
        // token in a URL fragment, which lands in history and in referrers.
        responseType: 'code',

        // PKCE is on by default in this library and this line exists to make the
        // ADR 0003 requirement greppable. A public client without PKCE is an
        // authorization code interceptable by any application registered for the
        // redirect scheme.
        disablePkce: false,

        // Refresh tokens rather than an iframe. Keycloak's default
        // `SameSite=Lax` cookies make the iframe silent-renew fail in a way that
        // only shows up in browsers with third-party cookie restrictions — which
        // is now all of them.
        silentRenew: true,
        useRefreshToken: true,
        // Renew before expiry rather than on failure, so an operator mid-approval
        // never eats a 401 and a re-login. Keycloak's default access token
        // lifetime is 5 minutes; 60 seconds of headroom covers a slow network.
        renewTimeBeforeTokenExpiresInSeconds: 60,

        // The library warns when `offline_access` is absent. It is absent
        // deliberately: an offline refresh token outlives the SSO session and
        // this console has no reason to hold one.
        disableRefreshTokenOfflineAccessScopeWarning: true,

        // The issuer must match the token's `iss` exactly. Turning this off is
        // how a token minted by another realm gets accepted.
        issValidationOff: false,

        // The authorization response is stripped from the address bar after the
        // callback, so an authorization code never sits in browser history.
        historyCleanupOff: false,

        // Attach the bearer only to the platform's own origin. A wildcard here
        // would send the token to any host this application ever calls,
        // including a map tile server.
        secureRoutes: [`${environment.apiBaseUrl}/api/v1/`],

        logLevel: environment.production ? LogLevel.Error : LogLevel.Warn,
      },
    }),
    // Tokens never reach localStorage. See security-storage.ts for what this
    // costs and why it is still the right trade.
    { provide: AbstractSecurityStorage, useClass: SplitSecurityStorage },
  ]);
}
