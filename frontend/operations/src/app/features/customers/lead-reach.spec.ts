import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { LeadReachResolver } from './lead-reach';

const TENANT = 'tenant-1';
const BRAND = 'brand-1';
const CHILONZOR = 'location-chilonzor';

function grant(
  type: 'TENANT' | 'BRAND' | 'LOCATION',
  capabilities: readonly string[],
  location: string | null = null,
): ScopeGrant {
  return {
    scope: {
      type,
      tenantId: TENANT,
      brandId: type === 'TENANT' ? null : BRAND,
      locationId: location,
    },
    roleCode: 'fixture',
    capabilities,
  };
}

function resolver(
  scopes: readonly ScopeGrant[],
  brand: BrandScope | null = { tenantId: TENANT, brandId: BRAND },
) {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: CurrentTenant,
        useValue: { ensureLoaded: vi.fn().mockResolvedValue(undefined), scopes: signal(scopes) },
      },
      {
        provide: CurrentBrand,
        useValue: { ensureLoaded: vi.fn().mockResolvedValue(undefined), scope: signal(brand) },
      },
    ],
  });
  return TestBed.inject(LeadReachResolver);
}

describe('LeadReachResolver', () => {
  it('sends an owner to the brand routes, with every right she holds', async () => {
    const access = await resolver([
      grant('TENANT', ['CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE', 'CUSTOMER_PII_REVEAL']),
    ]).resolve();

    expect(access).toEqual({
      reach: { kind: 'BRAND', tenantId: TENANT, brandId: BRAND },
      canManage: true,
      canReveal: true,
    });
  });

  it('a call-centre brand manager works the queue but cannot reveal a number', async () => {
    const access = await resolver([
      grant('BRAND', ['CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE']),
    ]).resolve();

    expect(access?.reach.kind).toBe('BRAND');
    expect(access?.canManage).toBe(true);
    expect(access?.canReveal).toBe(false);
  });

  it('support reads the queue and does not work it', async () => {
    const access = await resolver([grant('TENANT', ['CUSTOMER_LEAD_READ'])]).resolve();

    expect(access?.canManage).toBe(false);
    expect(access?.canReveal).toBe(false);
  });

  it('a branch manager is sent to her own branch’s routes, not the brand’s', async () => {
    const access = await resolver([
      grant(
        'LOCATION',
        ['CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE', 'CUSTOMER_PII_REVEAL'],
        CHILONZOR,
      ),
    ]).resolve();

    expect(access).toEqual({
      reach: { kind: 'LOCATION', tenantId: TENANT, brandId: BRAND, locationId: CHILONZOR },
      canManage: true,
      canReveal: true,
    });
  });

  it('reads the capability in either spelling the server uses', async () => {
    const access = await resolver([grant('LOCATION', ['customer.lead.read'], CHILONZOR)]).resolve();

    expect(access?.reach.kind).toBe('LOCATION');
  });

  it('is nothing for a line cook, who holds no lead capability anywhere', async () => {
    const access = await resolver([
      grant('LOCATION', ['ORDER_APPROVE', 'KITCHEN_TICKET_ADVANCE'], CHILONZOR),
    ]).resolve();

    expect(access).toBeNull();
  });

  it('falls back to her branch when a company-wide grant names no brand to look at', async () => {
    const access = await resolver(
      [
        grant('TENANT', ['CUSTOMER_LEAD_READ']),
        grant('LOCATION', ['CUSTOMER_LEAD_READ'], CHILONZOR),
      ],
      null,
    ).resolve();

    expect(access?.reach.kind).toBe('LOCATION');
  });
});
