import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { GrantView, StaffApi, scopeFor } from './staff-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const GRANT: GrantView = {
  id: 'g1',
  principalSubject: 'subject-1',
  roleCode: 'location-staff',
  scopeType: 'LOCATION',
  scopeId: 'l1',
  status: 'ACTIVE',
  grantedBy: 'owner-1',
  reason: 'Onboarded',
  validFrom: '2026-08-20T10:00:00Z',
  validUntil: null,
  revokedAt: null,
  revokedBy: null,
  revokedReason: null,
};

describe('StaffApi', () => {
  let api: StaffApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), StaffApi],
    });
    api = TestBed.inject(StaffApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists grants active-only by default', async () => {
    const promise = api.listGrants('t1');
    const request = http.expectOne(
      (candidate) => candidate.url === url('/api/v1/control-plane/tenants/t1/grants'),
    );
    expect(request.request.params.get('includeInactive')).toBe('false');
    request.flush([GRANT]);

    expect(await promise).toEqual([GRANT]);
  });

  it('passes includeInactive through when the caller wants revoked grants too', async () => {
    const promise = api.listGrants('t1', true);
    const request = http.expectOne(
      (candidate) => candidate.url === url('/api/v1/control-plane/tenants/t1/grants'),
    );
    expect(request.request.params.get('includeInactive')).toBe('true');
    request.flush([GRANT]);

    await promise;
  });

  it('grants with an Idempotency-Key and the request body untouched', async () => {
    const promise = api.grant('t1', {
      principalSubject: 'subject-2',
      roleCode: 'location-manager',
      locationId: 'l1',
      brandId: 'b1',
      reason: 'Hired for Chilonzor',
    });
    const request = http.expectOne(url('/api/v1/control-plane/tenants/t1/grants'));

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({
      principalSubject: 'subject-2',
      roleCode: 'location-manager',
      locationId: 'l1',
      brandId: 'b1',
      reason: 'Hired for Chilonzor',
    });
    request.flush({ grantId: 'g2' });

    expect(await promise).toEqual({ grantId: 'g2' });
  });

  it('revokes with a DELETE carrying the reason in the body', async () => {
    const promise = api.revoke('t1', 'g1', 'Left the company');
    const request = http.expectOne(url('/api/v1/control-plane/tenants/t1/grants/g1'));

    expect(request.request.method).toBe('DELETE');
    expect(request.request.body).toEqual({ reason: 'Left the company' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ changed: true, outcome: 'revoked' });

    expect(await promise).toEqual({ changed: true, outcome: 'revoked' });
  });

  it('reads the tenant-visible role catalogue', async () => {
    const promise = api.roles('t1');
    http
      .expectOne(url('/api/v1/control-plane/tenants/t1/roles'))
      .flush([{ code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read'] }]);

    expect(await promise).toEqual([
      { code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read'] },
    ]);
  });

  it('reads staff Telegram links', async () => {
    const promise = api.telegramLinks('t1');
    http
      .expectOne(url('/api/v1/tenants/t1/staff/telegram/links'))
      .flush([
        { principalSubject: 'subject-1', telegramUserId: 555, linkedAt: '2026-09-01T00:00:00Z' },
      ]);

    expect(await promise).toEqual([
      { principalSubject: 'subject-1', telegramUserId: 555, linkedAt: '2026-09-01T00:00:00Z' },
    ]);
  });

  it('resolves the scope directory by fanning out one locations call per brand', async () => {
    const promise = api.scopeDirectory('t1');

    http.expectOne(url('/api/v1/operations/tenants/t1/brands')).flush([
      { id: 'b1', displayName: 'Milliy' },
      { id: 'b2', displayName: 'Second' },
    ]);
    // The per-brand fan-out starts inside the brands promise's own `.then`,
    // one microtask after `flush` resolves it — never issued in the same
    // synchronous tick `flush` runs in.
    await Promise.resolve();
    await Promise.resolve();

    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }]);
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b2/locations'))
      .flush([{ id: 'l2', brandId: 'b2', displayName: 'Yunusobod' }]);

    expect(await promise).toEqual({
      brands: [
        { id: 'b1', displayName: 'Milliy' },
        { id: 'b2', displayName: 'Second' },
      ],
      locations: [
        { id: 'l1', brandId: 'b1', displayName: 'Chilonzor' },
        { id: 'l2', brandId: 'b2', displayName: 'Yunusobod' },
      ],
    });
  });

  it('fetches nothing further for a tenant with no brands', async () => {
    const promise = api.scopeDirectory('t1');
    http.expectOne(url('/api/v1/operations/tenants/t1/brands')).flush([]);

    expect(await promise).toEqual({ brands: [], locations: [] });
  });
});

describe('scopeFor', () => {
  it('builds a TENANT scope with brand and location stripped', () => {
    expect(scopeFor('TENANT', 't1', 'b1', 'l1')).toEqual({
      type: 'TENANT',
      tenantId: 't1',
      brandId: null,
      locationId: null,
    });
  });

  it('builds a BRAND scope with location stripped', () => {
    expect(scopeFor('BRAND', 't1', 'b1', 'l1')).toEqual({
      type: 'BRAND',
      tenantId: 't1',
      brandId: 'b1',
      locationId: null,
    });
  });

  it('builds a LOCATION scope carrying all three', () => {
    expect(scopeFor('LOCATION', 't1', 'b1', 'l1')).toEqual({
      type: 'LOCATION',
      tenantId: 't1',
      brandId: 'b1',
      locationId: 'l1',
    });
  });

  it('builds a PLATFORM scope with every identifier null', () => {
    expect(scopeFor('PLATFORM', 't1', 'b1', 'l1')).toEqual({
      type: 'PLATFORM',
      tenantId: null,
      brandId: null,
      locationId: null,
    });
  });
});
