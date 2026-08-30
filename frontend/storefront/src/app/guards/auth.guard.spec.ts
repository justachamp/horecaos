import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { provideRouter } from '@angular/router';

import { authGuard } from './auth.guard';
import { Session } from '../core/auth/session';

function setUp() {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  return { session: TestBed.inject(Session), router: TestBed.inject(Router) };
}

function runGuard() {
  return TestBed.runInInjectionContext(() =>
    authGuard({} as never, { url: '/home' } as never),
  );
}

describe('authGuard', () => {
  beforeEach(() => localStorage.clear());

  it('passes through when the session is authenticated', () => {
    const { session } = setUp();
    session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    expect(runGuard()).toBe(true);
  });

  it('redirects to /auth/login when there is no live session', () => {
    setUp();

    const result = runGuard();

    expect(result).toBeInstanceOf(UrlTree);
  });

  it('the redirect actually points at /auth/login', () => {
    const { router } = setUp();

    const result = runGuard() as UrlTree;

    expect(router.serializeUrl(result)).toBe('/auth/login');
  });

  it('redirects when a previously live session has since expired', () => {
    const { session } = setUp();
    const realNow = Date.now;
    try {
      Date.now = () => 1_000_000;
      session.adopt({ accessToken: 'tok', expiresAt: new Date(1_000_500).toISOString() });
      Date.now = () => 2_000_000;

      expect(runGuard()).toBeInstanceOf(UrlTree);
    } finally {
      Date.now = realNow;
    }
  });
});
