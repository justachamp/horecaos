import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { CommercialApi } from './commercial-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/**
 * The tenant's end of its own module purchase (ADR 0127's status note of
 * 2026-09-30) goes through a real `HttpClient` against a literal expected URL,
 * for the reason `inventory-api.spec.ts` gives: a spec that compares a request
 * to the builder that made it cannot fail when the prefix is wrong.
 */
describe('CommercialApi module purchase and end', () => {
  let api: CommercialApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), CommercialApi],
    });
    api = TestBed.inject(CommercialApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('ends a purchased module with a keyed, body-less POST to the tenant path', async () => {
    const promise = api.endModule('t1', 'tm-1');
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/modules/tm-1/end'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({
      tenantModuleId: 'tm-1',
      endedAt: '2026-09-30T10:00:00Z',
      lastBilledPeriod: '2026-09',
    });

    await expect(promise).resolves.toEqual({
      tenantModuleId: 'tm-1',
      endedAt: '2026-09-30T10:00:00Z',
      lastBilledPeriod: '2026-09',
    });
  });

  it('percent-encodes the ids it puts in the path', async () => {
    const promise = api.endModule('t 1', 'tm/1');
    const request = http.expectOne(url('/api/v1/tenants/t%201/commercial/modules/tm%2F1/end'));
    request.flush({
      tenantModuleId: 'tm/1',
      endedAt: '2026-09-30T10:00:00Z',
      lastBilledPeriod: '2026-09',
    });
    await promise;
  });

  it('keeps the purchase on the same modules path, keyed', async () => {
    const promise = api.purchaseModule('t1', 'module-1', null);
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/modules'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ tenantModuleId: 'tm-2' });
    await promise;
  });
});
