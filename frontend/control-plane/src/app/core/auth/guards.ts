import { inject } from '@angular/core';
import { CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';
import { Capability } from './capability';
import { SessionContextService } from './session-context.service';

/**
 * Where the operator was heading when they were sent to sign in.
 *
 * Kept in `sessionStorage` because it has to survive the sign-in page's own
 * navigation away and back, and it is a route, not a credential. Read by
 * `SignInPage` once sign-in succeeds; also written by
 * `sessionRefreshInterceptor` when a mid-session 401 survives a silent
 * refresh attempt, so a token that expires while an operator is three levels
 * into Tenants sends them back to the same place, not to the console root.
 */
export const RETURN_TO_KEY = 'horecaos.control-plane.returnTo';

/**
 * Remembers a destination for {@link SignInPage} to return to.
 *
 * Exported so `sessionRefreshInterceptor` can call this too — a 401 that
 * survives a silent refresh loses the session exactly the way an unguarded
 * navigation does, and deserves the same "come back here" treatment.
 */
export function rememberReturnTo(url: string): void {
  try {
    globalThis.sessionStorage?.setItem(RETURN_TO_KEY, url);
  } catch {
    // Storage can be unavailable — a locked-down kiosk profile, or a private
    // window with quota exhausted. Losing the deep link is a worse landing
    // page, not a failed sign-in or a failed redirect, so it must not throw.
  }
}

/**
 * No route in this console is reachable without a session.
 *
 * A signed-out visitor is sent to this console's own `/login` (ADR 0062),
 * not to Keycloak: the browser never talks to Keycloak at all any more, so
 * there is nothing to redirect to and nothing that can race a document
 * navigation the way the old Authorization Code redirect could. A `UrlTree`
 * is therefore always safe to return here, unlike the old guard's `false`.
 */
export const authGuard: CanActivateFn = (_route, state: RouterStateSnapshot): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.status() === 'signed-in') {
    return true;
  }
  rememberReturnTo(state.url);
  return router.parseUrl('/login');
};

/**
 * Keeps a route out of reach when the principal lacks its capability.
 *
 * A convenience, not a control. The API refuses the same call regardless, and
 * this exists so an account manager does not click into a section that answers
 * 403 — not because clicking it would have worked.
 */
export function requiresCapability(...required: readonly Capability[]): CanActivateFn {
  return (): boolean | UrlTree => {
    const session = inject(SessionContextService);
    const router = inject(Router);
    return session.hasAll(required) ? true : router.parseUrl('/denied');
  };
}
