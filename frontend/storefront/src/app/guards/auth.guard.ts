import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Session } from '../core/auth/session';

/**
 * Keeps a guarded route behind a live session.
 *
 * Reads {@link Session}, which reports a token expired the moment its own
 * deadline passes rather than waiting for the platform to answer 401. The
 * legacy guard asked `TokenService` whether a string was present in
 * localStorage, which stayed true for thirty days after the token behind it
 * died -- so every guarded screen let the customer through and then failed.
 */
export const authGuard: CanActivateFn = () => {
  if (inject(Session).isAuthenticated()) {
    return true;
  }
  return inject(Router).createUrlTree(['/auth', 'login']);
};
