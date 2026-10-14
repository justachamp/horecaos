import { inject } from '@angular/core';
import { Router, type CanActivateFn } from '@angular/router';

import { AuthService } from './auth.service';
import { OidcError } from './oidc-client';

/**
 * A route that needs a signed-in courier.
 *
 * With no usable token it does not render anything: it sends the browser to the identity provider
 * (which, while the SSO session lives, sends it straight back with a code) and answers `false`.
 * If the sign-in cannot even be started -- the realm is unreachable, the browser keeps no
 * storage -- it routes to the error screen, which offers a retry, rather than looping.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if ((await auth.accessToken()) !== null) {
    return true;
  }
  try {
    await auth.beginSignIn(state.url);
  } catch (failure) {
    const code = failure instanceof OidcError ? failure.code : 'provider_error';
    return router.createUrlTree(['/auth/error'], { queryParams: { code } });
  }
  return false;
};
