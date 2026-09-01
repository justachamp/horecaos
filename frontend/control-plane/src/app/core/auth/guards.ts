import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { AuthService } from './auth.service';
import { Capability } from './capability';
import { SessionContextService } from './session-context.service';

/**
 * No route in this console is reachable without a session.
 *
 * A signed-out visitor is sent to this console's own `/login` (ADR 0062),
 * not to Keycloak: the browser never talks to Keycloak at all any more, so
 * there is nothing to redirect to and nothing that can race a document
 * navigation the way the old Authorization Code redirect could. A `UrlTree`
 * is therefore always safe to return here, unlike the old guard's `false`.
 */
export const authGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.status() === 'signed-in' ? true : router.parseUrl('/login');
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
