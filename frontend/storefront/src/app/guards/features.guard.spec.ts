import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';

import { favouritesEnabledGuard } from './features.guard';
import { FEATURES } from '../core/config/features';

function setUp() {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  return { router: TestBed.inject(Router) };
}

function runGuard() {
  return TestBed.runInInjectionContext(() =>
    favouritesEnabledGuard({} as never, { url: '/profile/favorites' } as never),
  );
}

describe('favouritesEnabledGuard', () => {
  it('blocks /profile/favorites while FEATURES.favourites is off, redirecting to /profile', () => {
    // This is the shipped configuration today (`core/config/features.ts`):
    // the favourites backend does not exist yet, so a direct navigation to
    // the URL must not reach a screen whose every call would 404.
    expect(FEATURES.favourites).toBe(false);

    const { router } = setUp();
    const result = runGuard();

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/profile');
  });

  it('passes through once the flag is on -- proving this is a real toggle and not a permanent block', () => {
    setUp();
    const original = FEATURES.favourites;
    // `FEATURES` is a plain frozen-shaped `const`, not a DI token, so the
    // guard's *other* branch is exercised by flipping the flag directly
    // rather than by provider override -- the same thing a future
    // remote-config read would do at this call site.
    (FEATURES as { favourites: boolean }).favourites = true;
    try {
      expect(runGuard()).toBe(true);
    } finally {
      (FEATURES as { favourites: boolean }).favourites = original;
    }
  });
});
