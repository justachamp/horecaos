import { TestBed } from '@angular/core/testing';

import { FakeGeolocation, fix } from '../../../testing/fake-device';
import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { delivery, offer } from '../../../testing/work-fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';
import { DeliveriesStore } from '../deliveries/deliveries.store';
import { GEOLOCATION } from '../device/geolocation';
import { OFFER_POLL_SECONDS, OffersStore } from './offers.store';

describe('OffersStore', () => {
  const SHIFT = courierProfile().openShift!;
  const WHERE = { brandId: SHIFT.brandId, locationId: SHIFT.locationId };

  let geo: FakeGeolocation;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let adopt: ReturnType<typeof vi.fn>;
  let store: OffersStore;

  const soon = offer({ offerId: 'offer-soon', expiresAt: '2026-10-14T05:00:30Z', version: 3 });
  const later = offer({ offerId: 'offer-later', expiresAt: '2026-10-14T05:01:30Z' });

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-14T05:00:00Z'));
    geo = new FakeGeolocation();
    geo.onceAnswer = fix({ latitude: 41.3, longitude: 69.2, accuracyMeters: 9 });
    api = {
      me: vi.fn().mockResolvedValue(courierProfile()),
      offers: vi.fn().mockResolvedValue([later, soon]),
      acceptOffer: vi.fn(),
      declineOffer: vi.fn(),
      revealOfferLocation: vi.fn(),
    };
    adopt = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: api },
        { provide: GEOLOCATION, useValue: geo },
        { provide: DeliveriesStore, useValue: { adopt } },
      ],
    });
    await TestBed.inject(CourierProfileStore).load();
    store = TestBed.inject(OffersStore);
  });

  afterEach(() => {
    store.stop();
    delete (document as { visibilityState?: unknown }).visibilityState;
    vi.useRealTimers();
  });

  describe('polling', () => {
    it('asks at once, then every poll interval, well inside the offer’s minute to live', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
      expect(api['offers']).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 1_000);
      expect(api['offers']).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 1_000);
      expect(api['offers']).toHaveBeenCalledTimes(3);
      expect(api['offers']).toHaveBeenCalledWith(WHERE);

      // The platform's default offer lifetime is 60 s (ADR 0179 D7.7); a poll at most a quarter of that
      // sees every offer with most of its life left.
      expect(OFFER_POLL_SECONDS).toBeLessThanOrEqual(15);
    });

    it('lists the offer that lapses first first, whatever order the platform sent', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);

      expect(store.offers().map((o) => o.offerId)).toEqual(['offer-soon', 'offer-later']);
      expect(store.count()).toBe(2);
    });

    it('stops asking, and forgets what it held, when stopped', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);

      store.stop();
      await vi.advanceTimersByTimeAsync(60_000);

      expect(api['offers']).toHaveBeenCalledTimes(1);
      expect(store.offers()).toEqual([]);
    });

    it('starting again at the same branch does not double the polling', async () => {
      store.start(WHERE);
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 1_000);

      expect(api['offers']).toHaveBeenCalledTimes(2);
    });

    it('does not ask while the page is hidden, and asks the moment it is shown again', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });

      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 3_000);
      expect(api['offers']).toHaveBeenCalledTimes(1);

      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);
      expect(api['offers']).toHaveBeenCalledTimes(2);
    });

    it('keeps the offers it has when a poll fails, says so, and recovers by itself', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
      api['offers'].mockRejectedValueOnce(
        new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }),
      );

      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 1_000);
      expect(store.offers()).toHaveLength(2);
      expect(store.error()?.isNetwork).toBe(true);

      await vi.advanceTimersByTimeAsync(OFFER_POLL_SECONDS * 1_000);
      expect(store.error()).toBeNull();
    });

    it('notes when the list was last read, for the courier who wonders if it is current', async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);

      expect(store.updatedAt()).toBe(Date.parse('2026-10-14T05:00:00Z'));
    });
  });

  describe('accepting', () => {
    beforeEach(async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
    });

    it('sends the offer’s own version and the courier’s position, and takes the delivery up', async () => {
      const taken = delivery({ shipmentId: 'ship-1', orderReference: 'A-1042' });
      api['acceptOffer'].mockResolvedValue({ outcome: 'ACCEPTED', delivery: taken });

      const outcome = await store.accept(soon);

      expect(api['acceptOffer']).toHaveBeenCalledWith(
        WHERE,
        soon,
        { latitude: 41.3, longitude: 69.2, accuracyMeters: 9 },
        expect.any(String),
      );
      expect(outcome).toEqual({ kind: 'accepted', delivery: taken });
      expect(adopt).toHaveBeenCalledWith(taken);
      expect(store.offers().map((o) => o.offerId)).toEqual(['offer-later']);
    });

    it('says the offer is gone -- an answer, not an error -- when somebody else took it', async () => {
      api['acceptOffer'].mockResolvedValue({ outcome: 'NO_LONGER_AVAILABLE', delivery: null });

      const outcome = await store.accept(soon);

      expect(outcome).toEqual({ kind: 'gone' });
      expect(adopt).not.toHaveBeenCalled();
      expect(store.offers().map((o) => o.offerId)).toEqual(['offer-later']);
    });

    it('keeps the offer and says why when a rule refuses it', async () => {
      api['acceptOffer'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'TOO_FAR_FROM_PICKUP',
          detail: 'x',
        }),
      );

      const outcome = await store.accept(soon);

      expect(outcome).toEqual({ kind: 'refused', reason: 'TOO_FAR_FROM_PICKUP' });
      expect(store.offers().map((o) => o.offerId)).toContain('offer-soon');
      expect(store.outcomeOf('offer-soon')).toEqual(outcome);
    });

    it('re-reads the list when the offer changed under the courier', async () => {
      api['acceptOffer'].mockRejectedValue(
        new ApiError({ status: 409, code: 'STALE_VERSION', detail: 'x' }),
      );
      api['offers'].mockClear();

      const outcome = await store.accept(soon);

      expect(outcome).toEqual({ kind: 'stale' });
      expect(api['offers']).toHaveBeenCalledOnce();
    });

    it('asks without a position when the phone has none, and lets the gate decide', async () => {
      geo.onceAnswer = 'denied';
      api['acceptOffer'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'GPS_POSITION_REQUIRED',
          detail: 'x',
        }),
      );

      const outcome = await store.accept(soon);

      expect(api['acceptOffer'].mock.calls[0][2]).toBeNull();
      expect(outcome).toEqual({ kind: 'refused', reason: 'GPS_POSITION_REQUIRED' });
    });

    it('replays the same key when the connection dropped and the courier taps again', async () => {
      api['acceptOffer']
        .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }))
        .mockResolvedValueOnce({ outcome: 'ACCEPTED', delivery: delivery() });

      const first = await store.accept(soon);
      await store.accept(soon);

      expect(first.kind).toBe('failed');
      expect(api['acceptOffer'].mock.calls[1][3]).toBe(api['acceptOffer'].mock.calls[0][3]);
    });

    it('does one thing at a time', async () => {
      let release: (value: unknown) => void = () => undefined;
      api['acceptOffer'].mockReturnValue(new Promise((resolve) => (release = resolve)));

      const first = store.accept(soon);
      await vi.advanceTimersByTimeAsync(0);
      expect(store.busy()).toBe('offer-soon');
      const second = await store.accept(later);
      release({ outcome: 'NO_LONGER_AVAILABLE', delivery: null });
      await first;

      expect(second.kind).toBe('failed');
      expect(api['acceptOffer']).toHaveBeenCalledOnce();
      expect(store.busy()).toBeNull();
    });
  });

  describe('declining', () => {
    beforeEach(async () => {
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
    });

    it('turns the offer down with its version, and it leaves the list', async () => {
      api['declineOffer'].mockResolvedValue({ outcome: 'DECLINED' });

      const outcome = await store.decline(soon);

      expect(api['declineOffer']).toHaveBeenCalledWith(WHERE, soon, expect.any(String));
      expect(outcome).toEqual({ kind: 'declined' });
      expect(store.offers().map((o) => o.offerId)).toEqual(['offer-later']);
    });

    it('says gone when it had already lapsed', async () => {
      api['declineOffer'].mockResolvedValue({ outcome: 'NO_LONGER_AVAILABLE' });

      expect(await store.decline(soon)).toEqual({ kind: 'gone' });
      expect(store.offers().map((o) => o.offerId)).toEqual(['offer-later']);
    });
  });

  describe("the customer's address before acceptance", () => {
    const revealable = offer({ offerId: 'offer-open', customerLocationRevealable: true });
    const door = {
      latitude: 41.31,
      longitude: 69.25,
      addressLine: 'Amir Temur 1',
      entrance: null,
      floor: null,
      apartment: null,
      instructions: null,
    };

    beforeEach(async () => {
      api['offers'].mockResolvedValue([revealable]);
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
    });

    it('is opened through the offer’s own audited call and shown until the offer goes', async () => {
      api['revealOfferLocation'].mockResolvedValue(door);

      const outcome = await store.reveal(revealable);

      expect(api['revealOfferLocation']).toHaveBeenCalledWith(
        WHERE,
        'offer-open',
        expect.any(String),
      );
      expect(outcome).toEqual({ kind: 'revealed' });
      expect(store.revealedFor('offer-open')).toEqual(door);
    });

    it('is forgotten as soon as the offer is declined, taken, or the polling stops', async () => {
      api['revealOfferLocation'].mockResolvedValue(door);
      await store.reveal(revealable);
      api['declineOffer'].mockResolvedValue({ outcome: 'DECLINED' });

      await store.decline(revealable);
      expect(store.revealedFor('offer-open')).toBeNull();

      api['offers'].mockResolvedValue([revealable]);
      store.start(WHERE);
      await vi.advanceTimersByTimeAsync(0);
      await store.reveal(revealable);
      store.stop();
      expect(store.revealedFor('offer-open')).toBeNull();
    });

    it('is refused in words when the tenant reveals it only after acceptance', async () => {
      api['revealOfferLocation'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'LOCATION_NOT_YET_REVEALED',
          detail: 'x',
        }),
      );

      expect(await store.reveal(revealable)).toEqual({
        kind: 'refused',
        reason: 'LOCATION_NOT_YET_REVEALED',
      });
      expect(store.revealedFor('offer-open')).toBeNull();
    });
  });
});
