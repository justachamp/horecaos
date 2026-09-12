import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentTenant } from './current-tenant';
import { ScopeGrant } from './session-context';
import { capabilityGuard, navItemForUrl } from './capability.guard';

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly ScopeGrant[]>([]);
  readonly denied = signal(false);
  ensureLoaded = () => Promise.resolve();
}

function grant(capabilities: readonly string[]): ScopeGrant {
  return {
    scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
    roleCode: 'tenant-owner',
    capabilities,
  };
}

describe('navItemForUrl', () => {
  it('finds the rail section a URL belongs to by its first path segment', () => {
    expect(navItemForUrl('/staff')?.path).toBe('/staff');
    expect(navItemForUrl('/staff/roles')?.path).toBe('/staff');
    expect(navItemForUrl('/orders/018f?tab=attention')?.path).toBe('/orders');
    expect(navItemForUrl('/settings/terms')?.path).toBe('/settings');
  });

  it('finds nothing for a URL that names no rail section', () => {
    expect(navItemForUrl('/')).toBeUndefined();
    expect(navItemForUrl('/access-denied')).toBeUndefined();
    expect(navItemForUrl('/login')).toBeUndefined();
  });
});

describe('capabilityGuard', () => {
  let tenant: FakeCurrentTenant;

  beforeEach(() => {
    tenant = new FakeCurrentTenant();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: CurrentTenant, useValue: tenant }],
    });
  });

  function run(url: string): Promise<boolean | UrlTree> {
    return TestBed.runInInjectionContext(() =>
      capabilityGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as Promise<boolean | UrlTree>;
  }

  it('admits a direct URL into a section the operator holds the capability for', async () => {
    tenant.scopes.set([grant(['IAM_GRANT_MANAGE'])]);
    expect(await run('/staff/roles')).toBe(true);
  });

  it('admits a URL that names no rail section at all, unconditionally', async () => {
    expect(await run('/access-denied?capability=IAM_GRANT_MANAGE')).toBe(true);
  });

  it('refuses a direct URL into a section the capability is missing for, naming it in the redirect', async () => {
    tenant.scopes.set([grant(['ORDER_READ'])]);

    const result = await run('/staff/roles');

    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe(
      '/access-denied?capability=IAM_GRANT_MANAGE&section=shell.nav.staff',
    );
  });

  it('refuses a section nested two levels deep the same way as the section itself', async () => {
    tenant.scopes.set([grant(['ORDER_READ'])]);

    const result = await run('/settings/sales-channels');

    expect(String(result)).toBe('/access-denied?capability=TENANT_READ&section=shell.nav.settings');
  });

  it('waits for the session context to load before deciding', async () => {
    let resolveLoad!: () => void;
    tenant.ensureLoaded = () =>
      new Promise((resolve) => {
        resolveLoad = () => {
          tenant.scopes.set([grant(['IAM_GRANT_MANAGE'])]);
          resolve();
        };
      });

    const pending = run('/staff');
    resolveLoad();

    expect(await pending).toBe(true);
  });
});
