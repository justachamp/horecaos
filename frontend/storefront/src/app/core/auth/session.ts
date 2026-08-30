import { Injectable, computed, signal } from '@angular/core';

export type SessionStatus = 'ANONYMOUS' | 'AUTHENTICATING' | 'AUTHENTICATED';

/**
 * The bearer this application is holding, and nothing else.
 *
 * ## Why there is no OIDC arm here
 *
 * A customer signs in with a phone number and an SMS code against the
 * platform's own identity endpoints (ADR 0051), and the token that comes back
 * is minted by the platform: 256 bits of CSPRNG behind a fixed `qcs1.` prefix,
 * stored on the platform only as a SHA-256 digest. It is **not a JWT**. Nothing
 * here decodes it, nothing reads a claim out of it, and there is no Keycloak
 * session behind it — so signing out is `DELETE /sessions/current` and dropping
 * the token, never a redirect to a staff realm's end-session endpoint.
 *
 * This replaces the legacy `TokenService`, which decoded the old backend's JWT
 * to recover `phone_number` from its payload. That cannot work against an opaque
 * token, and it should not: a phone number is ADR 0029 personal data, and the
 * account it belongs to is read from `GET /me`, where the decrypt is recorded
 * against a purpose.
 *
 * ## Where the token lives
 *
 * In memory, and in `localStorage` alongside its deadline.
 *
 * In-memory alone would sign a customer out every time the Telegram Mini App
 * host tears the WebView down, which it does routinely — that is the reason the
 * legacy app persisted a token too. What is stored is the token and the instant
 * it stops being spendable, and nothing else: no phone number, no account id,
 * no name. A shared handset's storage is not a place to leave somebody's
 * personal data, and none of it is needed to make a request.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly token = signal<string | null>(null);
  private readonly expiresAtMillis = signal(0);
  private readonly authenticating = signal(false);

  constructor() {
    this.restore();
  }

  /**
   * The bearer to send, or null.
   *
   * Null once the deadline has passed, *without* waiting for the platform to
   * say so. A token this application knows to be expired is one it must not
   * spend: sending it produces a 401 on a screen the customer is looking at,
   * where showing them the sign-in they actually need is the honest answer.
   */
  readonly accessToken = computed(() => {
    const value = this.token();
    if (!value) {
      return null;
    }
    return Date.now() < this.expiresAtMillis() ? value : null;
  });

  readonly status = computed<SessionStatus>(() => {
    if (this.accessToken()) {
      return 'AUTHENTICATED';
    }
    return this.authenticating() ? 'AUTHENTICATING' : 'ANONYMOUS';
  });

  readonly isAuthenticated = computed(() => this.status() === 'AUTHENTICATED');

  /** Marks a sign-in as underway, so a guard does not bounce mid-flight. */
  beginSignIn(): void {
    this.authenticating.set(true);
  }

  /**
   * Installs a token the platform just minted.
   *
   * @param minted.expiresAt the platform's own ISO-8601 deadline, taken as
   *        given. An unparseable or absent one yields 0, which every comparison
   *        against `Date.now()` reads as already expired — so a malformed
   *        response produces a customer who is not signed in rather than one who
   *        appears signed in and fails every call.
   */
  adopt(minted: { accessToken: string; expiresAt?: string | null }): void {
    const deadline = expiryOf(minted.expiresAt);
    this.token.set(minted.accessToken);
    this.expiresAtMillis.set(deadline);
    this.authenticating.set(false);
    this.persist(minted.accessToken, deadline);
  }

  /**
   * Drops a bearer the platform has told us is dead (`SESSION_EXPIRED`).
   *
   * Distinct from {@link signOut} only in intent: both end the local session,
   * but this one is the platform's statement that the session row is gone —
   * expired on its own, or revoked from another handset — which nothing local
   * could have discovered.
   */
  expire(): void {
    this.clear();
  }

  /** The customer asked to leave. The platform half is `CustomerOtp.signOut`. */
  signOut(): void {
    this.clear();
  }

  private clear(): void {
    this.token.set(null);
    this.expiresAtMillis.set(0);
    this.authenticating.set(false);
    safely(() => localStorage.removeItem(TOKEN_KEY));
    safely(() => localStorage.removeItem(EXPIRES_AT_KEY));
  }

  private persist(token: string, deadline: number): void {
    safely(() => localStorage.setItem(TOKEN_KEY, token));
    safely(() => localStorage.setItem(EXPIRES_AT_KEY, String(deadline)));
  }

  /**
   * Reads back a token a previous run stored.
   *
   * An expired one is dropped here rather than carried: leaving it would make
   * `status` briefly report a session that the first request then loses.
   */
  private restore(): void {
    const stored = safely(() => localStorage.getItem(TOKEN_KEY));
    const deadline = Number(safely(() => localStorage.getItem(EXPIRES_AT_KEY)) ?? 0);
    if (!stored || !Number.isFinite(deadline) || Date.now() >= deadline) {
      this.clear();
      return;
    }
    this.token.set(stored);
    this.expiresAtMillis.set(deadline);
  }
}

const TOKEN_KEY = 'horecaos_session_token';
const EXPIRES_AT_KEY = 'horecaos_session_expires_at';

/** Epoch milliseconds, or 0 for anything that cannot be read as an instant. */
function expiryOf(expiresAt: string | null | undefined): number {
  if (!expiresAt) {
    return 0;
  }
  const at = Date.parse(expiresAt);
  return Number.isFinite(at) ? at : 0;
}

/**
 * `localStorage` throws rather than returning null in a WebView with site data
 * disabled, and a private window in some browsers. A storefront that cannot
 * remember a session must still run.
 */
function safely<T>(read: () => T): T | null {
  try {
    return read();
  } catch {
    return null;
  }
}
