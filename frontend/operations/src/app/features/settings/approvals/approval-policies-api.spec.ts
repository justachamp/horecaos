import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { ApprovalPoliciesApi } from './approval-policies-api';

const POLICIES = '/api/v1/operations/tenants/t1/approval-policies';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('ApprovalPoliciesApi', () => {
  let api: ApprovalPoliciesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApprovalPoliciesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the coverage from the operations surface, not the control plane', async () => {
    const promise = api.coverage('t1');

    const request = http.expectOne(url(`${POLICIES}/coverage`));
    expect(request.request.method).toBe('GET');
    request.flush([
      {
        actionCode: 'payments.remedy.record',
        missingPolicyMode: 'ALLOW_WITHOUT_APPROVAL',
        worklist: 'TENANT',
        configuredAnywhere: false,
        configuredScopes: [],
      },
    ]);

    expect((await promise)[0].worklist).toBe('TENANT');
  });

  it('publishes the next version in the body, with its own idempotency key', async () => {
    const promise = api.author('t1', {
      actionCode: 'customer.pii.export',
      scopeType: 'BRAND',
      brandId: 'b1',
      locationId: null,
      thresholdDescription: 'Any export',
      requiredApproverCapability: 'approval.decide',
      validFrom: null,
      reason: 'Finance asked',
    });

    const request = http.expectOne(url(POLICIES));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({
      actionCode: 'customer.pii.export',
      scopeType: 'BRAND',
      brandId: 'b1',
      locationId: null,
      thresholdDescription: 'Any export',
      requiredApproverCapability: 'approval.decide',
      validFrom: null,
      reason: 'Finance asked',
    });
    request.flush({ id: 'p1', version: 1 });

    expect((await promise).id).toBe('p1');
  });

  it('end-dates a version at its own expiry route, effective now', async () => {
    const promise = api.endDate('t1', 'p 1', 'No longer needed');

    const request = http.expectOne(url(`${POLICIES}/p%201/expiry`));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ effectiveAt: null, reason: 'No longer needed' });
    request.flush({ id: 'p 1', validUntil: '2026-10-14T05:00:00Z' });

    expect((await promise).validUntil).toBe('2026-10-14T05:00:00Z');
  });
});
