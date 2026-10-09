import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';

import { SessionCapabilities } from '../../core/auth/session-capabilities';

/**
 * Where `/customers` lands for an operator who is in the section for the call centre's queue and
 * for nothing else (ADR 0111, ADR 0025).
 *
 * A brand manager holds `customer.lead.read` at her brand and no `customer.read` (`PlatformRole`), so the
 * rail admits her to Customers (`NavItem.alsoAdmittedBy`) and the customer list — the section's default
 * screen — would answer her with a refusal she can do nothing about. She is sent to the queue instead.
 * Everyone who can read customers keeps the list as the landing screen, and an operator who holds both
 * is not redirected.
 *
 * A usability affordance, never an authorization decision: the server refuses the list's requests for
 * anyone without `customer.read` whatever this guard says.
 */
export const customersLandingGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  // Both inject() calls happen before the await: the injection context does not survive it.
  const capabilities = inject(SessionCapabilities);
  const router = inject(Router);

  await capabilities.ensureLoaded();
  if (!capabilities.has('CUSTOMER_READ') && capabilities.has('CUSTOMER_LEAD_READ')) {
    return router.createUrlTree(['/customers/leads']);
  }
  return true;
};
