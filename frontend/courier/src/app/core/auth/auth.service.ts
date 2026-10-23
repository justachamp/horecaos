import { Injectable, computed, inject, signal } from '@angular/core';

import { I18n } from '../i18n/i18n';
import {
  AUTH_CLOCK,
  BROWSER_NAVIGATION,
  OidcClient,
  OidcError,
  SIGNED_OUT_PATH,
  type IdentityClaims,
  type Tokens,
} from './oidc-client';
import { PendingLoginStore } from './pending-login-store';
import { sanitizeReturnTo } from './return-destination';

/** An access token this close to expiry is renewed before it is used, so a request never races its own expiry. */
export const REFRESH_SKEW_MS = 30_000;

/**
 * What a renewal came to.
 *
 * - `renewed`: new tokens are held.
 * - `kept`: the identity provider could not be reached, so nothing was decided: the tokens held are
 *   still the courier's, and the session carries on with them until a renewal can be made.
 * - `ended`: there is nothing to renew with, or the identity provider refused; the session is over.
 */
export type RenewOutcome = 'renewed' | 'kept' | 'ended';

/**
 * The courier's sign-in, and the only place a token is held.
 *
 * **Tokens live in memory only** (AGENTS.md: never in local storage). Reloading the page, or the
 * operating system discarding a backgrounded tab, loses them -- and that is cheap, because the
 * Keycloak session cookie outlives the page: the route guard sends the browser back to the
 * authorization endpoint and the identity provider answers at once with a new code, with no
 * password prompt, until the SSO session itself ends. That session is the realm's (30 minutes
 * idle, 10 hours at most at the defaults; a client cannot lengthen it, see
 * `create-courier-login-client.sh`), and an app in the foreground on duty keeps it alive by
 * refreshing, so a phone locked on a long break signs in again and a working shift does not.
 *
 * Renewal is single-flight: a screen firing three requests as the token lapses causes one refresh,
 * not three, which matters because a refresh token is rotated and the second use of a spent one
 * would end the session.
 *
 * **Only a refusal ends the session.** A courier in a lift or a basement has a token lapsing and no
 * connection; the identity provider not answering says nothing about the refresh token, so the
 * tokens are kept and the stale access token is still handed out -- the platform decides whether it
 * is good, and a call that fails for want of a connection is retried by the callers that retry.
 * Ending the session there would stop the duty session and its sender and replace the page in
 * the middle of a delivery.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oidc = inject(OidcClient);
  private readonly pending = inject(PendingLoginStore);
  private readonly navigation = inject(BROWSER_NAVIGATION);
  private readonly now = inject(AUTH_CLOCK);
  private readonly i18n = inject(I18n);

  private tokens: Tokens | null = null;
  private refreshing: Promise<RenewOutcome> | null = null;

  private readonly identity = signal<IdentityClaims | null>(null);

  /** Who is signed in, for display only. Authorization is the platform's, on every call. */
  readonly claims = this.identity.asReadonly();
  readonly signedIn = computed(() => this.identity() !== null);

  /** Leaves for the identity provider. Resolves only if it could not (the page is gone otherwise). */
  async beginSignIn(returnTo: string): Promise<void> {
    const { url, pending } = await this.oidc.startLogin(
      sanitizeReturnTo(returnTo),
      this.i18n.locale(),
    );
    if (!this.pending.save(pending)) {
      throw new OidcError('storage_unavailable', 'the browser would not keep the login in flight');
    }
    this.navigation.assign(url);
  }

  /** Completes the login the callback answers, and returns the path to open. */
  async completeSignIn(callback: URLSearchParams): Promise<string> {
    const pending = this.pending.take();
    const { tokens, claims } = await this.oidc.completeLogin(callback, pending);
    this.tokens = tokens;
    this.identity.set(claims);
    return sanitizeReturnTo(pending?.returnTo);
  }

  /**
   * A token that is good to send now, or null when there is none to be had.
   *
   * Null is not an error to retry: it means the session is over and the caller should start a
   * sign-in. When a renewal was due but the identity provider could not be reached the session is
   * not over, so the token already held is returned (it may be about to lapse, or just have).
   */
  async accessToken(): Promise<string | null> {
    if (this.tokens === null) {
      return null;
    }
    if (this.tokens.accessExpiresAt - this.now() > REFRESH_SKEW_MS) {
      return this.tokens.accessToken;
    }
    const outcome = await this.renew();
    return outcome === 'ended' ? null : (this.tokens?.accessToken ?? null);
  }

  /** Renews the tokens now, whatever their clock says; used when the platform refuses one. */
  forceRefresh(): Promise<RenewOutcome> {
    return this.renew();
  }

  /** The session is over: forget everything held. Does not talk to the identity provider. */
  expire(): void {
    this.tokens = null;
    this.identity.set(null);
  }

  /** Ends the session here and at the identity provider. */
  async signOut(): Promise<void> {
    const idToken = this.tokens?.idToken ?? null;
    this.expire();
    this.pending.clear();
    let destination: string | null = null;
    try {
      destination = await this.oidc.endSessionUrl(idToken);
    } catch {
      // The identity provider could not be asked; the local session is already gone.
    }
    this.navigation.assign(destination ?? this.navigation.origin + SIGNED_OUT_PATH);
  }

  private renew(): Promise<RenewOutcome> {
    this.refreshing ??= this.refreshOnce().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async refreshOnce(): Promise<RenewOutcome> {
    const current = this.tokens;
    if (current === null || current.refreshToken === null) {
      this.expire();
      return 'ended';
    }
    if (current.refreshExpiresAt !== null && current.refreshExpiresAt <= this.now()) {
      this.expire();
      return 'ended';
    }
    try {
      const renewed = await this.oidc.refresh(current.refreshToken);
      this.tokens = {
        ...renewed,
        // A refresh response may omit the identity token; the logout hint is still wanted.
        idToken: renewed.idToken ?? current.idToken,
      };
      return 'renewed';
    } catch (failure) {
      if (failure instanceof OidcError && failure.code === 'refresh_unavailable') {
        // Nobody said no. The tokens are still the courier's; the next call tries again.
        return 'kept';
      }
      this.expire();
      return 'ended';
    }
  }
}
