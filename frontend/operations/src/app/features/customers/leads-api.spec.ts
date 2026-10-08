import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { LeadReach } from '../../core/api/lead-paths';
import { firstPage } from '../../core/api/page';
import { LeadsApi } from './leads-api';

const BRAND: LeadReach = { kind: 'BRAND', tenantId: 't1', brandId: 'b1' };
const BRANCH: LeadReach = { kind: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

describe('LeadsApi', () => {
  let api: LeadsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(LeadsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists a brand’s queue on the brand route, narrowed by the attention view and by status', async () => {
    const promise = api.list(BRAND, firstPage(), {
      view: 'attention',
      status: ['NEW', 'CALLBACK_SCHEDULED'],
      source: 'B2B_CATERING_ENQUIRY',
      unassigned: true,
    });

    const request = http.expectOne((r) => r.url === url('/api/v1/tenants/t1/brands/b1/leads'));
    expect(request.request.params.get('view')).toBe('attention');
    expect(request.request.params.getAll('status')).toEqual(['NEW', 'CALLBACK_SCHEDULED']);
    expect(request.request.params.get('source')).toBe('B2B_CATERING_ENQUIRY');
    expect(request.request.params.get('unassigned')).toBe('true');
    request.flush({ items: [], nextCursor: null });

    expect((await promise).items).toEqual([]);
  });

  it('reads a branch’s own leads on the branch route', async () => {
    const promise = api.list(BRANCH, firstPage());

    http
      .expectOne((r) => r.url === url('/api/v1/tenants/t1/brands/b1/locations/l1/leads'))
      .flush({ items: [], nextCursor: null });

    await promise;
  });

  it('registers with an Idempotency-Key and the number in the body, never in the path', async () => {
    const promise = api.register(BRAND, { source: 'CALLBACK_REQUEST', phone: '+998 90 123 45 67' });

    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/leads'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.url).not.toContain('998');
    expect(request.request.body).toEqual({
      source: 'CALLBACK_REQUEST',
      phone: '+998 90 123 45 67',
    });
    request.flush({ id: 'lead-1' });

    await promise;
  });

  it('moves a lead with the version it was read at in If-Match', async () => {
    const promise = api.transition(BRAND, 'lead-1', { target: 'CONTACTED' }, 3);

    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/leads/lead-1/transitions'));
    expect(request.request.headers.get('If-Match')).toContain('3');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ id: 'lead-1' });

    await promise;
  });

  it('hands a lead to a branch through the brand’s assignment route', async () => {
    const promise = api.assign(BRAND, 'lead-1', 'l9', 4);

    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/leads/lead-1/assignment'));
    expect(request.request.body).toEqual({ locationId: 'l9' });
    expect(request.request.headers.get('If-Match')).toContain('4');
    request.flush({ id: 'lead-1' });

    await promise;
  });

  it('reveals the contact with the purpose in the query', async () => {
    const promise = api.reveal(BRANCH, 'lead-1', 'Calling back');

    const request = http.expectOne(
      (r) => r.url === url('/api/v1/tenants/t1/brands/b1/locations/l1/leads/lead-1/contact'),
    );
    expect(request.request.params.get('purpose')).toBe('Calling back');
    request.flush({ phone: '+998901234567', displayName: null, notes: null });

    expect((await promise).phone).toBe('+998901234567');
  });

  it('opens the card with the purpose, and pages back with the instant it was handed', async () => {
    const promise = api.openCard(
      't1',
      'c1',
      'Operations console: open customer card',
      '2026-09-01T00:00:00Z',
    );

    const request = http.expectOne((r) => r.url === url('/api/v1/tenants/t1/customers/c1/card'));
    expect(request.request.params.get('purpose')).toBe('Operations console: open customer card');
    expect(request.request.params.get('before')).toBe('2026-09-01T00:00:00Z');
    request.flush({ customerAccountId: 'c1', history: [], leads: [], nextBefore: null });

    expect((await promise).customerAccountId).toBe('c1');
  });

  it('records a call about an account holder with the brand the call was on', async () => {
    const promise = api.recordCustomerAttempt('t1', 'c1', 'b1', {
      direction: 'INBOUND',
      outcome: 'CONNECTED',
    });

    const request = http.expectOne(url('/api/v1/tenants/t1/customers/c1/contact-attempts'));
    expect(request.request.body).toEqual({
      brandId: 'b1',
      direction: 'INBOUND',
      outcome: 'CONNECTED',
    });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ id: 'a1' });

    await promise;
  });
});
