import { Injectable, Signal, computed, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable } from 'rxjs';

/**
 * The application's view of who is signed in.
 *
 * A facade over `OidcSecurityService` rather than a passthrough, for two
 * reasons. It keeps the library at one seam, so replacing it is one file. And it
 * is the place to state what a client may and may not conclude from a token:
 *
 * **Capability checks in this client are a usability affordance, never
 * enforcement.** The API is the enforcement point (ADR 0025). Hiding a button
 * the operator cannot use is good design; believing that hiding it prevents
 * anything is a security bug. This class therefore exposes identity and never
 * exposes a `can(capability)` helper — no such helper can be correct, because
 * capability resolution is a server-side decision over grants, scopes and
 * entitlements that no token claim summarises.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly oidc = inject(OidcSecurityService);

  /**
   * Whether a valid session exists.
   *
   * `authenticated()` reports per-config; with a single config the `isAuthenticated`
   * property is the one to read.
   */
  readonly isAuthenticated: Signal<boolean> = computed(
    () => this.oidc.authenticated().isAuthenticated,
  );

  /**
   * A label for the signed-in operator, for the account menu only.
   *
   * This is personal data. It may be rendered; it may not be logged, traced or
   * sent to analytics (ADR 0029). The stable, non-identifying value for
   * correlating a session in a log is {@link subject}.
   */
  readonly displayName: Signal<string | null> = computed(() => {
    const claims = this.oidc.userData().userData as Record<string, unknown> | null | undefined;
    if (!claims) {
      return null;
    }
    const name = claims['name'] ?? claims['preferred_username'];
    return typeof name === 'string' ? name : null;
  });

  /**
   * The `sub` claim: an opaque Keycloak identifier.
   *
   * Safe in a log line, because it names an account without describing a person.
   * It is what the server records as the actor on a decision.
   */
  readonly subject: Signal<string | null> = computed(() => {
    const claims = this.oidc.userData().userData as Record<string, unknown> | null | undefined;
    const sub = claims?.['sub'];
    return typeof sub === 'string' ? sub : null;
  });

  /** Starts the Authorization Code + PKCE redirect. */
  login(): void {
    this.oidc.authorize();
  }

  /**
   * Ends the session at Keycloak and locally.
   *
   * Both halves matter. Clearing local state without calling the end-session
   * endpoint leaves the SSO cookie alive, so the next login silently signs the
   * same operator back in — which on a shared till terminal at shift change is
   * the wrong person's name on the next twenty orders.
   */
  logout(): Observable<unknown> {
    return this.oidc.logoff();
  }

  /** The current access token, for the rare caller that needs it explicitly. */
  accessToken(): Observable<string> {
    return this.oidc.getAccessToken();
  }
}
