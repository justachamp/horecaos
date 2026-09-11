import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { ScopeGrant } from './session-context';
import { CurrentTenant } from './current-tenant';
import { SessionCapabilities } from './session-capabilities';

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly ScopeGrant[]>([]);
  readonly denied = signal(false);
  ensureLoaded = () => Promise.resolve();
}

function grant(capabilities: readonly string[], scope: ScopeGrant['scope']): ScopeGrant {
  return { scope, roleCode: 'some-role', capabilities };
}

function setUp(): { capabilities: SessionCapabilities; tenant: FakeCurrentTenant } {
  const tenant = new FakeCurrentTenant();
  TestBed.configureTestingModule({
    providers: [{ provide: CurrentTenant, useValue: tenant }],
  });
  return { capabilities: TestBed.inject(SessionCapabilities), tenant };
}

describe('SessionCapabilities', () => {
  it('holds nothing before the session context has loaded', () => {
    const { capabilities } = setUp();
    expect(capabilities.has('ORDER_READ')).toBe(false);
  });

  it('holds a capability granted at any one of the operator’s scopes', () => {
    const { capabilities, tenant } = setUp();
    tenant.scopes.set([
      grant(['ORDER_READ', 'KITCHEN_TICKET_READ'], {
        type: 'LOCATION',
        tenantId: 't1',
        brandId: 'b1',
        locationId: 'l1',
      }),
    ]);

    expect(capabilities.has('ORDER_READ')).toBe(true);
    expect(capabilities.has('KITCHEN_TICKET_READ')).toBe(true);
    expect(capabilities.has('IAM_GRANT_MANAGE')).toBe(false);
  });

  it('unions capabilities across every scope the operator holds, not just the first', () => {
    const { capabilities, tenant } = setUp();
    tenant.scopes.set([
      grant(['DELIVERY_PLAN_READ'], {
        type: 'BRAND',
        tenantId: 't1',
        brandId: 'b1',
        locationId: null,
      }),
      grant(['REPORTING_READ'], {
        type: 'TENANT',
        tenantId: 't1',
        brandId: null,
        locationId: null,
      }),
    ]);

    expect(capabilities.has('DELIVERY_PLAN_READ')).toBe(true);
    expect(capabilities.has('REPORTING_READ')).toBe(true);
  });

  it('treats a scope with no capabilities field as holding none, never throwing', () => {
    const { capabilities, tenant } = setUp();
    tenant.scopes.set([
      { scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null }, roleCode: 'x' },
    ]);

    expect(capabilities.has('TENANT_READ')).toBe(false);
  });

  it('delegates ensureLoaded to CurrentTenant rather than fetching a second time', async () => {
    const { capabilities, tenant } = setUp();
    let called = 0;
    tenant.ensureLoaded = () => {
      called += 1;
      return Promise.resolve();
    };

    await capabilities.ensureLoaded();

    expect(called).toBe(1);
  });
});
