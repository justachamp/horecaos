import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { ConsentType, DataPrivacyApi, TenantErasureRequest } from './data-privacy-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

const ERASURE_REQUEST: TenantErasureRequest = {
  id: 'erasure-1',
  customerAccountId: 'cust-1',
  status: 'PENDING',
  requestedVia: 'STOREFRONT',
  requestedByActorType: 'CUSTOMER',
  requestedByActorId: 'cust-1',
  requestedAt: '2026-09-01T10:00:00Z',
  completedAt: null,
  completedByActorId: null,
  cancelledAt: null,
  cancelledByActorId: null,
};

const CONSENT_TYPE: ConsentType = {
  id: 'consent-1',
  code: 'MARKETING_SMS',
  labelRu: 'СМС-маркетинг',
  labelUz: 'SMS marketing',
  labelEn: 'Marketing SMS',
  description: null,
  channelSpecific: true,
  policyVersion: '1',
  active: true,
  updatedAt: '2026-09-01T10:00:00Z',
};

/**
 * A dedicated HttpTestingController-backed suite for exactly the bug the W03
 * adversarial review found: `data-privacy-page.spec.ts` mocks `DataPrivacyApi`
 * directly, so no test in the codebase ever built the real request URL — and
 * `settingsPaths.erasureRequests` was missing the `/customers` segment
 * `CustomerController.tenantErasureRequests` actually requires, 404ing on
 * every real load. This file, mirroring `staff-api.spec.ts`, asserts the
 * literal path instead of a mocked method call, so a future path/route drift
 * between `settings-paths.ts` and the controller fails here rather than
 * shipping silently.
 */
describe('DataPrivacyApi', () => {
  let api: DataPrivacyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), DataPrivacyApi],
    });
    api = TestBed.inject(DataPrivacyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the tenant-wide erasure worklist from CustomerController.tenantErasureRequests’ real route', async () => {
    const promise = api.erasureWorklist('t1');
    const request = http.expectOne(
      (candidate) => candidate.url === url('/api/v1/tenants/t1/customers/erasure-requests'),
    );
    expect(request.request.method).toBe('GET');
    request.flush([ERASURE_REQUEST]);

    expect(await promise).toEqual([ERASURE_REQUEST]);
  });

  it('passes an optional status filter and the default limit', async () => {
    const promise = api.erasureWorklist('t1', 'PENDING');
    const request = http.expectOne(
      (candidate) => candidate.url === url('/api/v1/tenants/t1/customers/erasure-requests'),
    );
    expect(request.request.params.get('status')).toBe('PENDING');
    expect(request.request.params.get('limit')).toBe('200');
    request.flush([]);

    await promise;
  });

  it('reads the consent-type registry from ConsentTypeController’s own route', async () => {
    const promise = api.consentTypes('t1');
    const request = http.expectOne(
      (candidate) => candidate.url === url('/api/v1/tenants/t1/consent-types'),
    );
    expect(request.request.method).toBe('GET');
    request.flush([CONSENT_TYPE]);

    expect(await promise).toEqual([CONSENT_TYPE]);
  });
});
