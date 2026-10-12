import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { ConsoleProfile } from './console-profile';
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

  describe('a branch manager, who pairs a branch’s kiosks (ADR 0162, ADR 0025)', () => {
    /**
     * `PlatformRole.LOCATION_MANAGER`: `kiosk.device.manage` at her branch and no `tenant.read` anywhere. The kiosk's QR
     * opens a page under /settings, which is hers to use and the rest of which is not.
     */
    const LOCATION_MANAGER: ScopeGrant = {
      scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      roleCode: 'location-manager',
      capabilities: [
        'ORDER_READ',
        'IAM_GRANT_MANAGE',
        'TERMINAL_READ',
        'TERMINAL_MANAGE',
        'KIOSK_DEVICE_MANAGE',
      ],
    };

    it('is admitted to the approval page the kiosk’s QR opens, code and all', async () => {
      tenant.scopes.set([LOCATION_MANAGER]);

      expect(await run('/settings/channel-setup/kiosk-pairing?code=K7QX-4M2P')).toBe(true);
      expect(await run('/settings/channel-setup/kiosk-pairing')).toBe(true);
    });

    it('is admitted to the branch’s kiosks, which the Channel setup hub keeps behind channel.read', async () => {
      tenant.scopes.set([LOCATION_MANAGER]);

      expect(await run('/settings/channel-setup/kiosks')).toBe(true);
      expect(await run('/settings/channel-setup/kiosks?location=l1#top')).toBe(true);
    });

    it('is not admitted to the rest of Settings, which is the tenant’s', async () => {
      tenant.scopes.set([LOCATION_MANAGER]);
      const denied = '/access-denied?capability=TENANT_READ&section=shell.nav.settings';

      expect(String(await run('/settings'))).toBe(denied);
      expect(String(await run('/settings/sales-channels'))).toBe(denied);
      expect(String(await run('/settings/channel-setup/some-channel-id'))).toBe(denied);
      expect(String(await run('/settings/channel-setup/kiosks/extra'))).toBe(denied);
      expect(String(await run('/settings/channel-setup/kiosk-pairing-other'))).toBe(denied);
    });

    it('is still refused a section she holds nothing for', async () => {
      tenant.scopes.set([LOCATION_MANAGER]);

      expect(String(await run('/finance'))).toBe(
        '/access-denied?capability=PAYMENT_READ&section=shell.nav.finance',
      );
    });

    it('is refused the pages too when she cannot pair kiosks, naming the section’s own capability', async () => {
      tenant.scopes.set([{ ...LOCATION_MANAGER, capabilities: ['ORDER_READ', 'TERMINAL_READ'] }]);

      expect(String(await run('/settings/channel-setup/kiosk-pairing'))).toBe(
        '/access-denied?capability=TENANT_READ&section=shell.nav.settings',
      );
    });

    it('leaves a tenant owner, who reads the tenant, in all of Settings', async () => {
      tenant.scopes.set([grant(['TENANT_READ'])]);

      expect(await run('/settings/channel-setup/kiosk-pairing')).toBe(true);
      expect(await run('/settings/sales-channels')).toBe(true);
    });
  });

  describe('a deliveries console profile (ADR 0164)', () => {
    /** The profile puts the kitchen, marketing and the inbox away and opens on /deliveries. */
    const deliveriesProfile = {
      variant: signal<'STANDARD' | 'DELIVERIES'>('DELIVERIES'),
      putAway: signal(['/kitchen', '/marketing', '/inbox']),
      hiddenSections: signal(['/kitchen', '/marketing', '/inbox']),
      ensureLoaded: () => Promise.resolve(),
    };

    beforeEach(() => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          { provide: CurrentTenant, useValue: tenant },
          { provide: ConsoleProfile, useValue: deliveriesProfile },
        ],
      });
    });

    it('only puts a section away: a holder who types its URL is still let in', async () => {
      tenant.scopes.set([grant(['KITCHEN_TICKET_READ'])]);

      expect(await run('/kitchen/buffer')).toBe(true);
    });

    it('and grants nothing: a section put away stays refused to someone without its capability', async () => {
      tenant.scopes.set([grant(['ORDER_READ'])]);

      const result = await run('/kitchen/buffer');

      expect(result).toBeInstanceOf(UrlTree);
      expect(String(result)).toContain('capability=KITCHEN_TICKET_READ');
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
