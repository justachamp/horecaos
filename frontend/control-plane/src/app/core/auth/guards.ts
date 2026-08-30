import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';
import { Capability } from './capability';
import { SessionContextService } from './session-context.service';

/**
 * No route in this console is reachable without a session.
 *
 * A signed-out visitor is sent to Keycloak rather than to a local sign-in
 * page: there is no local sign-in page, and inventing one that immediately
 * redirects is a screen whose only content is a flicker.
 */
export const authGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  switch (auth.status()) {
    case 'signed-in':
      return true;
    case 'unavailable':
      // The realm is not answering. Route to the state that says so, rather
      // than to a redirect that will also fail and lose the address bar.
      return router.parseUrl('/unavailable');
    default:
      auth.signIn();
      // False rather than a redirect: the browser is already navigating away
      // to Keycloak, and starting a second navigation races it.
      return false;
  }
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
