import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';
import { DineInService, type DineInAdmission, type DineInBill } from './dine-in.service';

/** The wire response: the admission plus the guest token the service keeps to itself. */
type AdmissionResponse = DineInAdmission & { guestToken: string };

const ADMISSION_KEY = 'horecaos_dinein_admission';
const PENDING_KEY = 'horecaos_dinein_pending_rounds';

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

function admission(overrides: Partial<AdmissionResponse> = {}): AdmissionResponse {
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

function bill(overrides: Partial<DineInBill> = {}): DineInBill {
  return {
    sessionId: 'session-1',
    status: 'OPEN',
    currency: 'UZS',
    totalMinor: 45_000,
    roundCount: 1,
    orderIds: ['order-1'],
    ...overrides,
  };
}

function setUp(): { service: DineInService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: api }] });
  return { service: TestBed.inject(DineInService), api };
}

/** A device that scanned an ORDER_AND_PAY table earlier: what a reload resumes from. */
function seated(overrides: Partial<AdmissionResponse> = {}): ReturnType<typeof setUp> {
  localStorage.setItem(ADMISSION_KEY, JSON.stringify(admission(overrides)));
  return setUp();
}

const offline = () =>
  new HorecaOSApiError({
    status: 0,
    code: 'NETWORK_UNREACHABLE',
    detail: 'The request did not reach the platform.',
  });

