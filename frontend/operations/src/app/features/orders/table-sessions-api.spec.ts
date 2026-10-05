import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { SessionView, TableSessionsApi, isUnconfirmedClaim } from './table-sessions-api';

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
    tables: [{ tableId: 't1', code: 'T1', displayName: 'Table 1' }],
    origin: 'STAFF',
    claimExpiresAt: null,
    confirmedAt: null,
    ...overrides,
  };
}

describe('TableSessionsApi.open', () => {
  it('posts with an Idempotency-Key', () => {
    const { api, http } = setUp();

    api
      .open(SCOPE, {
        reservationId: 'r1',
        tableIds: ['t1'],
        currency: 'UZS',
        reason: 'Seating a booking',
      })
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
    const body = {
      reservationId: 'r1',
      tableIds: ['t1'],
      currency: 'UZS',
      reason: 'Seating a booking',
    };

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
      .open(SCOPE, {
        reservationId: 'r1',
        tableIds: ['t1'],
        currency: 'UZS',
        reason: 'Seating a booking',
      })
      .subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush(session());

    api
      .open(SCOPE, {
        reservationId: 'r2',
        tableIds: ['t2'],
        currency: 'UZS',
        reason: 'Seating a booking',
      })
      .subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush(session({ sessionId: 's2', reservationId: 'r2' }));
  });
});

