import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { ConsoleProfile } from './console-profile';
import { SessionCapabilities } from './session-capabilities';

/**
 * Where the console opens (ADR 0164): the root path redirects to the profile's landing route, which is
 * `/today` for every tenant that has not bought the `deliveries-console` module and `/deliveries` for
 * one that has.
 *
 * Not an access guard and not a capability check -- `capabilityGuard` still decides, at the landing, whether
 * this operator has any business there. The one thing this adds is a fallback so that a deliveries-profile
 * operator who holds no `DELIVERY_PLAN_READ` (a role the profile's landing means nothing to) is sent to
 * `/today` rather than to an access-denied page they did nothing to earn.
 *
 * A profile that cannot be read lands on `/today`, as the console always did.
 */
export const landingGuard: CanActivateFn = async (): Promise<UrlTree> => {
  const profile = inject(ConsoleProfile);
  const capabilities = inject(SessionCapabilities);
  const router = inject(Router);

  await Promise.all([profile.ensureLoaded(), capabilities.ensureLoaded()]);

  const landing = profile.landing();
  if (landing === '/deliveries' && !capabilities.has('DELIVERY_PLAN_READ')) {
    return router.parseUrl('/today');
  }
  return router.parseUrl(landing);
};

/**
 * Where Statistics opens (ADR 0164 Decision 5d): the couriers report for the deliveries profile -- a
 * courier-service tenant's overview is its couriers -- and the business overview for everyone else.
 */
export const statisticsLandingGuard: CanActivateFn = async (): Promise<UrlTree> => {
  const profile = inject(ConsoleProfile);
  const router = inject(Router);

  await profile.ensureLoaded();

  return router.parseUrl(profile.isDeliveries() ? '/statistics/couriers' : '/statistics/overview');
};