describe('DineInService (ADR 0047)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('exchange -- the scan', () => {
    it('posts the printed token unauthenticated and keeps the admission it gets back', async () => {
      const { service, api } = setUp();
      const response = admission({ mode: 'VIEW_ONLY', openSessionId: null });
      api.mutate.mockResolvedValue(response);

      const result = await service.exchange('printed-table-token');

      expect(api.mutate).toHaveBeenCalledWith(
        'POST',
        '/storefront/dine-in/qr/token-exchanges',
        expect.objectContaining({ body: { tableToken: 'printed-table-token' }, anonymous: true }),
      );
      const { guestToken: _token, ...kept } = response;
      expect(result).toEqual(kept);
      expect(service.admission()).toEqual(kept);
      expect(service.hasAdmission()).toBe(true);
    });

    it('never puts the printed token anywhere it could outlive the one request that spends it', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission());

      await service.exchange('printed-table-token');

      expect(JSON.stringify(localStorage)).not.toContain('printed-table-token');
      expect(JSON.stringify(sessionStorage)).not.toContain('printed-table-token');
    });

    it('VIEW_ONLY holds no guest token at all: nothing a menu-only table may do is authorised by it', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(
        admission({ mode: 'VIEW_ONLY', openSessionId: null, guestToken: 'secret-guest-token' }),
      );

      const result = await service.exchange('printed-table-token');

      expect(result).not.toHaveProperty('guestToken');
      expect(service.admission()).not.toHaveProperty('guestToken');
      expect(JSON.stringify(localStorage)).not.toContain('secret-guest-token');
      // ...and so it cannot make any table call, even by mistake.
      await expect(service.bill('session-1')).rejects.toThrow();
      expect(api.get).not.toHaveBeenCalled();
    });

    it('ORDER_AND_PAY keeps the guest token out of everything a screen can read', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission({ guestToken: 'secret-guest-token' }));

      const result = await service.exchange('printed-table-token');

      // The token is the service's own: a template or component that read it
      // could render it, log it or put it in a URL.
      expect(result).not.toHaveProperty('guestToken');
      expect(service.admission()).not.toHaveProperty('guestToken');
      expect(JSON.stringify(service.admission())).not.toContain('secret-guest-token');
    });

    it('survives a reload: a fresh instance resumes the visit and can still speak for the table', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission());
      await service.exchange('printed-table-token');

      TestBed.resetTestingModule();
      const { service: resumed, api: resumedApi } = setUp();
      resumedApi.get.mockResolvedValue(bill());
      await resumed.bill('session-1');

      expect(resumed.admission()?.tableCode).toBe('T1');
      expect(resumedApi.get).toHaveBeenCalledWith(
        '/storefront/dine-in/sessions/session-1',
        expect.objectContaining({ headers: { 'X-Dine-In-Token': 'guest-token-1' } }),
      );
    });

    it('does not resume an ORDER_AND_PAY visit stored without its guest token (an earlier build dropped it)', () => {
      const { guestToken: _dropped, ...withoutToken } = admission();
      localStorage.setItem(ADMISSION_KEY, JSON.stringify(withoutToken));

      const { service } = setUp();

      expect(service.admission()).toBeNull();
      expect(service.hasAdmission()).toBe(false);
    });

    it('still resumes a VIEW_ONLY visit stored without a token, which is how it is always stored', () => {
      const { guestToken: _none, ...viewOnly } = admission({ mode: 'VIEW_ONLY', openSessionId: null });
      localStorage.setItem(ADMISSION_KEY, JSON.stringify(viewOnly));

      const { service } = setUp();

      expect(service.admission()?.mode).toBe('VIEW_ONLY');
    });

    it('reports no admission once the stored deadline has passed, without asking the platform', () => {
      const { service, api } = seated({ expiresAt: new Date(Date.now() - 1000).toISOString() });

      expect(service.admission()).toBeNull();
      expect(service.hasAdmission()).toBe(false);
      expect(api.mutate).not.toHaveBeenCalled();
    });

    it('treats a corrupt stored value as nothing scanned, not as a crash', () => {
      localStorage.setItem(ADMISSION_KEY, '{not json');

      const { service } = setUp();

      expect(service.admission()).toBeNull();
    });

    it('a refused scan leaves the previous table visit untouched', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValueOnce(admission({ tableCode: 'T1' }));
      await service.exchange('good-token');
      api.mutate.mockRejectedValueOnce(new Error('refused'));

      await expect(service.exchange('rotated-token')).rejects.toThrow('refused');

      expect(service.admission()?.tableCode).toBe('T1');
    });

    it('a second scan replaces the first -- and the first table\'s token with it', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValueOnce(admission({ tableCode: 'T1', guestToken: 'guest-token-1' }));
      api.mutate.mockResolvedValueOnce(
        admission({ tableCode: 'T2', guestToken: 'guest-token-2', openSessionId: 'session-2' }),
      );

      await service.exchange('t1');
      await service.exchange('t2');
      api.get.mockResolvedValue(bill({ sessionId: 'session-2' }));
      await service.bill('session-2');

      expect(service.admission()?.tableCode).toBe('T2');
      expect(api.get).toHaveBeenLastCalledWith(
        '/storefront/dine-in/sessions/session-2',
        expect.objectContaining({ headers: { 'X-Dine-In-Token': 'guest-token-2' } }),
      );
      expect(JSON.stringify(localStorage)).not.toContain('guest-token-1');
    });

    it('scanning a VIEW_ONLY table after an ORDER_AND_PAY one drops the earlier token', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValueOnce(admission({ guestToken: 'guest-token-1' }));
      await service.exchange('ordering-table');
      api.mutate.mockResolvedValueOnce(
        admission({ mode: 'VIEW_ONLY', openSessionId: null, tableCode: 'T9', guestToken: 'guest-token-9' }),
      );

      await service.exchange('menu-only-table');

      expect(JSON.stringify(localStorage)).not.toContain('guest-token');
      await expect(service.bill('session-1')).rejects.toThrow();
    });

    it('clear() forgets the visit and its token, in memory and in storage', async () => {
      const { service, api } = setUp();
      api.mutate.mockResolvedValue(admission());
      await service.exchange('printed-table-token');

      service.clear();

      expect(service.admission()).toBeNull();
      expect(localStorage.getItem(ADMISSION_KEY)).toBeNull();
      await expect(service.bill('session-1')).rejects.toThrow();
    });
  });

  describe('the calls a guest token authorises', () => {
    it('reads the bill with the token in X-Dine-In-Token and no bearer', async () => {
      const { service, api } = seated();
      api.get.mockResolvedValue(bill());

      const result = await service.bill('session-1');

      expect(api.get).toHaveBeenCalledWith('/storefront/dine-in/sessions/session-1', {
        anonymous: true,
        headers: { 'X-Dine-In-Token': 'guest-token-1' },
      });
      expect(result.totalMinor).toBe(45_000);
    });

    it('asks for the bill with the token and no bearer', async () => {
      const { service, api } = seated();
      api.mutate.mockResolvedValue(bill({ status: 'BILL_REQUESTED' }));

      const result = await service.requestBill('session-1');

      expect(api.mutate).toHaveBeenCalledWith('POST', '/storefront/dine-in/sessions/session-1/bill-requests', {
        anonymous: true,
        headers: { 'X-Dine-In-Token': 'guest-token-1' },
      });
      expect(result.status).toBe('BILL_REQUESTED');
    });

    it('attaches a round with the token in the header and the order id in the body', async () => {
      const { service, api } = seated();
      api.mutate.mockResolvedValue(bill());

      const result = await service.attachRound('session-1', 'order-1');

      expect(api.mutate).toHaveBeenCalledWith(
        'POST',
        '/storefront/dine-in/sessions/session-1/rounds',
        expect.objectContaining({
          body: { orderId: 'order-1' },
          headers: { 'X-Dine-In-Token': 'guest-token-1' },
        }),
      );
      expect(result.roundCount).toBe(1);
    });

    it('does not mark the attach anonymous, so the customer\'s own signed-in session rides beside the token', async () => {
      const { service, api } = seated();
      api.mutate.mockResolvedValue(bill());

      await service.attachRound('session-1', 'order-1');

      const [, , options] = api.mutate.mock.calls.at(-1)!;
      // The token proves the device is at the table, not that the order is the
      // caller's: the platform checks the order against the bearer as well.
      expect(options.anonymous).not.toBe(true);
    });

    it('never puts the guest token in a path, a query or a body -- only in the header', async () => {
      const { service, api } = seated({ guestToken: 'secret-guest-token' });
      api.get.mockResolvedValue(bill());
      api.mutate.mockResolvedValue(bill());

      await service.bill('session-1');
      await service.requestBill('session-1');
      await service.attachRound('session-1', 'order-1');

      for (const [, path, options] of [
        ['GET', ...api.get.mock.calls[0]],
        ...api.mutate.mock.calls,
      ] as [string, string, { body?: unknown; query?: unknown }][]) {
        expect(path).not.toContain('secret-guest-token');
        expect(JSON.stringify(options.body ?? null)).not.toContain('secret-guest-token');
        expect(options.query).toBeUndefined();
      }
    });

    it.each([
      ['bill', (service: DineInService) => service.bill('session-1')],
      ['requestBill', (service: DineInService) => service.requestBill('session-1')],
      ['attachRound', (service: DineInService) => service.attachRound('session-1', 'order-1')],
    ])('%s refuses to leave the device with no table scanned', async (_name, call) => {
      const { service, api } = setUp();

      await expect(call(service)).rejects.toThrow();

      expect(api.get).not.toHaveBeenCalled();
      expect(api.mutate).not.toHaveBeenCalled();
    });

    it('refuses once the token\'s own deadline has passed, without asking the platform', async () => {
      const { service, api } = seated({ expiresAt: new Date(Date.now() - 1000).toISOString() });

      await expect(service.bill('session-1')).rejects.toThrow();

      expect(api.get).not.toHaveBeenCalled();
    });
  });

  describe('isGuestSessionEnded', () => {
    it('is true for UNAUTHENTICATED and false for anything else', () => {
      const { service } = setUp();
      const ended = new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'session ended' });
      const other = new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no such session' });

      expect(service.isGuestSessionEnded(ended)).toBe(true);
      expect(service.isGuestSessionEnded(other)).toBe(false);
      expect(service.isGuestSessionEnded(new Error('boom'))).toBe(false);
    });
  });

  describe('queued rounds -- an order that still has to reach its table\'s bill', () => {
    const billAfter = (orderIds: string[]) =>
      bill({ totalMinor: 45_000 * orderIds.length, roundCount: orderIds.length, orderIds });

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
      ['a 408', 408, 'INTERNAL_ERROR'],
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
