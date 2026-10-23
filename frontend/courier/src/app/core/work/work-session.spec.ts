import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import { DeliveriesStore } from '../deliveries/deliveries.store';
import { OffersStore } from '../offers/offers.store';
import { DutyStore } from '../telemetry/duty.store';
import { WorkSession } from './work-session';

describe('WorkSession', () => {
  let me: ReturnType<typeof vi.fn>;
  let restore: ReturnType<typeof vi.fn>;
  let shiftEnded: ReturnType<typeof vi.fn>;
  let startOffers: ReturnType<typeof vi.fn>;
  let stopOffers: ReturnType<typeof vi.fn>;
  let refreshDeliveries: ReturnType<typeof vi.fn>;
  let clearDeliveries: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    me = vi.fn().mockResolvedValue(courierProfile());
    restore = vi.fn().mockResolvedValue(undefined);
    shiftEnded = vi.fn();
    startOffers = vi.fn();
    stopOffers = vi.fn();
    refreshDeliveries = vi.fn().mockResolvedValue(undefined);
    clearDeliveries = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me } },
        { provide: DutyStore, useValue: { restore, shiftEnded } },
        { provide: OffersStore, useValue: { start: startOffers, stop: stopOffers } },
        {
          provide: DeliveriesStore,
          useValue: { refresh: refreshDeliveries, clear: clearDeliveries },
        },
      ],
    });
  });

  it('reads the record once and picks up a duty session the platform still has open', async () => {
    const work = TestBed.inject(WorkSession);

    await work.begin();
    await work.begin();

    expect(me).toHaveBeenCalledOnce();
    expect(restore).toHaveBeenCalledOnce();
    expect(shiftEnded).not.toHaveBeenCalled();
  });

  it('polls for offers at the shift’s branch, and reads the deliveries the courier carries', async () => {
    await TestBed.inject(WorkSession).begin();

    expect(startOffers).toHaveBeenCalledWith({
      brandId: courierProfile().openShift!.brandId,
      locationId: courierProfile().openShift!.locationId,
    });
    expect(refreshDeliveries).toHaveBeenCalledOnce();
    expect(stopOffers).not.toHaveBeenCalled();
  });

  it('offers nothing with no shift, but still reads what is carried so it can be finished', async () => {
    me.mockResolvedValue(courierProfile({ openShift: null }));

    await TestBed.inject(WorkSession).begin();

    expect(stopOffers).toHaveBeenCalledOnce();
    expect(startOffers).not.toHaveBeenCalled();
    expect(refreshDeliveries).toHaveBeenCalledOnce();
  });

  it('lets duty go when the record says no shift is open', async () => {
    me.mockResolvedValue(courierProfile({ openShift: null }));

    await TestBed.inject(WorkSession).begin();

    expect(shiftEnded).toHaveBeenCalledOnce();
    expect(restore).not.toHaveBeenCalled();
  });

  it('re-reads the record before it reconciles, so a shift closed elsewhere is noticed', async () => {
    const work = TestBed.inject(WorkSession);
    await work.begin();
    me.mockResolvedValue(courierProfile({ openShift: null }));

    await work.refresh();

    expect(me).toHaveBeenCalledTimes(2);
    expect(shiftEnded).toHaveBeenCalledOnce();
  });

  describe('when the first read of the record fails', () => {
    const offline = () => new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' });

    it('tears nothing down and starts nothing on a record it does not have', async () => {
      me.mockRejectedValueOnce(offline());

      await TestBed.inject(WorkSession).begin();

      // Nothing is known about the shift: a courier whose phone has just lost the signal is not
      // a courier who has no shift, so duty is not ended and the deliveries are not cleared.
      expect(shiftEnded).not.toHaveBeenCalled();
      expect(stopOffers).not.toHaveBeenCalled();
      expect(startOffers).not.toHaveBeenCalled();
      expect(restore).not.toHaveBeenCalled();
      expect(refreshDeliveries).not.toHaveBeenCalled();
    });

    it('reads the record again the next time something asks it to begin, and then brings everything up', async () => {
      me.mockRejectedValueOnce(offline());
      const work = TestBed.inject(WorkSession);
      await work.begin();

      await work.begin();

      expect(me).toHaveBeenCalledTimes(2);
      expect(restore).toHaveBeenCalledOnce();
      expect(startOffers).toHaveBeenCalledOnce();
      expect(refreshDeliveries).toHaveBeenCalledOnce();
    });

    it('brings everything up when the Retry that finally loads the record goes through refresh, and then counts as begun', async () => {
      me.mockRejectedValueOnce(offline());
      const work = TestBed.inject(WorkSession);
      await work.begin();

      await work.refresh();

      expect(restore).toHaveBeenCalledOnce();
      expect(startOffers).toHaveBeenCalledOnce();
      expect(refreshDeliveries).toHaveBeenCalledOnce();

      await work.begin();
      expect(me).toHaveBeenCalledTimes(2);
      expect(restore).toHaveBeenCalledOnce();
    });

    it('keeps trying while the record cannot be had, and does not reconcile on a refresh that failed too', async () => {
      me.mockRejectedValue(offline());
      const work = TestBed.inject(WorkSession);
      await work.begin();

      await work.refresh();

      expect(shiftEnded).not.toHaveBeenCalled();
      expect(startOffers).not.toHaveBeenCalled();
      expect(refreshDeliveries).not.toHaveBeenCalled();

      me.mockResolvedValue(courierProfile());
      await work.begin();
      expect(startOffers).toHaveBeenCalledOnce();
    });

    it('treats an account that is no courier here as an answer, not a failure to retry', async () => {
      me.mockRejectedValue(
        new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'not a courier' }),
      );
      const work = TestBed.inject(WorkSession);

      await work.begin();
      await work.begin();

      expect(me).toHaveBeenCalledOnce();
      expect(shiftEnded).toHaveBeenCalledOnce();
    });
  });

  it('stops everything at sign-out and starts again at the next sign-in', async () => {
    const work = TestBed.inject(WorkSession);
    await work.begin();

    work.end();
    expect(shiftEnded).toHaveBeenCalledOnce();
    expect(stopOffers).toHaveBeenCalledOnce();
    expect(clearDeliveries).toHaveBeenCalledOnce();

    await work.begin();
    expect(me).toHaveBeenCalledTimes(2);
  });
});
