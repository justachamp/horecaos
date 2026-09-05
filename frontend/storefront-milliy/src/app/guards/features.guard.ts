import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { FEATURES } from '../core/config/features';

/**
 * Keeps the favourites screen behind its feature flag.
 *
 * The heart on a food card and the profile menu link are both hidden while
 * `FEATURES.favourites` is off, but a URL is still a URL: this is what stops
 * somebody who navigates to `/profile/favorites` directly from landing on a
 * screen whose every call 404s against a backend that has not shipped yet.
 */
export const favouritesEnabledGuard: CanActivateFn = () => {
  if (FEATURES.favourites) {
    return true;
  }
  return inject(Router).createUrlTree(['/profile']);
};
