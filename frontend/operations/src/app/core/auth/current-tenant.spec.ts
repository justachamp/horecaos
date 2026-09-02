import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { CurrentTenant } from './current-tenant';
import { SessionContext } from './session-context';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('CurrentTenant', () => {
  let http: HttpTestingController;
  let tenant: CurrentTenant;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient, CurrentTenant],
    });
    tenant = TestBed.inject(CurrentTenant);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is unresolved and not yet denied before the session context arrives', () => {
    expect(tenant.tenantId()).toBeNull();
    expect(tenant.denied()).toBe(false);
    expect(tenant.scopes()).toEqual([]);
  });

  it('reads activeTenantId directly, not derived from scopes', async () => {
    const promise = tenant.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush({
      subject: 'operator-1',
      activeTenantId: 't1',
      scopes: [
        {
          scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
          roleCode: 'tenant-owner',
          capabilities: ['iam.grant.manage'],
        },
      ],
    } satisfies SessionContext);
    await promise;

    expect(tenant.tenantId()).toBe('t1');
    expect(tenant.denied()).toBe(false);
    expect(tenant.scopes()).toHaveLength(1);
  });

  it('is denied once loaded with no active tenant, never before', async () => {
    const promise = tenant.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush({
      subject: 'platform-admin-1',
      activeTenantId: null,
      scopes: [],
    } satisfies SessionContext);
    await promise;

    expect(tenant.tenantId()).toBeNull();
    expect(tenant.denied()).toBe(true);
  });

  it('treats an unreachable session-context call as denied rather than hanging forever', async () => {
    const promise = tenant.ensureLoaded();
    http
      .expectOne(url('/api/v1/session/context'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await promise;

    expect(tenant.tenantId()).toBeNull();
    expect(tenant.denied()).toBe(true);
  });

  it('fetches the session context exactly once no matter how many callers await it', async () => {
    const first = tenant.ensureLoaded();
    const second = tenant.ensureLoaded();

    http.expectOne(url('/api/v1/session/context')).flush({
      subject: 'operator-1',
      activeTenantId: 't1',
      scopes: [],
    } satisfies SessionContext);
    await Promise.all([first, second]);

    await tenant.ensureLoaded();
  });
});
