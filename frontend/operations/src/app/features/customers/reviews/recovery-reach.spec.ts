import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ScopeGrant } from '../../../core/auth/session-context';
import { RecoveryReachResolver } from './recovery-reach';

const TENANT = 'tenant-1';
const BRAND: BrandScope = { tenantId: TENANT, brandId: 'brand-1' };

function grant(
  type: 'TENANT' | 'BRAND' | 'LOCATION',
  capabilities: readonly string[],
  where: { brandId?: string | null; locationId?: string | null; tenantId?: string } = {},
): ScopeGrant {
  return {
    scope: {
      type,
      tenantId: where.tenantId ?? TENANT,
      brandId: type === 'TENANT' ? null : (where.brandId ?? 'brand-1'),
      locationId: type === 'LOCATION' ? (where.locationId ?? 'location-1') : null,
    },
    roleCode: 'fixture',
    capabilities,
  };
}

function resolver(scopes: readonly ScopeGrant[]): RecoveryReachResolver {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: CurrentTenant,
        useValue: { ensureLoaded: vi.fn().mockResolvedValue(undefined), scopes: signal(scopes) },
      },
    ],
  });
  return TestBed.inject(RecoveryReachResolver);
}

describe('RecoveryReachResolver', () => {
  it('an owner or administrator works the brand worklist', async () => {
    const reach = await resolver([grant('TENANT', ['RECOVERY_CASE_MANAGE'])]).resolve(BRAND);

    expect(reach).toEqual({ scope: BRAND, locationIds: null });
  });

  it('a brand manager works the brand worklist', async () => {
    const reach = await resolver([grant('BRAND', ['RECOVERY_CASE_MANAGE'])]).resolve(BRAND);

    expect(reach?.locationIds).toBeNull();
  });

  it('a branch manager works her own branch and is not sent to the brand worklist, which refuses her', async () => {
    const reach = await resolver([
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { locationId: 'location-chilonzor' }),
    ]).resolve(BRAND);

    expect(reach).toEqual({ scope: BRAND, locationIds: ['location-chilonzor'] });
  });

  it('a manager of two branches works both, each once', async () => {
    const reach = await resolver([
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { locationId: 'location-a' }),
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { locationId: 'location-b' }),
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { locationId: 'location-a' }),
    ]).resolve(BRAND);

    expect(reach?.locationIds).toEqual(['location-a', 'location-b']);
  });

  it('a branch grant without the capability, or in another brand, opens nothing', async () => {
    const reach = await resolver([
      grant('LOCATION', ['REVIEW_READ'], { locationId: 'location-a' }),
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { brandId: 'brand-2', locationId: 'location-x' }),
      grant('BRAND', ['RECOVERY_CASE_MANAGE'], { brandId: 'brand-2' }),
      grant('TENANT', ['RECOVERY_CASE_MANAGE'], { tenantId: 'tenant-2' }),
    ]).resolve(BRAND);

    expect(reach).toBeNull();
  });

  it('a brand-wide grant wins over a branch grant beside it', async () => {
    const reach = await resolver([
      grant('LOCATION', ['RECOVERY_CASE_MANAGE'], { locationId: 'location-a' }),
      grant('BRAND', ['RECOVERY_CASE_MANAGE']),
    ]).resolve(BRAND);

    expect(reach?.locationIds).toBeNull();
  });

  it('reads the capability by its code as well as by its name', async () => {
    const reach = await resolver([
      grant('LOCATION', ['recovery.case.manage'], { locationId: 'location-a' }),
    ]).resolve(BRAND);

    expect(reach?.locationIds).toEqual(['location-a']);
  });
});
