import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { Auth } from './auth';

/**
 * Where the operator was heading when they were sent to sign in.
 *
 * Kept in `sessionStorage` because it has to survive the sign-in page's own
 * navigation away and back, and it is a route, not a credential. Read by
 * `SignInPage` once sign-in succeeds.
 *
 * Losing it matters more here than on most consoles: a supervisor sends a
 * link to one late order, the recipient's session has lapsed, and without
 * this they land on Today and have to find the order themselves — which is
 * the moment the link stopped being useful.
 */
export const RETURN_TO_KEY = 'horecaos.operations.returnTo';

/**
 * Requires a signed-in operator, and sends a signed-out visitor to this
 * console's own `/login` (ADR 0062, ADR 0003, ADR 0035).
 *
 * The guard never decides *what* the operator may do. It decides only
 * whether anybody is there. Authorization is the server's, per ADR 0025, and
 * a guard that inspected role claims would be inventing a second, weaker,
 * always-stale copy of the capability model.
 *
 * Synchronous now, unlike the guard this replaces: `Auth.isAuthenticated()`
 * is a signal read, not an `Observable` over a library's own async state
 * machine, and a `UrlTree` here is always safe to return — there is no
 * document navigation it could race, because the browser never leaves this
 * origin at all any more.
 */
export const authGuard: CanActivateFn = (_route, state): boolean | UrlTree => {
  const auth = inject(Auth);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }
  rememberReturnTo(state.url);
  return router.parseUrl('/login');
};

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
