import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { captureSupportTenant, leaveSupportTenant, supportTenant, supportTenantInterceptor } from './support-tenant';

const TENANT = '018f6f4e-899d-7b1c-a8cf-0242ac1281a1';

describe('support tenant (ADR 0081)', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([supportTenantInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('remembers only a well-formed tenant id from the address the control plane opened', () => {
    captureSupportTenant('?supportTenant=not-a-tenant');
    expect(supportTenant()).toBeNull();

    captureSupportTenant(`?supportTenant=${TENANT}`);
    expect(supportTenant()).toBe(TENANT);
  });

  it('names the support tenant on the session-context read, and on nothing else', () => {
    captureSupportTenant(`?supportTenant=${TENANT}`);

    http.get('/api/v1/session/context').subscribe();
    http.get('/api/v1/operations/tenants/x/orders').subscribe();

    expect(controller.expectOne((request) => request.url === '/api/v1/session/context').request.params.get('tenantId')).toBe(
      TENANT,
    );
    expect(controller.expectOne('/api/v1/operations/tenants/x/orders').request.params.has('tenantId')).toBe(false);
  });

  it('leaves ordinary staff untouched, and stops once the person leaves', () => {
    http.get('/api/v1/session/context').subscribe();
    expect(controller.expectOne('/api/v1/session/context').request.params.has('tenantId')).toBe(false);

    captureSupportTenant(`?supportTenant=${TENANT}`);
    leaveSupportTenant();
    http.get('/api/v1/session/context').subscribe();
    expect(controller.expectOne('/api/v1/session/context').request.params.has('tenantId')).toBe(false);
  });
});
