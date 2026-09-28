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
});
