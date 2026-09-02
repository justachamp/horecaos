import { describe, expect, it } from 'vitest';

import { ScopeGrant } from '../../core/auth/session-context';
import { brandScope, canGrantAt, covers, locationScope, tenantScope } from './scope-coverage';

const TENANT = 'tenant-1';
const BRAND = 'brand-1';
const LOCATION = 'location-1';
const OTHER_LOCATION = 'location-2';

describe('covers', () => {
  it('lets a tenant scope cover a location beneath it', () => {
    expect(covers(tenantScope(TENANT), locationScope(TENANT, BRAND, LOCATION))).toBe(true);
  });

  it('never lets a location scope cover a sibling location', () => {
    expect(
      covers(locationScope(TENANT, BRAND, LOCATION), locationScope(TENANT, BRAND, OTHER_LOCATION)),
    ).toBe(false);
  });

  it('never covers upward', () => {
    expect(covers(locationScope(TENANT, BRAND, LOCATION), tenantScope(TENANT))).toBe(false);
  });

  it('a scope covers itself', () => {
    expect(covers(brandScope(TENANT, BRAND), brandScope(TENANT, BRAND))).toBe(true);
  });
});

describe('canGrantAt', () => {
  /** The scenario staff-and-access.md §0 names by example: courier capabilities the owner does not hold. */
  it('refuses a job whose capabilities are not a subset of what the operator holds at the target scope', () => {
    const scopes: readonly ScopeGrant[] = [
      {
        scope: { type: 'TENANT', tenantId: TENANT, brandId: null, locationId: null },
        roleCode: 'tenant-owner',
        capabilities: ['order.read', 'order.cancel'],
      },
    ];

    expect(canGrantAt(scopes, tenantScope(TENANT), ['order.read', 'order.cancel'])).toBe(true);
    expect(canGrantAt(scopes, tenantScope(TENANT), ['courier.duty.manage'])).toBe(false);
  });

  it('unions capabilities across every covering scope, not just the closest one', () => {
    const scopes: readonly ScopeGrant[] = [
      {
        scope: { type: 'TENANT', tenantId: TENANT, brandId: null, locationId: null },
        roleCode: 'tenant-finance',
        capabilities: ['payment.read'],
      },
      {
        scope: { type: 'LOCATION', tenantId: TENANT, brandId: BRAND, locationId: LOCATION },
        roleCode: 'location-manager',
        capabilities: ['order.cancel'],
      },
    ];

    expect(
      canGrantAt(scopes, locationScope(TENANT, BRAND, LOCATION), ['payment.read', 'order.cancel']),
    ).toBe(true);
  });

  it('never lets a location-scoped grant reach a sibling location', () => {
    const scopes: readonly ScopeGrant[] = [
      {
        scope: { type: 'LOCATION', tenantId: TENANT, brandId: BRAND, locationId: LOCATION },
        roleCode: 'location-manager',
        capabilities: ['order.cancel'],
      },
    ];

    expect(canGrantAt(scopes, locationScope(TENANT, BRAND, OTHER_LOCATION), ['order.cancel'])).toBe(
      false,
    );
  });

  it('treats a scope with no capabilities field as empty rather than throwing', () => {
    const scopes: readonly ScopeGrant[] = [
      {
        scope: { type: 'TENANT', tenantId: TENANT, brandId: null, locationId: null },
        roleCode: 'legacy-fixture',
      },
    ];

    expect(canGrantAt(scopes, tenantScope(TENANT), ['order.cancel'])).toBe(false);
  });

  it('an empty capability requirement is always satisfied', () => {
    expect(canGrantAt([], tenantScope(TENANT), [])).toBe(true);
  });
});
