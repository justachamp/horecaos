import { Injectable } from '@angular/core';

/**
 * The console's own tokens, in memory only (ADR 0035, ADR 0062).
 *
 * Replaces `InMemoryOAuthStorage`, which existed to satisfy a library that
 * needed a `sessionStorage` half for a redirect handshake this console no
 * longer performs at all: there is no PKCE verifier, no nonce, nothing that
 * has to survive a navigation to Keycloak and back, because the browser never
 * navigates there. What is left is exactly the thing ADR 0035 asked for and
 * nothing else — a `Map`-shaped holder that dies with the page.
 *
 * The cost, stated the same way `InMemoryOAuthStorage` stated it: a reload
 * drops the session and the console shows the sign-in page again. Before ADR
 * 0062 that round trip through Keycloak's own SSO cookie was often invisible;
 * now there is no cookie and no redirect to be invisible, so every reload is a
 * sign-in. That loss is named in the ADR's own trade-offs, not an oversight
 * here.
 */
@Injectable({ providedIn: 'root' })
export class StaffTokenStore {
  private access: string | null = null;
  private refresh: string | null = null;

  accessToken(): string | null {
    return this.access;
  }

  refreshToken(): string | null {
    return this.refresh;
  }

  set(accessToken: string, refreshToken: string): void {
    this.access = accessToken;
    this.refresh = refreshToken;
  }

  clear(): void {
    this.access = null;
    this.refresh = null;
  }
}
