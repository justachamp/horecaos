import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { AccessTokenSource } from './access-token-source';
import {
  StaffLogoutRequest,
  StaffRefreshRequest,
  StaffSessionResponse,
  StaffSignInRequest,
} from './staff-session';
import { StaffTokenStore } from './staff-token-store';

/**
 * What the application knows about the sign-in state.
 *
 * There used to be a fourth value here, `unavailable`, for when Keycloak's own
 * discovery document failed to load during bootstrap — a redirect flow has a
 * moment before any user action where the whole thing can be unreachable, and
 * a console that rendered nothing in that case was indistinguishable from a
 * console that was broken. ADR 0062 removes the case along with the redirect:
 * `initialise()` below makes no network call at all, so there is nothing left
 * that can fail before the operator has even seen a sign-in page. A platform
 * that cannot be reached now surfaces per-attempt, as an `ApiError` on the
 * sign-in page's own submit — see `SignInPage`.
 */
export type AuthStatus = 'starting' | 'signed-in' | 'signed-out';

const SIGN_IN_PATH = '/api/v1/control-plane/auth/sessions';
const REFRESH_PATH = '/api/v1/control-plane/auth/sessions/refresh';
const SIGN_OUT_PATH = '/api/v1/control-plane/auth/sessions/current';

/**
 * Refresh this far before the access token actually expires, so an in-flight
 * request is never the one that discovers expiry. The same margin the old
 * `angular-oauth2-oidc` config used (`clockSkewInSec: 60`).
 */
const REFRESH_MARGIN_MS = 60_000;

/** Never wait longer than this between refresh attempts, whatever a clock says. */
const MAX_REFRESH_DELAY_MS = 30 * 60_000;

/**
 * Staff sign-in on this console's own page (ADR 0062).
 *
 * Replaces the Authorization Code + PKCE redirect to Keycloak: this class no
 * longer talks to Keycloak at all. It POSTs a username and password to the
 * platform's own `/auth/sessions` endpoint, which does the Keycloak exchange
 * on the backend, and stores exactly the token pair that comes back. Every
 * other consumer of this service — the bearer interceptor via
 * {@link AccessTokenSource}, the guard, the shell's operator name and
 * sign-out button — is unchanged, because the shape this class exposes
 * (a status signal, a display name, sign-in/out/refresh) is unchanged.
 */
@Injectable({ providedIn: 'root' })
export class AuthService extends AccessTokenSource {
  private readonly api = inject(ApiClient);
  private readonly tokens = inject(StaffTokenStore);

  private readonly state = signal<AuthStatus>('starting');
  readonly status = this.state.asReadonly();

  readonly signedIn = computed(() => this.state() === 'signed-in');

  /**
   * The display name for the rail's footer, read from the fresh access
   * token's own claims rather than fetched separately. Never logged: it is
   * the operator's own name and therefore personal data (ADR 0029).
   */
  readonly displayName = signal<string | null>(null);

  /**
   * The pending call to {@link refresh}, scheduled a minute before the
   * current access token expires. Tracked so a second sign-in or a sign-out
   * can cancel a stale timer rather than let it fire against a session that
   * has already changed underneath it.
   */
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Nothing to rehydrate. Tokens are in-memory only (ADR 0035), so a fresh
   * page load never has a session to restore — see {@link StaffTokenStore}'s
   * own doc for why that trade-off is accepted rather than worked around.
   * Kept as a method, and still returning the status, so the application
   * initialiser and the guard need no change in shape from before ADR 0062.
   */
  initialise(): AuthStatus {
    this.state.set('signed-out');
    return 'signed-out';
  }

  /** Exchanges credentials for a session. Throws `ApiError` on refusal — the sign-in page reads `error.code`. */
  async signIn(username: string, password: string): Promise<void> {
    const body: StaffSignInRequest = { username, password };
    const session = await firstValueFrom(this.api.post<StaffSessionResponse>(SIGN_IN_PATH, body));
    this.applySession(session);
  }

  /**
   * Proxies the refresh grant through the backend. Returns false rather than
   * throwing on a lapsed session, because every caller of this method already
   * has exactly one thing to do about "no" — send the operator back to
   * sign-in — and a caught exception type is not needed to decide that.
   */
  async refresh(): Promise<boolean> {
    const refreshToken = this.tokens.refreshToken();
    if (refreshToken === null) {
      return false;
    }
    try {
      const body: StaffRefreshRequest = { refreshToken };
      const session = await firstValueFrom(this.api.post<StaffSessionResponse>(REFRESH_PATH, body));
      this.applySession(session);
      return true;
    } catch {
      this.clearLocally();
      return false;
    }
  }

  /**
   * Ends the session at Keycloak as well as here.
   *
   * A local-only sign-out leaves the refresh token live at Keycloak, so
   * anyone who later obtained it — a shared machine's process memory, a
   * debugging session — could still use it. The revocation call is
   * best-effort: whatever it answers, the operator who clicked sign-out sees
   * themselves signed out immediately, because the alternative is a sign-out
   * button that sometimes does not work when the network is the problem.
   */
  async signOut(): Promise<void> {
    const refreshToken = this.tokens.refreshToken();
    this.clearLocally();
    if (refreshToken === null) {
      return;
    }
    try {
      const body: StaffLogoutRequest = { refreshToken };
      await firstValueFrom(this.api.delete(SIGN_OUT_PATH, body));
    } catch {
      // Best-effort revocation; the local session is already gone either way.
    }
  }

  override accessToken(): string | null {
    return this.tokens.accessToken();
  }

  private applySession(session: StaffSessionResponse): void {
    this.tokens.set(session.accessToken, session.refreshToken);
    this.displayName.set(readDisplayName(session.accessToken));
    this.state.set('signed-in');
    this.scheduleRefresh(session.accessTokenExpiresAt);
  }

  private clearLocally(): void {
    this.cancelScheduledRefresh();
    this.tokens.clear();
    this.displayName.set(null);
    this.state.set('signed-out');
  }

  /**
   * Wires the refresh endpoint proactively, the replacement for
   * `setupAutomaticSilentRefresh()`. A silent, unattended failure here just
   * means the *next* API call hits a 401 and the operator is sent back to
   * `/login` — the same outcome a missed refresh always had — so nothing
   * here needs its own error surface.
   */
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
 * `preferred_username` (or `name`, when the profile scope carries one) out of
 * an access token's own claims, unverified.
 *
 * Reading it without checking the signature is safe specifically here and
 * nowhere else this console might parse a token: this JWT was handed to this
 * console moments ago, straight from this console's own backend, over the
 * same TLS connection every other API response arrives on. There is no
 * scenario in which it is attacker-controlled that a signature check would
 * catch and this parse would not.
 */
function readDisplayName(accessToken: string): string | null {
  const claims = decodeJwtPayload(accessToken);
  const name = claims?.['name'] ?? claims?.['preferred_username'];
  return typeof name === 'string' ? name : null;
}

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
