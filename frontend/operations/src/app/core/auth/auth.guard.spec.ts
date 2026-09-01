import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { Auth } from './auth';
import { RETURN_TO_KEY, authGuard } from './auth.guard';

class FakeAuth {
  readonly authenticated = signal(false);
  readonly isAuthenticated = this.authenticated.asReadonly();
}

/**
 * What can and cannot be proven here.
 *
 * These tests prove the guard's decision and its side effects against a fake
 * {@link Auth}. They do not prove the credential exchange itself — that is
 * `Auth`'s own suite's job, against `HttpTestingController`.
 */
describe('authGuard', () => {
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: Auth, useValue: auth }],
    });
    sessionStorage.clear();
  });

  function run(url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  it('admits a signed-in operator', () => {
    auth.authenticated.set(true);
    expect(run('/orders/018f')).toBe(true);
  });

  it("sends a signed-out visitor to this console's own /login (ADR 0062)", () => {
    auth.authenticated.set(false);
    const result = run('/orders/018f');
    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/login');
  });

  it('remembers the deep link so a shared order link survives a sign-in', () => {
    // The reason this matters: a supervisor sends a colleague a link to one
    // late order. Without this the colleague lands on Today and has to find
    // it again, which is the moment the link stopped being useful.
    auth.authenticated.set(false);
    run('/orders/018f-late-one');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBe('/orders/018f-late-one');
  });
});
