import { TestBed } from '@angular/core/testing';

import { FakeGeolocation, fix } from '../../../testing/fake-device';
import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { delivery } from '../../../testing/work-fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';
import { GEOLOCATION } from '../device/geolocation';
import { DutyStore } from '../telemetry/duty.store';
import { DeliveriesStore } from './deliveries.store';

describe('DeliveriesStore', () => {
  const SHIFT = courierProfile().openShift!;
  const WHERE = { brandId: SHIFT.brandId, locationId: SHIFT.locationId };

  let geo: FakeGeolocation;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let assignments: { set: ReturnType<typeof vi.fn> };
  let store: DeliveriesStore;

  const earlier = delivery({
    shipmentId: 'ship-early',
    assignedAt: '2026-10-14T05:00:00Z',
    status: 'PICKED_UP',
  });
  const later = delivery({ shipmentId: 'ship-late', assignedAt: '2026-10-14T05:10:00Z' });
  const finished = delivery({
    shipmentId: 'ship-done',
    assignedAt: '2026-10-14T04:00:00Z',
    status: 'DELIVERED',
  });

  async function create(record = courierProfile()) {
    api['me'].mockResolvedValue(record);
    await TestBed.inject(CourierProfileStore).load();
    store = TestBed.inject(DeliveriesStore);
    return store;
  }

  beforeEach(() => {
    geo = new FakeGeolocation();
    geo.onceAnswer = fix({ latitude: 41.3, longitude: 69.2, accuracyMeters: 9 });
    api = {
      me: vi.fn(),
      deliveries: vi.fn().mockResolvedValue([later, finished, earlier]),
      delivery: vi.fn(),
      advance: vi.fn(),
      pickUp: vi.fn(),
      handOver: vi.fn(),
      confirmPayment: vi.fn(),
      revealDeliveryLocation: vi.fn(),
    };
    assignments = { set: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: api },
        { provide: GEOLOCATION, useValue: geo },
        { provide: DutyStore, useValue: { assignments } },
      ],
    });
  });

  describe('the list', () => {
    it('reads the courier’s own deliveries at the branch they work at, oldest assignment first', async () => {
      await create();

      await store.refresh();

      expect(api['deliveries']).toHaveBeenCalledWith(WHERE);
      expect(store.deliveries().map((d) => d.shipmentId)).toEqual([
        'ship-done',
        'ship-early',
        'ship-late',
      ]);
    });

    it('counts only what is still being carried, and tells the sender', async () => {
      await create();

      await store.refresh();

      expect(store.active().map((d) => d.shipmentId)).toEqual(['ship-early', 'ship-late']);
      expect(assignments.set).toHaveBeenLastCalledWith(2);
    });

    it('works at the main branch when no shift is open, so what is still carried can be finished', async () => {
      const closed = courierProfile({ openShift: null });
      await create(closed);

      await store.refresh();

      expect(api['deliveries']).toHaveBeenCalledWith({
        brandId: closed.branches[0].brandId,
        locationId: closed.branches[0].locationId,
      });
    });

    it('holds nothing and asks nothing when the courier has no branch', async () => {
      await create(courierProfile({ openShift: null, branches: [] }));

      await store.refresh();

      expect(api['deliveries']).not.toHaveBeenCalled();
      expect(store.deliveries()).toEqual([]);
    });

    it('keeps what it had and says so when a refresh fails', async () => {
      await create();
      await store.refresh();
      api['deliveries'].mockRejectedValue(
        new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }),
      );

      await store.refresh();

      expect(store.deliveries()).toHaveLength(3);
      expect(store.error()?.isNetwork).toBe(true);
    });

    it('forgets everything at sign-out, and tells the sender nothing is carried', async () => {
      await create();
      await store.refresh();

      store.clear();

      expect(store.deliveries()).toEqual([]);
      expect(assignments.set).toHaveBeenLastCalledWith(0);
    });

    it('takes up a delivery just accepted without waiting for the next read', async () => {
      await create();
      await store.refresh();

      store.adopt(delivery({ shipmentId: 'ship-new', assignedAt: '2026-10-14T05:20:00Z' }));

      expect(store.deliveries().map((d) => d.shipmentId)).toContain('ship-new');
      expect(assignments.set).toHaveBeenLastCalledWith(3);
    });

    it('reads one delivery fresh, for the screen that shows it', async () => {
      await create();
      api['delivery'].mockResolvedValue(delivery({ shipmentId: 'ship-late', version: 9 }));

      const read = await store.load('ship-late');

      expect(api['delivery']).toHaveBeenCalledWith(WHERE, 'ship-late');
      expect(read?.version).toBe(9);
      expect(store.byId('ship-late')?.version).toBe(9);
    });

    it('answers null for a delivery that is not the courier’s, as the platform does', async () => {
      await create();
      api['delivery'].mockRejectedValue(
        new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'x' }),
      );

      expect(await store.load('someone-elses')).toBeNull();
    });
  });

  describe('arriving', () => {
    it('announces arrival with the delivery’s version and the courier’s position', async () => {
      await create();
      api['advance'].mockResolvedValue({ ...later, status: 'PICKUP_PENDING', version: 2 });

      const outcome = await store.arrive(later);

      expect(api['advance']).toHaveBeenCalledWith(
        WHERE,
        later,
        'PICKUP_PENDING',
        { latitude: 41.3, longitude: 69.2, accuracyMeters: 9 },
        expect.any(String),
      );
      expect(outcome.kind).toBe('done');
      expect(store.byId('ship-late')?.status).toBe('PICKUP_PENDING');
    });

    it('goes ahead without a position when the phone has none -- the gate says whether that is enough', async () => {
      await create();
      geo.onceAnswer = 'denied';
      api['advance'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'GPS_POSITION_REQUIRED',
          detail: 'x',
        }),
      );

      const outcome = await store.arrive(later);

      expect(api['advance'].mock.calls[0][3]).toBeNull();
      expect(outcome).toEqual({ kind: 'refused', reason: 'GPS_POSITION_REQUIRED' });
    });
  });

  describe('picking up (cross-wave gate contract)', () => {
    it('is done with the checks the platform reports when the gate is soft', async () => {
      await create();
      api['pickUp'].mockResolvedValue({
        ...later,
        status: 'PICKED_UP',
        version: 2,
        checks: [
          { kind: 'GEO', outcome: 'FAILED' },
          { kind: 'PHOTO', outcome: 'SKIPPED' },
        ],
      });

      const outcome = await store.pickUp(later);

      expect(api['pickUp']).toHaveBeenCalledWith(
        WHERE,
        later,
        { latitude: 41.3, longitude: 69.2, accuracyMeters: 9 },
        expect.any(String),
      );
      expect(outcome.kind).toBe('done');
      if (outcome.kind === 'done') {
        expect(outcome.unconfirmed).toEqual(['GEO', 'PHOTO']);
      }
      expect(store.byId('ship-late')?.status).toBe('PICKED_UP');
    });

    it('is refused with the failed checks when the gate is hard, and nothing changes', async () => {
      await create();
      await store.refresh();
      api['pickUp'].mockRejectedValue(
        new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'GEO, PHOTO' }),
      );

      const outcome = await store.pickUp(later);

      expect(outcome).toEqual({ kind: 'gate-failed', failed: ['GEO', 'PHOTO'] });
      expect(store.byId('ship-late')?.status).toBe('ASSIGNED');
    });

    it('is told with the reason when a rule refuses it', async () => {
      await create();
      api['pickUp'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'KITCHEN_NOT_READY',
          detail: 'x',
        }),
      );

      expect(await store.pickUp(later)).toEqual({ kind: 'refused', reason: 'KITCHEN_NOT_READY' });
    });

    it('reloads the delivery when it moved under the courier, and says it was stale', async () => {
      await create();
      api['pickUp'].mockRejectedValue(
        new ApiError({ status: 409, code: 'STALE_VERSION', detail: 'x' }),
      );
      api['delivery'].mockResolvedValue(delivery({ shipmentId: 'ship-late', version: 5 }));

      const outcome = await store.pickUp(later);

      expect(outcome).toEqual({ kind: 'stale' });
      expect(api['delivery']).toHaveBeenCalledWith(WHERE, 'ship-late');
      expect(store.byId('ship-late')?.version).toBe(5);
    });

    it('replays the same key when the connection dropped and the courier taps again', async () => {
      await create();
      api['pickUp']
        .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }))
        .mockResolvedValueOnce({ ...later, status: 'PICKED_UP', version: 2 });

      const first = await store.pickUp(later);
      const second = await store.pickUp(later);

      expect(first.kind).toBe('failed');
      expect(second.kind).toBe('done');
      expect(api['pickUp'].mock.calls[1][3]).toBe(api['pickUp'].mock.calls[0][3]);
    });

    it('does not run a second step on the same delivery while one is in the air', async () => {
      await create();
      let release: (value: unknown) => void = () => undefined;
      api['pickUp'].mockReturnValue(new Promise((resolve) => (release = resolve)));

      const first = store.pickUp(later);
      await vi.waitFor(() => expect(store.busy()).toBe('ship-late'));
      const second = await store.pickUp(later);
      release({ ...later, status: 'PICKED_UP', version: 2 });
      await first;

      expect(second.kind).toBe('failed');
      expect(api['pickUp']).toHaveBeenCalledOnce();
    });
  });

  describe('handing over', () => {
    const carried = delivery({ shipmentId: 'ship-late', status: 'PICKED_UP', version: 3 });

    it('delivers through the handover gate, and the delivery is no longer carried', async () => {
      await create();
      await store.refresh();
      api['handOver'].mockResolvedValue({
        ...carried,
        status: 'DELIVERED',
        version: 4,
        checks: [{ kind: 'GEO', outcome: 'PASSED' }],
      });

      const outcome = await store.handOver(carried);

      expect(api['handOver'].mock.calls[0][1]).toBe(carried);
      expect(outcome.kind).toBe('done');
      expect(store.active().map((d) => d.shipmentId)).not.toContain('ship-late');
    });

    it('is refused with the failed checks in a hard gate, exactly as pickup is', async () => {
      await create();
      api['handOver'].mockRejectedValue(
        new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'PAYMENT' }),
      );

      expect(await store.handOver(carried)).toEqual({ kind: 'gate-failed', failed: ['PAYMENT'] });
    });
  });

  describe('confirming the cash', () => {
    const carried = delivery({ shipmentId: 'ship-late', status: 'PICKED_UP', version: 3 });

    it('sends what the courier counted, in minor units, against the delivery’s version', async () => {
      await create();
      api['confirmPayment'].mockResolvedValue({
        ...carried,
        version: 4,
        paymentConfirmedAt: '2026-10-14T05:40:00Z',
        paymentConfirmedMinor: 12_500_000,
      });

      const outcome = await store.confirmPayment(carried, 12_500_000);

      expect(api['confirmPayment']).toHaveBeenCalledWith(
        WHERE,
        carried,
        12_500_000,
        expect.any(String),
      );
      expect(outcome.kind).toBe('done');
      expect(store.byId('ship-late')?.paymentConfirmedMinor).toBe(12_500_000);
    });

    it('is told the amount is not what is due', async () => {
      await create();
      api['confirmPayment'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'PAYMENT_AMOUNT_MISMATCH',
          detail: 'x',
        }),
      );

      expect(await store.confirmPayment(carried, 1)).toEqual({
        kind: 'refused',
        reason: 'PAYMENT_AMOUNT_MISMATCH',
      });
    });
  });

  describe('the customer’s address', () => {
    it('is opened through the audited call and handed to the screen, not kept', async () => {
      await create();
      const door = {
        latitude: 41.31,
        longitude: 69.25,
        addressLine: 'Amir Temur 1',
        entrance: '2',
        floor: '3',
        apartment: '14',
        instructions: null,
      };
      api['revealDeliveryLocation'].mockResolvedValue(door);

      const answer = await store.reveal(later);

      expect(api['revealDeliveryLocation']).toHaveBeenCalledWith(
        WHERE,
        'ship-late',
        expect.any(String),
      );
      expect(answer).toEqual({ ok: true, location: door });
      expect(JSON.stringify(store.deliveries())).not.toContain('Amir Temur');
    });

    it('is refused in words once the delivery is finished', async () => {
      await create();
      api['revealDeliveryLocation'].mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'STEP_NOT_ALLOWED',
          detail: 'x',
        }),
      );

      const answer = await store.reveal(later);

      expect(answer).toEqual({
        ok: false,
        outcome: { kind: 'refused', reason: 'STEP_NOT_ALLOWED' },
      });
    });
  });
});
