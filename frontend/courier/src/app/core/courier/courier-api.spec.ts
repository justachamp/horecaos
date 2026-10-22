import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, TENANT_ID, courierProfile } from '../../../testing/fixtures';
import { delivery, offer } from '../../../testing/work-fixtures';
import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from './courier-api';
import type { BranchRef } from './courier-shift';

const WHERE: BranchRef = {
  brandId: '018fe100-4000-7000-8000-0000000000b1',
  locationId: '018fe100-4000-7000-8000-0000000000c1',
};
const BRANCH = `/courier/tenants/${TENANT_ID}/brands/${WHERE.brandId}/locations/${WHERE.locationId}`;
const POSITION = { latitude: 41.31, longitude: 69.24, accuracyMeters: 12 };

describe('CourierApi', () => {
  let get: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;
  let api: CourierApi;

  beforeEach(() => {
    get = vi.fn().mockResolvedValue(null);
    post = vi.fn().mockResolvedValue(null);
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: ApiClient, useValue: { get, post } },
      ],
    });
    api = TestBed.inject(CourierApi);
  });

  it("reads the courier's own record from the tenant's courier surface, naming no courier id", async () => {
    get.mockResolvedValue(courierProfile());

    await api.me();

    expect(get).toHaveBeenCalledWith(`/courier/tenants/${TENANT_ID}/me`);
  });

  describe('the shift', () => {
    it('opens with the handset point and the tenant currency, and closes the same way', async () => {
      await api.openShift(WHERE, '41.31,69.24', 'k1');
      await api.closeShift(WHERE, 'shift-1', null, 'k2');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/shifts`, {
        body: { point: '41.31,69.24', currency: 'UZS' },
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(2, `${BRANCH}/shifts/shift-1/close`, {
        body: { point: null, currency: 'UZS' },
        idempotencyKey: 'k2',
      });
    });

    it('starts and ends a break on the shift, with no body', async () => {
      await api.startBreak(WHERE, 'shift-1');
      await api.endBreak(WHERE, 'shift-1');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/shifts/shift-1/breaks`, {
        idempotencyKey: undefined,
      });
      expect(post).toHaveBeenNthCalledWith(2, `${BRANCH}/shifts/shift-1/breaks/end`, {
        idempotencyKey: undefined,
      });
    });
  });

  it('declares the cash handed over, in minor units, against the handover the close opened', async () => {
    await api.declareCash(WHERE, 'handover-1', 12_500_000, 'k1');

    expect(post).toHaveBeenCalledWith(`${BRANCH}/cash-handovers/handover-1/declaration`, {
      body: { declaredMinor: 12_500_000 },
      idempotencyKey: 'k1',
    });
  });

  describe('duty and telemetry', () => {
    it('reads, opens and closes the courier’s own session at the branch', async () => {
      await api.currentDuty(WHERE);
      await api.openDuty(WHERE, 'device-1', 'k1');
      await api.closeDuty(WHERE, 'session-1', 'k2');

      expect(get).toHaveBeenCalledWith(`${BRANCH}/duty-session`);
      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/duty-sessions`, {
        body: { deviceId: 'device-1' },
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(2, `${BRANCH}/duty-sessions/session-1/closures`, {
        idempotencyKey: 'k2',
      });
    });

    it('posts a batch with no courier id and no idempotency key -- the endpoint is the exempt one', async () => {
      const batch = { activeAssignmentCount: 1, observations: [] };

      await api.postObservations(WHERE, batch);

      expect(post).toHaveBeenCalledWith(`${BRANCH}/telemetry/observations`, { body: batch });
    });

    it('acknowledges the notice at the tenant, not at a branch', async () => {
      await api.acknowledgeNotice('v1', 'k1');

      expect(post).toHaveBeenCalledWith(
        `/courier/tenants/${TENANT_ID}/telemetry-notice-acknowledgements`,
        {
          body: { version: 'v1' },
          idempotencyKey: 'k1',
        },
      );
    });
  });

  describe('offers', () => {
    it('lists the branch’s offers', async () => {
      await api.offers(WHERE);

      expect(get).toHaveBeenCalledWith(`${BRANCH}/offers`);
    });

    it('accepts with the offer’s own version as If-Match, and a position only when there is one', async () => {
      const held = offer({ offerId: 'offer-1', version: 4 });

      await api.acceptOffer(WHERE, held, POSITION, 'k1');
      await api.acceptOffer(WHERE, held, null, 'k2');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/offers/offer-1/accept`, {
        body: { position: POSITION },
        expectedVersion: 4,
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(2, `${BRANCH}/offers/offer-1/accept`, {
        body: {},
        expectedVersion: 4,
        idempotencyKey: 'k2',
      });
    });

    it('declines with the version and reveals the address through the offer’s own audited call', async () => {
      const held = offer({ offerId: 'offer-1', version: 4 });

      await api.declineOffer(WHERE, held, 'k1');
      await api.revealOfferLocation(WHERE, 'offer-1', 'k2');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/offers/offer-1/decline`, {
        expectedVersion: 4,
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(
        2,
        `${BRANCH}/offers/offer-1/customer-location-reveals`,
        {
          idempotencyKey: 'k2',
        },
      );
    });
  });

  describe('deliveries', () => {
    const held = delivery({ shipmentId: 'ship-1', version: 7 });

    it('lists and reads the courier’s own deliveries', async () => {
      await api.deliveries(WHERE);
      await api.delivery(WHERE, 'ship-1');

      expect(get).toHaveBeenNthCalledWith(1, `${BRANCH}/deliveries`);
      expect(get).toHaveBeenNthCalledWith(2, `${BRANCH}/deliveries/ship-1`);
    });

    it('announces arrival through advance with the delivery’s version', async () => {
      await api.advance(WHERE, held, 'PICKUP_PENDING', POSITION, 'k1');

      expect(post).toHaveBeenCalledWith(`${BRANCH}/deliveries/ship-1/advance`, {
        body: { step: 'PICKUP_PENDING', position: POSITION },
        expectedVersion: 7,
        idempotencyKey: 'k1',
      });
    });

    it('picks up and hands over through the gate endpoints, not advance (cross-wave contract)', async () => {
      await api.pickUp(WHERE, held, POSITION, 'k1');
      await api.handOver(WHERE, held, null, 'k2');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/deliveries/ship-1/pickup`, {
        body: { position: POSITION },
        expectedVersion: 7,
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(2, `${BRANCH}/deliveries/ship-1/handover`, {
        body: {},
        expectedVersion: 7,
        idempotencyKey: 'k2',
      });
    });

    it('confirms the cash in minor units, and reveals the door through the delivery’s own audited call', async () => {
      await api.confirmPayment(WHERE, held, 125_000_00, 'k1');
      await api.revealDeliveryLocation(WHERE, 'ship-1', 'k2');

      expect(post).toHaveBeenNthCalledWith(1, `${BRANCH}/deliveries/ship-1/payment-confirmation`, {
        body: { collectedMinor: 125_000_00 },
        expectedVersion: 7,
        idempotencyKey: 'k1',
      });
      expect(post).toHaveBeenNthCalledWith(
        2,
        `${BRANCH}/deliveries/ship-1/customer-location-reveals`,
        { idempotencyKey: 'k2' },
      );
    });
  });
});
