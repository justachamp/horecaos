import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ReservationsApi, type NewReservation } from './reservations-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/reservations`;

function booking(overrides: Partial<NewReservation> = {}): NewReservation {
  return {
    guestName: 'Aziz',
    guestPhone: '+998901234567',
    partySize: 4,
    requestedFrom: '2026-09-22T18:00:00Z',
    requestedTo: '2026-09-22T20:00:00Z',
    tableIds: ['t1'],
    sourceChannelId: 'c1',
    ...overrides,
  };
}

function response(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    reservationId: 'r1',
    partySize: 4,
    requestedFrom: '2026-09-22T18:00:00Z',
    requestedTo: '2026-09-22T20:00:00Z',
    turnaroundMinutes: 90,
    status: 'REQUESTED',
    tableIds: ['t1'],
    version: 1,
    guestName: null,
    guestPhone: null,
    note: null,
    ...overrides,
  };
}

function setUp(): { api: ReservationsApi; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), ReservationsApi],
  });
  return { api: TestBed.inject(ReservationsApi), http: TestBed.inject(HttpTestingController) };
}

describe('ReservationsApi.create', () => {
  it('posts the new booking with an Idempotency-Key and no If-Match -- no aggregate exists yet', () => {
    const { api, http } = setUp();

    api.create(SCOPE, booking()).subscribe();
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual(booking());
    request.flush(response());
  });

  // 2026-09-21 audit follow-up (a): a double click on "Book" before the
  // panel showed busy used to mint two independent keys and could
  // double-book the same table and window.
  it('reuses the same Idempotency-Key for two still-in-flight creates with the identical booking', () => {
    const { api, http } = setUp();

    api.create(SCOPE, booking()).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.create(SCOPE, booking()).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush(response());
    second.flush(response());
  });

  it('mints a fresh Idempotency-Key once a settled booking is followed by a genuinely different one', () => {
    const { api, http } = setUp();

    api.create(SCOPE, booking()).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(response());

    api.create(SCOPE, booking({ guestName: 'Malika', tableIds: ['t2'] })).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush(response({ reservationId: 'r2' }));
  });

  it('mints a fresh Idempotency-Key when the booking details change before the first settled', () => {
    const { api, http } = setUp();

    api.create(SCOPE, booking()).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.create(SCOPE, booking({ partySize: 6 })).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    first.flush(response());
    second.flush(response());
  });
});

describe('ReservationsApi.stateAction / amend -- already guarded by expectedVersion, left as they are', () => {
  it('stateAction sends If-Match and still mints a key per call (a stale retry fails loudly, not silently twice)', () => {
    const { api, http } = setUp();

    api.stateAction(SCOPE, 'r1', 'CONFIRMED', 'Guest confirmed by phone', 2).subscribe();
    const request = http.expectOne(`${URL}/r1/state-actions`);

    expect(request.request.headers.get('If-Match')).toBe('W/"2"');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush(response({ status: 'CONFIRMED', version: 3 }));
  });
});
