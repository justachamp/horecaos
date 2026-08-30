import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { map, take } from 'rxjs';

/**
 * Where the operator was heading when they were bounced to Keycloak.
 *
 * Kept in `sessionStorage` because it has to survive a full-page redirect and it
 * is a route, not a credential. Restored by the callback route.
 *
 * Losing it matters more here than on most consoles: a supervisor sends a link
 * to one late order, the recipient's session has lapsed, and without this they
 * land on Today and have to find the order themselves — which is the moment the
 * link stopped being useful.
 */
export const RETURN_TO_KEY = 'qoida.operations.returnTo';

/**
 * Requires a signed-in operator, and starts the login redirect when there is not
 * one (ADR 0003, ADR 0035).
 *
 * The guard never decides *what* the operator may do. It decides only whether
 * anybody is there. Authorization is the server's, per ADR 0025, and a guard
 * that inspected role claims would be inventing a second, weaker, always-stale
 * copy of the capability model.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const oidc = inject(OidcSecurityService);

  return oidc.isAuthenticated$.pipe(
    take(1),
    map(({ isAuthenticated }) => {
      if (isAuthenticated) {
        return true;
      }
      rememberReturnTo(state.url);
      oidc.authorize();
      // False, not a UrlTree: `authorize()` is navigating the whole document
      // away. Returning a UrlTree here would race the redirect and briefly paint
      // a route the operator is not entitled to see.
      return false;
    }),
  );
};

function rememberReturnTo(url: string): void {
  try {
    globalThis.sessionStorage?.setItem(RETURN_TO_KEY, url);
  } catch {
    // Storage can be unavailable — a locked-down kiosk profile, or a private
    // window with quota exhausted. Losing the deep link is a worse landing page,
    // not a failed login, so it must not throw.
  }
}
