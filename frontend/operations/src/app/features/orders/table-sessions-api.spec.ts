import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { TableSessionsApi } from './table-sessions-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/dine-in/sessions`;

function setUp(): { api: TableSessionsApi; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), TableSessionsApi],
  });
  return { api: TestBed.inject(TableSessionsApi), http: TestBed.inject(HttpTestingController) };
}

function session(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    sessionId: 's1',
    reservationId: 'r1',
    partySize: 4,
    businessDate: '2026-09-22',
    openedAt: '2026-09-22T10:00:00Z',
    status: 'OPEN',
    serviceChargeRateBp: null,
    currency: 'UZS',
    settledTotalMinor: null,
    closedAt: null,
    closeReasonCode: null,
    version: 1,
    ...overrides,
  };
}

describe('TableSessionsApi.open', () => {
  it('posts with an Idempotency-Key', () => {
    const { api, http } = setUp();

    api
      .open(SCOPE, { reservationId: 'r1', tableIds: ['t1'], currency: 'UZS', reason: 'Seating a booking' })
      .subscribe();
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush(session());
  });

  // 2026-09-21 audit follow-up (a): `open` creates a session, so no
  // aggregate exists yet for an If-Match -- a double click on "Seat" before
  // this used to open two sessions for the same booking.
  it('reuses the same Idempotency-Key for two still-in-flight opens of the same reservation', () => {
    const { api, http } = setUp();
    const body = { reservationId: 'r1', tableIds: ['t1'], currency: 'UZS', reason: 'Seating a booking' };

    api.open(SCOPE, body).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.open(SCOPE, body).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush(session());
    second.flush(session());
  });

  it('reuses the same Idempotency-Key for two still-in-flight walk-in opens of the same tables', () => {
    const { api, http } = setUp();
    const body = { tableIds: ['t1', 't2'], partySize: 3, currency: 'UZS', reason: 'Walk-in' };

    api.open(SCOPE, body).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.open(SCOPE, body).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush(session({ reservationId: null }));
    second.flush(session({ reservationId: null }));
  });

  it('mints a fresh Idempotency-Key once a settled open is followed by seating a different reservation', () => {
    const { api, http } = setUp();

    api
      .open(SCOPE, { reservationId: 'r1', tableIds: ['t1'], currency: 'UZS', reason: 'Seating a booking' })
      .subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(session());

    api
      .open(SCOPE, { reservationId: 'r2', tableIds: ['t2'], currency: 'UZS', reason: 'Seating a booking' })
      .subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush(session({ sessionId: 's2', reservationId: 'r2' }));
  });
});
