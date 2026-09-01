import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { ApiClient } from '../api/api-client';
import { command } from '../api/idempotency';
import {
  StaffLogoutRequest,
  StaffRefreshRequest,
  StaffSessionResponse,
  StaffSignInRequest,
} from './staff-session';
import { StaffTokenStore } from './staff-token-store';

/**
 * What this console knows about the sign-in state.
 *
 * There used to be no such thing as a settled `signed-out` before a redirect
 * even started — a full-page navigation to Keycloak has no meaningful
 * "starting" moment on this side. ADR 0062 makes sign-in an ordinary screen
 * instead, so `starting` now names the brief window before
 * {@link Auth#initialise} has run, kept for the same reason
 * `frontend/control-plane`'s equivalent type keeps it: shape stability for
 * whatever reads the signal before the app initializer settles it.
 */
export type AuthStatus = 'starting' | 'signed-in' | 'signed-out';

const SIGN_IN_PATH = '/api/v1/operations/auth/sessions';
const REFRESH_PATH = '/api/v1/operations/auth/sessions/refresh';
const SIGN_OUT_PATH = '/api/v1/operations/auth/sessions/current';

/** Refresh this far before the access token actually expires. */
const REFRESH_MARGIN_MS = 60_000;

/** Never wait longer than this between refresh attempts, whatever a clock says. */
const MAX_REFRESH_DELAY_MS = 30 * 60_000;

/**
 * The application's view of who is signed in (ADR 0062).
 *
 * Replaces the facade over `OidcSecurityService`: this console no longer
 * talks to Keycloak at all. It POSTs a username and password to the
 * platform's own `/auth/sessions` endpoint, which runs the Keycloak exchange
 * on the backend over a confidential client this bundle cannot see (ADR
 * 0028), and stores exactly the token pair that comes back.
 *
 * **Capability checks in this client are still a usability affordance, never
 * enforcement** — that guidance from the class this replaces has not
 * changed. The API is the enforcement point (ADR 0025); this class exposes
 * identity and session state and nothing that looks like a permission check.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly api = inject(ApiClient);
  private readonly tokens = inject(StaffTokenStore);

  private readonly state = signal<AuthStatus>('starting');
  readonly status = this.state.asReadonly();

  readonly isAuthenticated: Signal<boolean> = computed(() => this.state() === 'signed-in');

  /**
   * A label for the signed-in operator, for the account menu only, read from
   * the fresh access token's own claims rather than fetched separately.
   *
   * This is personal data. It may be rendered; it may not be logged, traced
   * or sent to analytics (ADR 0029). The stable, non-identifying value for
   * correlating a session in a log is {@link subject}.
   */
  readonly displayName = signal<string | null>(null);

  /**
   * The `sub` claim: an opaque Keycloak identifier.
   *
   * Safe in a log line, because it names an account without describing a
   * person. It is what the server records as the actor on a decision.
   */
  readonly subject = signal<string | null>(null);

  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Nothing to rehydrate. Tokens are in-memory only (ADR 0035), so a fresh
   * page load never has a session to restore — see {@link StaffTokenStore}'s
   * own doc for why that trade-off is accepted rather than worked around.
   */
  initialise(): AuthStatus {
    this.state.set('signed-out');
    return 'signed-out';
  }

  /** Exchanges credentials for a session. Throws `ApiError` on refusal — the sign-in page reads `error.code`. */
  async signIn(username: string, password: string): Promise<void> {
    const body: StaffSignInRequest = { username, password };
    const session = await firstValueFrom(
      this.api.post<StaffSignInRequest, StaffSessionResponse>(SIGN_IN_PATH, command(body)),
    );
    this.applySession(session);
  }

  /**
   * Proxies the refresh grant through the backend. Returns false rather than
   * throwing on a lapsed session: every caller already has exactly one thing
   * to do about "no" — send the operator back to `/login`.
   */
  async refresh(): Promise<boolean> {
    const refreshToken = this.tokens.refreshToken();
    if (refreshToken === null) {
      return false;
    }
    try {
      const body: StaffRefreshRequest = { refreshToken };
      const session = await firstValueFrom(
        this.api.post<StaffRefreshRequest, StaffSessionResponse>(REFRESH_PATH, command(body)),
      );
      this.applySession(session);
      return true;
    } catch {
      this.clearLocally();
      return false;
    }
  }

  /**
   * Ends the session at Keycloak as well as locally.
   *
   * A local-only sign-out leaves the refresh token live at Keycloak, which on
   * a shared till terminal at shift change means the next operator could
   * still use it if they ever obtained it. The revocation call is
   * best-effort — an `Observable` rather than a `Promise` only so
   * `shell.ts`'s existing `.subscribe()` call site needs no change — and
   * whatever it answers, the operator who clicked sign-out sees themselves
   * signed out immediately, because a sign-out button that sometimes does
   * not work when the network is the problem is worse than one that always
   * clears the local session.
   */
  logout(): Observable<unknown> {
    const refreshToken = this.tokens.refreshToken();
    this.clearLocally();
    if (refreshToken === null) {
      return of(null);
    }
    const body: StaffLogoutRequest = { refreshToken };
    return this.api
      .send<StaffLogoutRequest, void>('DELETE', SIGN_OUT_PATH, command(body))
      .pipe(catchError(() => of(null)));
  }

  /** The current access token, for the rare caller that needs it explicitly (the bearer interceptor reads the store directly). */
  accessToken(): string | null {
    return this.tokens.accessToken();
  }

  private applySession(session: StaffSessionResponse): void {
    this.tokens.set(session.accessToken, session.refreshToken);
    const claims = decodeJwtPayload(session.accessToken);
    const name = claims?.['name'] ?? claims?.['preferred_username'];
    this.displayName.set(typeof name === 'string' ? name : null);
    const sub = claims?.['sub'];
    this.subject.set(typeof sub === 'string' ? sub : null);
    this.state.set('signed-in');
    this.scheduleRefresh(session.accessTokenExpiresAt);
  }

  private clearLocally(): void {
    this.cancelScheduledRefresh();
    this.tokens.clear();
    this.displayName.set(null);
    this.subject.set(null);
    this.state.set('signed-out');
  }

  /** Wires the refresh endpoint proactively — the replacement for the OIDC library's `silentRenew`. */
  private scheduleRefresh(accessTokenExpiresAt: string): void {
    this.cancelScheduledRefresh();
    const expiresAt = Date.parse(accessTokenExpiresAt);
    const delay = Number.isNaN(expiresAt)
      ? MAX_REFRESH_DELAY_MS
      : Math.min(MAX_REFRESH_DELAY_MS, Math.max(0, expiresAt - Date.now() - REFRESH_MARGIN_MS));
    this.refreshTimer = setTimeout(() => void this.refresh(), delay);
  }

  private cancelScheduledRefresh(): void {
    if (this.refreshTimer !== null) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
  }
}

/**
 * `preferred_username`/`name`/`sub` out of an access token's own claims,
 * unverified — safe here specifically because this JWT was handed to this
 * console moments ago, straight from this console's own backend, over the
 * same TLS connection every other API response arrives on.
 */
function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const segments = token.split('.');
  if (segments.length !== 3) {
    return null;
  }
  try {
    const base64 = segments[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const json = decodeURIComponent(
      atob(padded)
        .split('')
        .map((char) => '%' + char.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    );
    const parsed: unknown = JSON.parse(json);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    // A malformed or unexpectedly-shaped token loses the display name, never
    // the sign-in itself: the caller already has valid tokens by the time
    // this runs.
    return null;
  }
}
