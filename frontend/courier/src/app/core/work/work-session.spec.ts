import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
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
