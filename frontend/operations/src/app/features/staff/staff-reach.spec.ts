import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { StaffReach } from './staff-reach';

const TENANT = 'tenant-1';
const BRAND = 'brand-1';
const CHILONZOR = 'location-1';
const YUNUSOBOD = 'location-2';

function reachFor(scopes: readonly ScopeGrant[]): StaffReach {
  TestBed.configureTestingModule({
    providers: [{ provide: CurrentTenant, useValue: { scopes: signal(scopes) } }],
  });
  return TestBed.inject(StaffReach);
}

function grant(
  type: 'TENANT' | 'BRAND' | 'LOCATION',
  location: string | null,
  capabilities: readonly string[],
): ScopeGrant {
  return {
    scope: {
      type,
      tenantId: TENANT,
      brandId: type === 'TENANT' ? null : BRAND,
      locationId: type === 'LOCATION' ? location : null,
    },
    roleCode: 'fixture',
    capabilities,
  };
}

describe('StaffReach', () => {
  it('is company-wide for an owner, whatever else she holds', () => {
    const reach = reachFor([
      grant('TENANT', null, ['IAM_GRANT_MANAGE']),
      grant('LOCATION', CHILONZOR, ['IAM_GRANT_MANAGE']),
    ]);

    expect(reach.tenantWide()).toBe(true);
    expect(reach.scoped()).toBe(false);
  });

  it('is scoped to her own branch for the Chilonzor manager, from the wire spelling of the capability', () => {
    // The session writes an enum name; the catalogue writes a code. Either must be read.
    const reach = reachFor([grant('LOCATION', CHILONZOR, ['ORDER_APPROVE', 'IAM_GRANT_MANAGE'])]);

    expect(reach.scoped()).toBe(true);
    expect(reach.places()).toEqual([
      { type: 'LOCATION', tenantId: TENANT, brandId: BRAND, locationId: CHILONZOR },
    ]);
  });

  it('reads the capability in its code spelling too', () => {
    expect(reachFor([grant('LOCATION', CHILONZOR, ['iam.grant.manage'])]).scoped()).toBe(true);
  });

  it('answers once for a brand and not again for its own branches', () => {
    const reach = reachFor([
      grant('BRAND', null, ['IAM_GRANT_MANAGE']),
      grant('LOCATION', YUNUSOBOD, ['IAM_GRANT_MANAGE']),
    ]);

    expect(reach.places()).toEqual([
      { type: 'BRAND', tenantId: TENANT, brandId: BRAND, locationId: null },
    ]);
  });

  it('is neither scoped nor wider for someone who holds the capability nowhere', () => {
    const reach = reachFor([grant('LOCATION', CHILONZOR, ['ORDER_APPROVE'])]);

    expect(reach.scoped()).toBe(false);
    expect(reach.tenantWide()).toBe(false);
    expect(reach.places()).toEqual([]);
  });
});