describe('TableSessionsApi -- a settled rejection is not held against the next click', () => {
  const conflict = { status: 409, statusText: 'Conflict' };
  const walkIn = { tableIds: ['t1'], partySize: 3, currency: 'UZS', reason: 'Walk-in' };

  it('mints a fresh key for a walk-in after the table was refused as occupied, so a table that has since freed can be seated', async () => {
    const { api, http } = setUp();

    const first = firstValueFrom(api.open(SCOPE, walkIn));
    const firstRequest = http.expectOne(URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush(
      { status: 409, code: 'RESOURCE_CONFLICT', conflict: 'TABLE_OCCUPIED' },
      conflict,
    );
    await first.catch(() => undefined);

    const second = firstValueFrom(api.open(SCOPE, walkIn));
    const secondRequest = http.expectOne(URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    secondRequest.flush(session({ reservationId: null }));
    await second;
  });

  it('keeps the key when the outcome is unknown, so a request that landed is replayed rather than repeated', async () => {
    const { api, http } = setUp();

    const first = firstValueFrom(api.open(SCOPE, walkIn));
    const firstRequest = http.expectOne(URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush({ title: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    await first.catch(() => undefined);

    const second = firstValueFrom(api.open(SCOPE, walkIn));
    const secondRequest = http.expectOne(URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).toBe(firstKey);
    secondRequest.flush(session({ reservationId: null }));
    await second;
  });

  it('keeps the key while a first attempt under it is still in progress', async () => {
    const { api, http } = setUp();

    const first = firstValueFrom(api.open(SCOPE, walkIn));
    const firstRequest = http.expectOne(URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush({ status: 409, code: 'IDEMPOTENCY_KEY_IN_PROGRESS' }, conflict);
    await first.catch(() => undefined);

    const second = firstValueFrom(api.open(SCOPE, walkIn));
    const secondRequest = http.expectOne(URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).toBe(firstKey);
    secondRequest.flush(session({ reservationId: null }));
    await second;
  });

  it('gives a round refused because its order is on another bill a fresh key next time', async () => {
    const { api, http } = setUp();
    const url = `${URL}/s1/rounds`;

    const first = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Round'));
    const firstRequest = http.expectOne(url);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush(
      { status: 409, code: 'RESOURCE_CONFLICT', conflict: 'ORDER_ALREADY_BILLED' },
      conflict,
    );
    await first.catch(() => undefined);

    const second = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Round'));
    const secondRequest = http.expectOne(url);

    expect(secondRequest.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    secondRequest.flush({ sessionId: 's1', orderId: 'o1', sequence: 1 });
    await second;
  });
});

describe('TableSessionsApi.live', () => {
  it('reads the live list and answers with each party and the tables it sits at', async () => {
    const { api, http } = setUp();

    const pending = firstValueFrom(api.live(SCOPE));
    const request = http.expectOne(URL);
    expect(request.request.method).toBe('GET');
    request.flush([
      session({
        sessionId: 's1',
        tables: [
          { tableId: 't2', code: 'T2', displayName: 'Table 2' },
          { tableId: 't10', code: 'T10', displayName: 'Table 10' },
        ],
      }),
      session({ sessionId: 's2', reservationId: null }),
    ]);

    const parties = await pending;
    expect(parties.map((party) => party.sessionId)).toEqual(['s1', 's2']);
    expect(parties[0].tables.map((table) => table.code)).toEqual(['T2', 'T10']);
  });

  it('answers an empty list rather than nothing when the room is empty', async () => {
    const { api, http } = setUp();

    const pending = firstValueFrom(api.live(SCOPE));
    http.expectOne(URL).flush(null);

    expect(await pending).toEqual([]);
  });
});

describe('TableSessionsApi.attachRound', () => {
  const ROUNDS_URL = `${URL}/s1/rounds`;

  it('posts the order id and a reason to the session, with an Idempotency-Key', async () => {
    const { api, http } = setUp();

    const pending = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Keyed in at the console'));
    const request = http.expectOne(ROUNDS_URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ orderId: 'o1', reason: 'Keyed in at the console' });
    request.flush({ sessionId: 's1', orderId: 'o1', sequence: 2 });

    expect(await pending).toEqual({ sessionId: 's1', orderId: 'o1', sequence: 2 });
  });

  // A dropped response leaves the operator not knowing whether the round is on the
  // bill; asking again must reuse the key so a first attempt that landed is replayed
  // rather than repeated.
  it('reuses the same Idempotency-Key when the same order is attached again after a failure', async () => {
    const { api, http } = setUp();

    const first = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Keyed in at the console'));
    const firstRequest = http.expectOne(ROUNDS_URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush({ title: 'down' }, { status: 503, statusText: 'Service Unavailable' });
    await first.catch(() => undefined);

    const retry = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Keyed in at the console'));
    const retryRequest = http.expectOne(ROUNDS_URL);

    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe(firstKey);
    retryRequest.flush({ sessionId: 's1', orderId: 'o1', sequence: 1 });
    await retry;
  });

  it('mints a fresh Idempotency-Key for a different order on the same session', async () => {
    const { api, http } = setUp();

    const first = firstValueFrom(api.attachRound(SCOPE, 's1', 'o1', 'Round'));
    const firstRequest = http.expectOne(ROUNDS_URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush({ sessionId: 's1', orderId: 'o1', sequence: 1 });
    await first;

    const second = firstValueFrom(api.attachRound(SCOPE, 's1', 'o2', 'Round'));
    const secondRequest = http.expectOne(ROUNDS_URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    secondRequest.flush({ sessionId: 's1', orderId: 'o2', sequence: 2 });
    await second;
  });
});

describe("TableSessionsApi -- a guest's self-seated claim (ADR 0143)", () => {
  it('confirms a claim with a reason, the session version and a fresh Idempotency-Key', async () => {
    const { api, http } = setUp();

    const confirmed = firstValueFrom(api.confirmClaim(SCOPE, 's1', 'Guest is at the bar', 4));
    const request = http.expectOne(`${URL}/s1/claim-confirmations`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toContain('4');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ reason: 'Guest is at the bar' });
    request.flush(session({ origin: 'GUEST_QR', confirmedAt: '2026-09-29T14:05:00Z', version: 5 }));
    expect((await confirmed).confirmedAt).toBe('2026-09-29T14:05:00Z');
  });

  it('releases a claim by closing the session through the state-action endpoint', async () => {
    const { api, http } = setUp();

    const released = firstValueFrom(api.release(SCOPE, 's1', 'Nobody came', 4));
    const request = http.expectOne(`${URL}/s1/state-actions`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toContain('4');
    expect(request.request.body).toEqual({ targetStatus: 'CLOSED', reason: 'Nobody came' });
    request.flush(session({ status: 'CLOSED', version: 5 }));
    await released;
  });

  it('knows an unconfirmed claim from a staff session and from a confirmed claim', () => {
    const staff = session() as unknown as SessionView;
    const unconfirmed = session({
      origin: 'GUEST_QR',
      claimExpiresAt: '2026-09-22T10:15:00Z',
    }) as unknown as SessionView;
    const confirmed = session({
      origin: 'GUEST_QR',
      confirmedAt: '2026-09-22T10:05:00Z',
    }) as unknown as SessionView;

    expect(isUnconfirmedClaim(staff)).toBe(false);
    expect(isUnconfirmedClaim(unconfirmed)).toBe(true);
    expect(isUnconfirmedClaim(confirmed)).toBe(false);
  });
});

describe('TableSessionsApi -- ending a party (gap map rows 1.3 and 10.2d)', () => {
  it('reads one party with its running bill, so a close is made against the figure just read', async () => {
    const { api, http } = setUp();

    const read = firstValueFrom(api.detail(SCOPE, 's1'));
    const request = http.expectOne(`${URL}/s1`);

    expect(request.request.method).toBe('GET');
    request.flush({
      session: session({ version: 7 }),
      orderIds: ['o1', 'o2'],
      currency: 'UZS',
      totalMinor: 87_000,
      roundCount: 2,
      openRoundCount: 1,
    });
    const detail = await read;
    expect(detail.totalMinor).toBe(87_000);
    expect(detail.roundCount).toBe(2);
    expect(detail.session.version).toBe(7);
  });

  it('closes a party through the state-action endpoint, on its version, with a fresh Idempotency-Key', async () => {
    const { api, http } = setUp();

    const closed = firstValueFrom(api.close(SCOPE, 's1', 'The guests paid', 6));
    const request = http.expectOne(`${URL}/s1/state-actions`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toContain('6');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ targetStatus: 'CLOSED', reason: 'The guests paid' });
    request.flush(session({ status: 'CLOSED', version: 7 }));
    expect((await closed).status).toBe('CLOSED');
  });

  it('starts settling a party whose guests asked for the bill through the same endpoint, on its version', async () => {
    const { api, http } = setUp();

    const settling = firstValueFrom(api.startSettling(SCOPE, 's1', 'The guests paid', 6));
    const request = http.expectOne(`${URL}/s1/state-actions`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toContain('6');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ targetStatus: 'SETTLING', reason: 'The guests paid' });
    request.flush(session({ status: 'SETTLING', version: 7 }));
    expect((await settling).version).toBe(7);
  });

  it('closes a party that left without paying through force-closures, which is a different endpoint and body', async () => {
    const { api, http } = setUp();

    const forced = firstValueFrom(api.forceClose(SCOPE, 's1', 'WALKOUT', 'Left at 21:40', 6));
    const request = http.expectOne(`${URL}/s1/force-closures`);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('If-Match')).toContain('6');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ reasonCode: 'WALKOUT', reason: 'Left at 21:40' });
    request.flush(session({ status: 'FORCE_CLOSED', version: 7 }));
    expect((await forced).status).toBe('FORCE_CLOSED');
  });

  it('never asks the state-action endpoint for FORCE_CLOSED, which that endpoint refuses', async () => {
    const { api, http } = setUp();

    void firstValueFrom(api.close(SCOPE, 's1', 'x', 1));
    const request = http.expectOne(`${URL}/s1/state-actions`);

    expect((request.request.body as { targetStatus: string }).targetStatus).not.toBe(
      'FORCE_CLOSED',
    );
    request.flush(session({ status: 'CLOSED' }));
  });
});
