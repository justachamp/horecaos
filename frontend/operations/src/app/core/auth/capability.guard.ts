import { inject } from '@angular/core';
import { CanActivateChildFn, Router, UrlTree } from '@angular/router';

import { NavItem, NAV_ITEMS } from '../../shell/navigation';
import { SessionCapabilities } from './session-capabilities';

/**
 * Refuses a direct URL into a rail section the operator holds no capability
 * for (operations IA §9.1c) — `authGuard`'s sibling, and the thing that
 * guard explicitly declines to be. `authGuard` asks only "is anybody
 * signed in"; this one asks the question `Shell`'s rail filter already asks
 * of the identical `GET /api/v1/session/context` response, so typing
 * `/staff` straight into the address bar is refused exactly as visibly as
 * clicking a rail item that was never rendered — never a second,
 * authoritative copy of ADR 0025: `CapabilityEnforcementInterceptor` still
 * refuses the API calls behind this route either way, and a wrong
 * prediction here only ever costs an extra round trip, never a wrong grant.
 *
 * Declared as `canActivateChild` on the shell's own route (`app.routes.ts`)
 * rather than repeated as `canActivate` on all fourteen children: every
 * route beneath the shell — including a nested one like `/staff/roles` or
 * `/settings/terms` — is refused by the same top-level section's capability,
 * checked once, from one place, by matching the URL's first path segment
 * against `navigation.ts`'s own `NAV_ITEMS`.
 *
 * Refused navigation lands on `/access-denied` (a routed child of the shell,
 * so the rail and top bar stay visible) naming the missing capability in the
 * query string, which `AccessDeniedPage` reads to render `q-denied-state` —
 * "make refusal legible" applied to a client-predicted refusal, the same as
 * `staff-roles-page.ts` applies it to a live one.
 */
export const capabilityGuard: CanActivateChildFn = async (_childRoute, state) => {
  const item = navItemForUrl(state.url);
  if (item === undefined) {
    // Not one of the fourteen rail sections — `/access-denied` itself, a
    // redirect target, or a route this wave does not know about. Nothing
    // here to predict, so let the route (and the server, on its own calls)
    // decide.
    return true;
  }

  // Both `inject()` calls happen before the `await` below, on purpose:
  // Angular's injection context does not survive an `await` — a call to
  // `inject()` in this function's continuation throws NG0203, in a browser
  // exactly as it would in a test.
  const capabilities = inject(SessionCapabilities);
  const router = inject(Router);

  await capabilities.ensureLoaded();
  if (capabilities.has(item.capability)) {
    return true;
  }

  const denied: UrlTree = router.createUrlTree(['/access-denied'], {
    queryParams: { capability: item.capability, section: item.label },
  });
  return denied;
};

/**
 * The `NAV_ITEMS` entry a URL's top-level path segment belongs to, or
 * `undefined` when the URL does not name one of the fourteen rail sections
 * at all (the empty path, a redirect target, `/access-denied` itself).
 * Exported for this file's own spec.
 */
export function navItemForUrl(url: string): NavItem | undefined {
  const path = url.split('?')[0].split('#')[0];
  const firstSegment = path.split('/')[1];
  return firstSegment ? NAV_ITEMS.find((item) => item.path === `/${firstSegment}`) : undefined;
}
