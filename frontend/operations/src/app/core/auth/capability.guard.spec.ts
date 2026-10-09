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

  describe("a section a role reaches by another capability than the section's own", () => {
    /**
     * `PlatformRole.BRAND_MANAGER`'s customer capabilities at her brand: the call centre's queue and
     * nothing of the customer list (`LeadControllerEndpointTests` pins that the session context the
     * server builds for her carries exactly this).
     */
    const BRAND_MANAGER: ScopeGrant = {
      scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
      roleCode: 'brand-manager',
      capabilities: ['ORDER_READ', 'CATALOG_READ', 'CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE'],
    };

    it('admits a brand manager to the lead queue under /customers, though she holds no customer.read', async () => {
      tenant.scopes.set([BRAND_MANAGER]);

      expect(await run('/customers/leads')).toBe(true);
      expect(await run('/customers')).toBe(true);
    });

    it("still refuses an operator who holds neither, naming the section's own capability", async () => {
      tenant.scopes.set([grant(['ORDER_READ', 'CUSTOMER_LEAD_MANAGE'])]);

      const result = await run('/customers/leads');

      expect(String(result)).toBe(
        '/access-denied?capability=CUSTOMER_READ&section=shell.nav.customers',
      );
    });
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
