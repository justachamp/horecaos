import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AuthService, AuthStatus } from './auth.service';
import { authGuard, requiresCapability } from './guards';
import { Capability } from './capability';
import { SessionContextService } from './session-context.service';

/** The two arguments a CanActivateFn takes and this console's guards ignore. */
const ROUTE = {} as ActivatedRouteSnapshot;
const STATE = {} as RouterStateSnapshot;

class FakeAuth {
  readonly state = signal<AuthStatus>('signed-out');
  readonly status = this.state.asReadonly();
}

class FakeSession {
  held = new Set<string>();

  has(capability: Capability): boolean {
    return this.held.has(capability);
  }

  hasAll(capabilities: readonly Capability[]): boolean {
    return capabilities.every((capability) => this.has(capability));
  }
}

describe('authGuard', () => {
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
  });

  function run(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => authGuard(ROUTE, STATE)) as boolean | UrlTree;
  }

  it('lets a signed-in operator through', () => {
    auth.state.set('signed-in');
    expect(run()).toBe(true);
  });

  it("sends a signed-out visitor to this console's own /login (ADR 0062)", () => {
    auth.state.set('signed-out');
    const result = run();
    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/login');
  });

  it('also sends a not-yet-settled visitor to /login rather than waiting', () => {
    auth.state.set('starting');
    const result = run();
    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/login');
  });
});

describe('requiresCapability', () => {
  let session: FakeSession;

  beforeEach(() => {
    session = new FakeSession();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: SessionContextService, useValue: session }],
    });
  });

  function run(...required: Capability[]): boolean | UrlTree {
    return TestBed.runInInjectionContext(() => requiresCapability(...required)(ROUTE, STATE)) as
      boolean | UrlTree;
  }

  it('admits a principal holding the capability', () => {
    session.held.add('TENANT_READ');
    expect(run('TENANT_READ')).toBe(true);
  });

  it('sends a principal without it to the denied state', () => {
    expect(String(run('TENANT_READ'))).toBe('/denied');
  });

  it('requires every listed capability, not any of them', () => {
    session.held.add('TENANT_READ');
    expect(String(run('TENANT_READ', 'TENANT_WRITE'))).toBe('/denied');
  });

  it('denies while the session context has not loaded', () => {
    // Unknown is not permission. The opposite default would render a full
    // navigation on start and then take half of it away.
    expect(String(run('PLATFORM_ADMIN'))).toBe('/denied');
  });
});
