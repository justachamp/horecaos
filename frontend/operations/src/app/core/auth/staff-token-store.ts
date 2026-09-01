import { Injectable } from '@angular/core';

/**
 * This console's own tokens, in memory only (ADR 0035, ADR 0062).
 *
 * Replaces `SplitSecurityStorage`, which existed to keep a PKCE verifier, a
 * CSRF `state`, and an id-token nonce alive across the full-document redirect
 * to Keycloak and back. None of that exists any more: the browser never
 * navigates to Keycloak, so there is no redirect state to survive and nothing
 * that needs `sessionStorage`'s "outlives this document" property. What is
 * left is exactly ADR 0035's own requirement — access and refresh tokens in
 * memory, never in a browser's persistent stores — and nothing else.
 *
 * The cost, stated the way `SplitSecurityStorage` stated it: a page refresh
 * drops the session and the operator sees the sign-in page again. Before ADR
 * 0062 Keycloak's own SSO cookie often made that invisible; now there is no
 * cookie and no redirect for one to ride along with, so every refresh is a
 * sign-in. Named in the ADR's own trade-offs, not an oversight here.
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
