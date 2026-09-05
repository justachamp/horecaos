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

/**
 * `FEATURES` is a plain module-level `const`, not a DI token, so there is no
 * provider to override for the guard's other branch -- the flag is flipped
 * directly, the same thing a future remote-config read would do at this call
 * site. Each test sets its own precondition explicitly rather than trusting
 * whatever `favourites` currently ships as: that value is expected to change
 * the day the backend lands (see the doc comment on `FEATURES.favourites`),
 * and did in fact change under this very test suite while it was being
 * written -- so nothing here may assume today's shipped default.
 */
describe('favouritesEnabledGuard', () => {
  const originalFavourites = FEATURES.favourites;

  function setFlag(value: boolean): void {
    (FEATURES as { favourites: boolean }).favourites = value;
  }

  afterAll(() => {
    setFlag(originalFavourites);
  });

  it('blocks /profile/favorites while the flag is off, redirecting to /profile', () => {
    setFlag(false);
    const { router } = setUp();

    const result = runGuard();

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/profile');
  });

  it('passes through once the flag is on', () => {
    setFlag(true);
    setUp();

    expect(runGuard()).toBe(true);
  });
});
