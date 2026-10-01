import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';
import { DineInAdmission, DineInService } from './dine-in.service';

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

function admission(overrides: Partial<DineInAdmission> = {}): DineInAdmission {
  return {
    guestToken: 'guest-token-1',
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    mode: 'ORDER_AND_PAY',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    locationId: 'location-1',
    tableCode: 'T1',
    openSessionId: 'session-1',
    channelCode: 'QRTABLE',
    ...overrides,
  };
}

function setUp(): { service: DineInService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [{ provide: ApiClient, useValue: api }],
  });
  return { service: TestBed.inject(DineInService), api };
}

describe('DineInService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('exchange', () => {
    it('posts the printed token unauthenticated and stores the admission', async () => {
      const { service, api } = setUp();
      const response = admission();
      api.mutate.mockResolvedValue(response);

      const result = await service.exchange('printed-table-token');

      expect(api.mutate).toHaveBeenCalledWith(
        'POST',
        '/storefront/dine-in/qr/token-exchanges',
        expect.objectContaining({ body: { tableToken: 'printed-table-token' }, anonymous: true }),
      );
      expect(result).toEqual(response);
      expect(service.admission()).toEqual(response);
    });

    it('persists the admission across a fresh instance (a reload)', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission());
      await service.exchange('printed-table-token');

      TestBed.resetTestingModule();
      const { service: resumed } = setUp();

      expect(resumed.admission()?.guestToken).toBe('guest-token-1');
    });
  });

  describe('admission expiry', () => {
    it('reports null once the stored deadline has passed, without asking the platform', () => {
      const { service } = setUp();
      localStorage.setItem(
        'horecaos_dinein_admission',
        JSON.stringify(admission({ expiresAt: new Date(Date.now() - 1000).toISOString() })),
      );

      TestBed.resetTestingModule();
      const { service: resumed } = setUp();
      void service;

      expect(resumed.admission()).toBeNull();
      expect(resumed.hasAdmission()).toBe(false);
    });
  });

  describe('seat (ADR 0143)', () => {
    const seating = {
      sessionId: 'session-new',
      status: 'OPEN',
      currency: 'UZS',
      totalMinor: 0,
      roundCount: 0,
      orderIds: [],
      origin: 'GUEST_QR',
      created: true,
      claimExpiresAt: '2026-10-01T10:15:00Z',
      confirmed: false,
    };

    async function scanned(overrides: Partial<DineInAdmission> = {}) {
      const parts = setUp();
      parts.api.mutate.mockResolvedValueOnce(
        admission({ openSessionId: null, walkInAvailable: true, ...overrides }),
      );
      await parts.service.exchange('printed-table-token');
      return parts;
    }

    it('posts the party size with the guest token, signed in as the customer -- never anonymous', async () => {
      const { service, api } = await scanned();
      api.mutate.mockResolvedValueOnce(seating);

      const result = await service.seat(3);

      expect(api.mutate).toHaveBeenLastCalledWith(
        'POST',
        '/storefront/dine-in/sessions',
        expect.objectContaining({
          body: { partySize: 3 },
          headers: { 'X-Dine-In-Token': 'guest-token-1' },
        }),
      );
      const options = api.mutate.mock.calls.at(-1)?.[2] as { anonymous?: boolean };
      expect(options.anonymous).toBeUndefined();
      expect(result.created).toBe(true);
    });

    it('moves the stored admission onto the new session, and a reload lands on the seated table', async () => {
      const { service, api } = await scanned();
      api.mutate.mockResolvedValueOnce(seating);

      await service.seat(2);

      expect(service.admission()?.openSessionId).toBe('session-new');
      expect(service.admission()?.walkInAvailable).toBe(false);
      TestBed.resetTestingModule();
      const { service: resumed } = setUp();
      expect(resumed.admission()?.openSessionId).toBe('session-new');
    });

    it('leaves the admission alone when the platform refuses', async () => {
      const { service, api } = await scanned();
      api.mutate.mockRejectedValueOnce(
        new HorecaOSApiError({
          status: 409,
          code: 'RESOURCE_CONFLICT',
          detail: 'This table cannot be taken from here.',
          problem: { conflict: 'TABLE_NOT_AVAILABLE' },
        }),
      );

      await expect(service.seat(2)).rejects.toBeInstanceOf(HorecaOSApiError);

      expect(service.admission()?.openSessionId).toBeNull();
      expect(service.admission()?.walkInAvailable).toBe(true);
    });

    it('refuses without a scanned table, rather than posting with no proof of where', async () => {
      const { service, api } = setUp();

      await expect(service.seat(2)).rejects.toThrow('No table has been scanned');
      expect(api.mutate).not.toHaveBeenCalled();
    });

    it('stops offering the table after a refusal, and offers it again when a session ends', async () => {
      const { service } = await scanned();

      service.markWalkInUnavailable();
      expect(service.admission()?.walkInAvailable).toBe(false);

      service.sessionEnded();
      expect(service.admission()?.openSessionId).toBeNull();
      expect(service.admission()?.walkInAvailable).toBe(true);
    });
  });

  describe('attachRound', () => {
    it('sends the guest token as X-Dine-In-Token and the order id in the body', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValueOnce(admission());
      await service.exchange('printed-table-token');
      api.mutate.mockResolvedValueOnce({
        sessionId: 'session-1',
        status: 'OPEN',
        currency: 'UZS',
        totalMinor: 45000,
        roundCount: 1,
        orderIds: ['order-1'],
      });

      const bill = await service.attachRound('session-1', 'order-1');

      expect(api.mutate).toHaveBeenLastCalledWith(
        'POST',
        '/storefront/dine-in/sessions/session-1/rounds',
        expect.objectContaining({
          body: { orderId: 'order-1' },
          headers: { 'X-Dine-In-Token': 'guest-token-1' },
        }),
      );
      expect(bill.totalMinor).toBe(45000);
    });

    it('does not mark the call anonymous, so the caller\'s own signed-in session is sent too', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValueOnce(admission());
      await service.exchange('printed-table-token');
      api.mutate.mockResolvedValueOnce({
        sessionId: 'session-1',
        status: 'OPEN',
        currency: 'UZS',
        totalMinor: 45000,
        roundCount: 1,
        orderIds: ['order-1'],
      });

      await service.attachRound('session-1', 'order-1');

      const [, , options] = api.mutate.mock.calls.at(-1)!;
      expect(options.anonymous).not.toBe(true);
    });

    it('refuses to call a dine-in endpoint with no guest token on hand', async () => {
      const { service } = setUp();

      await expect(service.attachRound('session-1', 'order-1')).rejects.toThrow();
    });
  });

  describe('isGuestSessionEnded', () => {
    it('is true for UNAUTHENTICATED and false for anything else', () => {
      const { service } = setUp();
      const ended = new HorecaOSApiError({
        status: 401,
        code: 'UNAUTHENTICATED',
        detail: 'session ended',
      });
      const other = new HorecaOSApiError({
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
        detail: 'no such session',
      });

      expect(service.isGuestSessionEnded(ended)).toBe(true);
      expect(service.isGuestSessionEnded(other)).toBe(false);
      expect(service.isGuestSessionEnded(new Error('boom'))).toBe(false);
    });
  });

  describe('clear', () => {
    it('forgets the admission both in memory and in storage', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission());
      await service.exchange('printed-table-token');

      service.clear();

      expect(service.admission()).toBeNull();
      expect(localStorage.getItem('horecaos_dinein_admission')).toBeNull();
    });
  });

  describe('queued rounds -- an order that still has to reach its table\'s bill', () => {
    const PENDING_KEY = 'horecaos_dinein_pending_rounds';
    const billAfter = (orderIds: string[]) => ({
      sessionId: 'session-1',
      status: 'OPEN',
      currency: 'UZS',
      totalMinor: 45000 * orderIds.length,
      roundCount: orderIds.length,
      orderIds,
    });
    const offline = () =>
      new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'The request did not reach the platform.' });

    function seated(): { service: DineInService; api: FakeApiClient } {
      localStorage.setItem('horecaos_dinein_admission', JSON.stringify(admission()));
      return setUp();
    }

    it('counts what was queued for a session and nothing for another one', () => {
      const { service } = seated();

      service.queueRound('session-1', 'order-1');
      service.queueRound('session-1', 'order-2');
      service.queueRound('session-2', 'order-3');

      expect(service.pendingRoundCount('session-1')).toBe(2);
      expect(service.pendingRoundCount('session-2')).toBe(1);
      expect(service.pendingRoundCount('session-9')).toBe(0);
    });

    it('queues an order once however often it is queued', () => {
      const { service } = seated();

      service.queueRound('session-1', 'order-1');
      service.queueRound('session-1', 'order-1');

      expect(service.pendingRoundCount('session-1')).toBe(1);
    });

    it('survives a reload, and stores ids only -- never the guest token', () => {
      const { service } = seated();
      service.queueRound('session-1', 'order-1');

      expect(localStorage.getItem(PENDING_KEY)).not.toContain('guest-token-1');
      TestBed.resetTestingModule();
      const { service: reloaded } = setUp();

      expect(reloaded.pendingRoundCount('session-1')).toBe(1);
    });

    it('forgets an order older than a day, on restore and when counting', () => {
      localStorage.setItem(
        PENDING_KEY,
        JSON.stringify([
          { sessionId: 'session-1', orderId: 'order-old', queuedAt: Date.now() - 25 * 60 * 60 * 1000 },
          { sessionId: 'session-1', orderId: 'order-new', queuedAt: Date.now() - 60 * 1000 },
        ]),
      );

      const { service } = seated();

      expect(service.pendingRoundCount('session-1')).toBe(1);
    });

    it('survives a corrupt entry in storage', () => {
      localStorage.setItem(PENDING_KEY, '{not json');
      const { service } = seated();

      expect(service.pendingRoundCount('session-1')).toBe(0);
    });

    it('attaches queued rounds oldest first, removes each once confirmed, and returns the last bill', async () => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      service.queueRound('session-1', 'order-2');
      api.mutate
        .mockResolvedValueOnce(billAfter(['order-1']))
        .mockResolvedValueOnce(billAfter(['order-1', 'order-2']));

      const flush = await service.flushPendingRounds('session-1');

      expect(api.mutate.mock.calls.map((call) => call[2].body)).toEqual([
        { orderId: 'order-1' },
        { orderId: 'order-2' },
      ]);
      expect(flush).toEqual({ bill: billAfter(['order-1', 'order-2']), pending: 0, abandoned: 0 });
      expect(service.pendingRoundCount('session-1')).toBe(0);
      expect(localStorage.getItem(PENDING_KEY)).toBeNull();
    });

    it('keeps every round queued when the platform cannot be reached, and stops at the first failure', async () => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      service.queueRound('session-1', 'order-2');
      api.mutate.mockRejectedValue(offline());

      const flush = await service.flushPendingRounds('session-1');

      expect(api.mutate).toHaveBeenCalledTimes(1);
      expect(flush).toEqual({ bill: null, pending: 2, abandoned: 0 });
      expect(service.pendingRoundCount('session-1')).toBe(2);
    });

    it.each([
      ['a 401, because the guest token or the customer session can be renewed', 401, 'UNAUTHENTICATED'],
      ['a 429', 429, 'RATE_LIMIT_EXCEEDED'],
      ['a 503', 503, 'INTERNAL_ERROR'],
    ])('keeps a round queued after %s', async (_label, status, code) => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      api.mutate.mockRejectedValue(new HorecaOSApiError({ status, code, detail: 'later' }));

      const flush = await service.flushPendingRounds('session-1');

      expect(flush).toEqual({ bill: null, pending: 1, abandoned: 0 });
    });

    it('keeps a round queued when no table is scanned on this device any more', async () => {
      const { service, api } = setUp();
      service.queueRound('session-1', 'order-1');

      const flush = await service.flushPendingRounds('session-1');

      expect(api.mutate).not.toHaveBeenCalled();
      expect(flush).toEqual({ bill: null, pending: 1, abandoned: 0 });
    });

    it('drops a round the platform refuses for good, counts it, and carries on with the next', async () => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      service.queueRound('session-1', 'order-2');
      api.mutate
        .mockRejectedValueOnce(new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'closed' }))
        .mockResolvedValueOnce(billAfter(['order-2']));

      const flush = await service.flushPendingRounds('session-1');

      expect(api.mutate).toHaveBeenCalledTimes(2);
      expect(flush).toEqual({ bill: billAfter(['order-2']), pending: 0, abandoned: 1 });
      expect(service.pendingRoundCount('session-1')).toBe(0);
    });

    it('leaves another session\'s rounds alone', async () => {
      const { service, api } = seated();
      service.queueRound('session-2', 'order-elsewhere');

      const flush = await service.flushPendingRounds('session-1');

      expect(api.mutate).not.toHaveBeenCalled();
      expect(flush).toEqual({ bill: null, pending: 0, abandoned: 0 });
      expect(service.pendingRoundCount('session-2')).toBe(1);
    });

    it('does not send one order twice when two passes overlap', async () => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      api.mutate.mockResolvedValue(billAfter(['order-1']));

      await Promise.all([service.flushPendingRounds('session-1'), service.flushPendingRounds('session-1')]);

      expect(api.mutate).toHaveBeenCalledTimes(1);
    });

    it('picks up a round queued while a pass was already running', async () => {
      const { service, api } = seated();
      service.queueRound('session-1', 'order-1');
      api.mutate.mockResolvedValue(billAfter([]));

      const running = service.flushPendingRounds('session-1');
      service.queueRound('session-1', 'order-2');
      const flush = await service.flushPendingRounds('session-1');
      await running;

      expect(api.mutate.mock.calls.map((call) => call[2].body.orderId)).toEqual(['order-1', 'order-2']);
      expect(flush.pending).toBe(0);
    });

    it('is not forgotten when the table visit is cleared -- a re-scan can still attach it', () => {
      const { service } = seated();
      service.queueRound('session-1', 'order-1');

      service.clear();

      expect(service.pendingRoundCount('session-1')).toBe(1);
    });
  });
});
