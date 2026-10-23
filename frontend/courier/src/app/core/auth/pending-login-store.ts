import { Injectable } from '@angular/core';

import type { PendingLogin } from './oidc-client';

const KEY = 'courier.oidc.pending';

/**
 * The one thing that has to survive the trip to the identity provider and back: the PKCE
 * verifier, the `state` and the `nonce` of the login in flight.
 *
 * `sessionStorage`, because it dies with the tab and is not shared between tabs, and because it
 * holds none of the things AGENTS.md says never to persist: no access token, no refresh token,
 * no code. It is single-use -- {@link take} removes it whether or not the login then succeeds, so
 * a verifier cannot be replayed by reloading the callback.
 *
 * Every access is in a try/catch: a browser in a private window, or with site data blocked,
 * throws from `sessionStorage`. `save` says whether it worked, because a login that cannot be
 * resumed must not be started.
 */
@Injectable({ providedIn: 'root' })
export class PendingLoginStore {
  save(pending: PendingLogin): boolean {
    try {
      globalThis.sessionStorage.setItem(KEY, JSON.stringify(pending));
      return true;
    } catch {
      return false;
    }
  }

  take(): PendingLogin | null {
    try {
      const text = globalThis.sessionStorage.getItem(KEY);
      globalThis.sessionStorage.removeItem(KEY);
      if (text === null) {
        return null;
      }
      const raw: unknown = JSON.parse(text);
      return isPendingLogin(raw) ? raw : null;
    } catch {
      return null;
    }
  }

  clear(): void {
    try {
      globalThis.sessionStorage.removeItem(KEY);
    } catch {
      // Nothing was stored that a failed removal could leave behind.
    }
  }
}

function isPendingLogin(value: unknown): value is PendingLogin {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const raw = value as Record<string, unknown>;
  return (
    typeof raw['state'] === 'string' &&
    typeof raw['nonce'] === 'string' &&
    typeof raw['verifier'] === 'string' &&
    typeof raw['returnTo'] === 'string' &&
    typeof raw['createdAt'] === 'number'
  );
}
