import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';

import { APP_CONFIG } from '../config/app-config';
import { AccessTokenSource } from './access-token-source';
import { buildAuthConfig } from './auth.config';

/**
 * What the application knows about the sign-in state.
 *
 * `unavailable` is a first-class state rather than an error, because the realm
 * genuinely may not be reachable — a developer machine without Keycloak
 * running, a network partition — and a console that renders a white screen
 * in that case is indistinguishable from a console that is broken.
 */
export type AuthStatus = 'starting' | 'signed-in' | 'signed-out' | 'unavailable';

@Injectable({ providedIn: 'root' })
export class AuthService extends AccessTokenSource {
  private readonly oauth = inject(OAuthService);
  private readonly config = inject(APP_CONFIG);

  private readonly state = signal<AuthStatus>('starting');
  readonly status = this.state.asReadonly();

  readonly signedIn = computed(() => this.state() === 'signed-in');

  /**
   * The display name from the id token, for the rail's footer.
   *
   * Read from claims rather than fetched, and never logged: it is the
   * operator's own name and therefore personal data (ADR 0029).
   */
  readonly displayName = signal<string | null>(null);

  /**
   * Completes a redirect if one is in flight, and reports what happened.
   *
   * Never throws. A failure to reach the realm sets `unavailable` and lets the
   * shell say so; the alternative is an unhandled rejection during bootstrap,
   * which Angular renders as nothing at all.
   */
  async initialise(): Promise<AuthStatus> {
    this.oauth.configure(buildAuthConfig(this.config));

    try {
      await this.oauth.loadDiscoveryDocumentAndTryLogin();
    } catch {
      // The exception is not re-logged: angular-oauth2-oidc has already
      // written its own diagnostic, and a second entry would carry the
      // attempted redirect URL, which is one of the few things in this flow
      // that can contain something about a person.
      this.state.set('unavailable');
      return 'unavailable';
    }

    if (this.oauth.hasValidAccessToken()) {
      this.oauth.setupAutomaticSilentRefresh();
      this.readClaims();
      this.state.set('signed-in');
      return 'signed-in';
    }

    this.state.set('signed-out');
    return 'signed-out';
  }

  /** Starts the redirect. Returns nothing useful: the document is going away. */
  signIn(): void {
    this.oauth.initCodeFlow();
  }

  /**
   * Ends the session at Keycloak as well as here.
   *
   * A local-only sign-out leaves the realm's SSO cookie in place, so the next
   * sign-in is silent and the operator who deliberately signed out on a shared
   * machine is signed straight back in.
   */
  signOut(): void {
    this.displayName.set(null);
    this.state.set('signed-out');
    this.oauth.logOut();
  }

  override accessToken(): string | null {
    return this.oauth.getAccessToken() || null;
  }

  private readClaims(): void {
    const claims = this.oauth.getIdentityClaims() as Record<string, unknown> | null;
    const name = claims?.['name'] ?? claims?.['preferred_username'];
    this.displayName.set(typeof name === 'string' ? name : null);
  }
}
