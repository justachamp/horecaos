import { InjectionToken, Injectable, inject } from '@angular/core';
import { AbstractSecurityStorage } from 'angular-auth-oidc-client';

/**
 * The browser store used for redirect state.
 *
 * A token rather than a constructor parameter typed `Storage`: Angular resolves
 * constructor parameter *types* through DI, and there is no provider for the DOM
 * `Storage` interface, so a plain parameter fails at runtime with
 * `NG0201: No provider found for Storage` even when a default value is written.
 * The token also gives tests a seam that does not involve a real browser store.
 */
export const SESSION_STORAGE = new InjectionToken<Storage>('horecaos.sessionStorage', {
  providedIn: 'root',
  factory: () => globalThis.sessionStorage,
});

/**
 * Session storage that keeps tokens out of the browser's persistent stores.
 *
 * ADR 0035 requires tokens in memory rather than local storage. The naive
 * reading — put *everything* in a `Map` — does not work, and it fails in a way
 * that looks like a Keycloak misconfiguration rather than a client bug. The
 * Authorization Code flow with PKCE navigates the whole document to Keycloak and
 * back, and a `Map` does not survive a navigation. Losing the PKCE verifier and
 * the `state` nonce turns every single login into `invalid_grant`.
 *
 * So the split is by *what the value is*, not by convenience:
 *
 *   - **Redirect state** — the PKCE verifier, the CSRF `state`, the id-token
 *     nonce, the in-progress flag — goes to `sessionStorage`, because the flow
 *     cannot complete without it. None of it is a bearer credential: a verifier
 *     is single-use, short-lived, and worthless without the matching
 *     authorization code, which never touches storage. `sessionStorage` is
 *     per-tab and dies with the tab.
 *
 *   - **Everything else** — access token, refresh token, id token, user data —
 *     stays in a closure and is never written anywhere a later script, a later
 *     tab, or a forensic tool can read it.
 *
 * **The cost, stated plainly.** A page refresh drops the tokens, so the
 * application re-runs the redirect. Where Keycloak's SSO cookie is still valid
 * that is invisible: Keycloak answers without prompting. Where it is not, the
 * operator gets a login screen after a refresh, during service. That is the
 * deliberate price of not persisting a bearer token in a browser, and it is the
 * reason silent renew is configured to keep the session alive rather than
 * letting it lapse.
 *
 * The library hands this class one key — the config id — whose value is a JSON
 * object holding every inner key. The split therefore happens inside the blob.
 */
@Injectable()
export class SplitSecurityStorage implements AbstractSecurityStorage {
  /**
   * The inner keys that must survive a full-page redirect.
   *
   * Mirrored from `StoragePersistenceService` in angular-auth-oidc-client 22.
   * If the library renames one of these, login breaks — which is why
   * `security-storage.spec.ts` asserts the round trip through a simulated
   * redirect rather than trusting this list to stay correct.
   */
  private static readonly REDIRECT_STATE_KEYS: readonly string[] = [
    'codeVerifier',
    'authStateControl',
    'authNonce',
    'session_state',
    'storageCodeFlowInProgress',
    'storageCustomParamsAuthRequest',
  ];

  private readonly memory = new Map<string, Record<string, unknown>>();
  private readonly session = inject(SESSION_STORAGE);

  read(key: string): string | null {
    const inMemory = this.memory.get(key);
    const persisted = this.readSession(key);
    if (inMemory === undefined && persisted === null) {
      return null;
    }
    return JSON.stringify({ ...(persisted ?? {}), ...(inMemory ?? {}) });
  }

  write(key: string, value: string): void {
    // The library writes `"null"` to mean "drop it", not to mean "store null".
    if (value === null || value === undefined || value === 'null') {
      this.remove(key);
      return;
    }

    const blob = JSON.parse(value) as Record<string, unknown>;
    const redirectState: Record<string, unknown> = {};
    const secrets: Record<string, unknown> = {};

    for (const [innerKey, innerValue] of Object.entries(blob)) {
      if (SplitSecurityStorage.REDIRECT_STATE_KEYS.includes(innerKey)) {
        redirectState[innerKey] = innerValue;
      } else {
        secrets[innerKey] = innerValue;
      }
    }

    this.memory.set(key, secrets);
    if (Object.keys(redirectState).length === 0) {
      this.session.removeItem(sessionKey(key));
    } else {
      this.session.setItem(sessionKey(key), JSON.stringify(redirectState));
    }
  }

  remove(key: string): void {
    this.memory.delete(key);
    this.session.removeItem(sessionKey(key));
  }

  /** Logout clears both halves. Leaving either behind leaves a session behind. */
  clear(): void {
    for (const key of this.memory.keys()) {
      this.session.removeItem(sessionKey(key));
    }
    this.memory.clear();
  }

  private readSession(key: string): Record<string, unknown> | null {
    const raw = this.session.getItem(sessionKey(key));
    if (raw === null) {
      return null;
    }
    try {
      return JSON.parse(raw) as Record<string, unknown>;
    } catch {
      // A corrupt blob is not recoverable and must not wedge every subsequent
      // login attempt. Dropping it costs one extra redirect.
      this.session.removeItem(sessionKey(key));
      return null;
    }
  }
}

function sessionKey(configId: string): string {
  return `horecaos.operations.oidc.${configId}`;
}
