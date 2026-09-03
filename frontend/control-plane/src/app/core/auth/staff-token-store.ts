import { Injectable } from '@angular/core';

/** Where the refresh token is persisted. See this class's own doc for why here and not `localStorage`. */
export const REFRESH_TOKEN_KEY = 'horecaos.control-plane.refreshToken';

/**
 * The console's own tokens (ADR 0035, ADR 0062).
 *
 * The access token stays exactly where it always has: in memory only, gone
 * the instant the page unloads. It is short-lived, every request needs it,
 * and there is no reason to write something that powerful anywhere a script
 * running later in the same tab could find at rest.
 *
 * The refresh token now lives in `sessionStorage`, which it did not
 * before. The previous, memory-only version of this class was ADR 0035's
 * letter but not its point — its own doc named the actual cost plainly: "a
 * reload drops the session and the console shows the sign-in page again."
 * That was not a deliberate security boundary, it was the leftover shape of
 * a class built to hold PKCE/nonce redirect state ADR 0062 then deleted the
 * need for. In practice it meant F5, a pasted deep link, or a laptop woken
 * from sleep after the tab's own memory state was gone all dumped a mid-shift
 * operator back to `/login`, losing whatever screen they were on.
 *
 * `sessionStorage` fixes exactly that and nothing more: it survives a reload
 * and a deep link, because it is keyed to the tab rather than to the
 * in-memory heap, and it dies with the tab, because that is what keeps it
 * short of `localStorage`. `localStorage` would survive the *browser*
 * closing and reopening — so a stolen laptop, rebooted days later, could
 * still resurrect a staff session from disk. `sessionStorage` cannot: close
 * the tab, or the browser, and the console asks for a password again exactly
 * as it always did. Nothing here changes how long a *live* session can run
 * unattended — that is Keycloak's refresh-token lifetime, set server-side —
 * only whether a page load has to throw the session away for no reason.
 */
@Injectable({ providedIn: 'root' })
export class StaffTokenStore {
  private access: string | null = null;

  accessToken(): string | null {
    return this.access;
  }

  /** Reads straight from `sessionStorage` on every call rather than caching, so a second tab's own write is never shadowed by a stale in-memory copy. */
  refreshToken(): string | null {
    try {
      return globalThis.sessionStorage?.getItem(REFRESH_TOKEN_KEY) ?? null;
    } catch {
      // Storage can be unavailable — a locked-down kiosk profile, or a
      // private window with quota exhausted. Behaves the same as never
      // having had a session, which is the safe direction to fail in.
      return null;
    }
  }

  set(accessToken: string, refreshToken: string): void {
    this.access = accessToken;
    try {
      globalThis.sessionStorage?.setItem(REFRESH_TOKEN_KEY, refreshToken);
    } catch {
      // If the write fails, refreshToken() reports null on every later read —
      // the same "does not survive a reload" outcome this class always had,
      // rather than a thrown error out of what looks like a plain setter.
    }
  }

  clear(): void {
    this.access = null;
    try {
      globalThis.sessionStorage?.removeItem(REFRESH_TOKEN_KEY);
    } catch {
      // Nothing more to do — the in-memory half is already gone either way.
    }
  }
}
