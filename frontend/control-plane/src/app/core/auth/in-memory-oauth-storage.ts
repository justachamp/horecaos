import { Injectable } from '@angular/core';
import { OAuthStorage } from 'angular-oauth2-oidc';

/**
 * Tokens in memory; the redirect handshake in session storage.
 *
 * ADR 0035 says tokens live in memory rather than local storage, and that is
 * about tokens. It cannot be about everything the OIDC library stores, because
 * the PKCE code verifier and the nonce are written *before* the browser
 * navigates away to Keycloak and read *after* it comes back — a different
 * document, with a different heap. Keeping those in memory does not make the
 * login flow more secure; it makes it fail every time.
 *
 * So the storage is partitioned. Two transient handshake values go to
 * `sessionStorage`, scoped to the tab and cleared when it closes. Everything
 * else — access token, refresh token, id token, claims, expiry — stays in a
 * `Map` that dies with the page.
 *
 * The cost is that a reload loses the session and the application redirects to
 * Keycloak again. With the realm's SSO cookie present that round trip is
 * invisible. Without it the user signs in again, which is the correct outcome
 * for a console that administers other people's businesses.
 */
const HANDSHAKE_KEYS: ReadonlySet<string> = new Set(['PKCE_verifier', 'nonce']);

@Injectable()
export class InMemoryOAuthStorage implements OAuthStorage {
  private readonly tokens = new Map<string, string>();

  getItem(key: string): string | null {
    if (HANDSHAKE_KEYS.has(key)) {
      return sessionStorage.getItem(key);
    }
    return this.tokens.get(key) ?? null;
  }

  setItem(key: string, data: string): void {
    if (HANDSHAKE_KEYS.has(key)) {
      sessionStorage.setItem(key, data);
      return;
    }
    this.tokens.set(key, data);
  }

  removeItem(key: string): void {
    if (HANDSHAKE_KEYS.has(key)) {
      sessionStorage.removeItem(key);
      return;
    }
    this.tokens.delete(key);
  }
}
