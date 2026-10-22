import { TestBed } from '@angular/core/testing';

import { FakeGeolocation, fix } from '../../../testing/fake-device';
import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import type { CourierProfile } from '../courier/courier-profile';
import { CourierProfileStore } from '../courier/courier-profile.store';
import { GEOLOCATION } from '../device/geolocation';
import { DutyStore } from '../telemetry/duty.store';
import { WorkSession } from '../work/work-session';
import { ShiftStore } from './shift.store';

describe('ShiftStore', () => {
  const OPEN = courierProfile();
  const CLOSED = courierProfile({ openShift: null });
  const MAIN = CLOSED.branches[0];
  const OTHER = CLOSED.branches[1];

  let geo: FakeGeolocation;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let duty: {
    shiftEnded: ReturnType<typeof vi.fn>;
    breakStarted: ReturnType<typeof vi.fn>;
    breakEnded: ReturnType<typeof vi.fn>;
  };
  let refresh: ReturnType<typeof vi.fn>;
  let current: CourierProfile;

  async function create(profile: CourierProfile) {
    current = profile;
    api['me'].mockImplementation(async () => current);
    await TestBed.inject(CourierProfileStore).load();
    return TestBed.inject(ShiftStore);
  }

  beforeEach(() => {
    geo = new FakeGeolocation();
    api = {
      me: vi.fn(),
      openShift: vi.fn().mockResolvedValue({ shiftId: 's1', status: 'OPEN' }),
      closeShift: vi.fn().mockResolvedValue({
        status: 'CLOSED',
        paidSeconds: 21_900,
        breakSeconds: 1_800,
        cashHandoverId: 'handover-1',
      }),
      startBreak: vi.fn().mockResolvedValue(null),
      endBreak: vi.fn().mockResolvedValue(null),
      declareCash: vi.fn().mockResolvedValue(null),
    };
    duty = { shiftEnded: vi.fn(), breakStarted: vi.fn(), breakEnded: vi.fn() };
    refresh = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: api },
        { provide: GEOLOCATION, useValue: geo },
        { provide: DutyStore, useValue: duty },
        { provide: WorkSession, useValue: { refresh } },
      ],
    });
  });

  describe('opening', () => {
    it('opens at the main branch by default, with the handset’s point when it has one', async () => {
      geo.onceAnswer = fix({ latitude: 41.3, longitude: 69.2 });
      const shift = await create(CLOSED);

      expect(shift.selectedBranch()?.locationId).toBe(MAIN.locationId);
      await shift.open();

      expect(api['openShift']).toHaveBeenCalledWith(
        { brandId: MAIN.brandId, locationId: MAIN.locationId },
        '41.3,69.2',
        expect.any(String),
      );
      expect(refresh).toHaveBeenCalledOnce();
    });

    it('opens at another of the courier’s own branches when they choose one', async () => {
      const shift = await create(CLOSED);

      shift.choose(OTHER.locationId);
      await shift.open();

      expect(api['openShift'].mock.calls[0][0]).toEqual({
        brandId: OTHER.brandId,
        locationId: OTHER.locationId,
      });
    });

    it('ignores a branch that is not one of theirs', async () => {
      const shift = await create(CLOSED);

      shift.choose('018fe100-4000-7000-8000-00000000dead');

      expect(shift.selectedBranch()?.locationId).toBe(MAIN.locationId);
    });

    it('opens without a point when the handset will not say where it is -- the shift does not depend on it', async () => {
      geo.onceAnswer = 'denied';
      const shift = await create(CLOSED);

      await shift.open();

      expect(api['openShift'].mock.calls[0][1]).toBeNull();
      expect(shift.error()).toBeNull();
    });

    it('keeps the platform’s refusal for the screen, and does not refresh what did not change', async () => {
      api['openShift'].mockRejectedValue(
        new ApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'x' }),
      );
      const shift = await create(CLOSED);

      await shift.open();

      expect(shift.error()?.code).toBe('UNPROCESSABLE_STATE');
      expect(refresh).not.toHaveBeenCalled();
    });

    it('replays the same key when the connection dropped and the courier taps again', async () => {
      api['openShift']
        .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'off' }))
        .mockResolvedValueOnce({ shiftId: 's1' });
      const shift = await create(CLOSED);

      await shift.open();
      await shift.open();

      expect(api['openShift'].mock.calls[1][2]).toBe(api['openShift'].mock.calls[0][2]);
    });

    it('does nothing with two taps at once', async () => {
      let release: () => void = () => undefined;
      api['openShift'].mockReturnValue(new Promise<void>((resolve) => (release = resolve)));
      const shift = await create(CLOSED);

      const first = shift.open();
      const second = shift.open();
      release();
      await Promise.all([first, second]);

      expect(api['openShift']).toHaveBeenCalledOnce();
    });
  });

  describe('closing', () => {
    it('closes the open shift, lets duty go, and keeps the summary and the cash handover to declare', async () => {
      const shift = await create(OPEN);

      await shift.close();

      expect(api['closeShift']).toHaveBeenCalledWith(
        { brandId: OPEN.openShift!.brandId, locationId: OPEN.openShift!.locationId },
        OPEN.openShift!.shiftId,
        null,
        expect.any(String),
      );
      expect(duty.shiftEnded).toHaveBeenCalledOnce();
      expect(shift.lastClosed()).toEqual({
        status: 'CLOSED',
        paidSeconds: 21_900,
        breakSeconds: 1_800,
        cashHandoverId: 'handover-1',
      });
      expect(refresh).toHaveBeenCalledOnce();
    });

    it('leaves duty alone when the platform refused the close', async () => {
      api['closeShift'].mockRejectedValue(
        new ApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'x' }),
      );
      const shift = await create(OPEN);

      await shift.close();

      expect(duty.shiftEnded).not.toHaveBeenCalled();
      expect(shift.lastClosed()).toBeNull();
      expect(shift.error()?.code).toBe('RESOURCE_CONFLICT');
    });

    it('does nothing when no shift is open', async () => {
      const shift = await create(CLOSED);

      await shift.close();

      expect(api['closeShift']).not.toHaveBeenCalled();
    });

    it('forgets the previous summary when a new shift opens', async () => {
      const shift = await create(OPEN);
      await shift.close();
      expect(shift.lastClosed()).not.toBeNull();

      current = CLOSED;
      await TestBed.inject(CourierProfileStore).load();
      await shift.open();

      expect(shift.lastClosed()).toBeNull();
    });
  });

  describe('breaks', () => {
    it('starts a break on the open shift and stops the sender sampling', async () => {
      const shift = await create(OPEN);

      await shift.startBreak();

      expect(api['startBreak']).toHaveBeenCalledWith(
        { brandId: OPEN.openShift!.brandId, locationId: OPEN.openShift!.locationId },
        OPEN.openShift!.shiftId,
        expect.any(String),
      );
      expect(duty.breakStarted).toHaveBeenCalledOnce();
      expect(refresh).toHaveBeenCalledOnce();
    });

    it('ends it and lets the sender carry on', async () => {
      const shift = await create(OPEN);

      await shift.endBreak();

      expect(api['endBreak']).toHaveBeenCalledOnce();
      expect(duty.breakEnded).toHaveBeenCalledOnce();
    });

    it('does not tell the sender a break started that the platform did not accept', async () => {
      api['startBreak'].mockRejectedValue(
        new ApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'x' }),
      );
      const shift = await create(OPEN);

      await shift.startBreak();

      expect(duty.breakStarted).not.toHaveBeenCalled();
      expect(shift.error()).not.toBeNull();
    });
  });

  describe('declaring the cash handed over', () => {
    it('sends the amount in minor units to the handover the close opened', async () => {
      const shift = await create(OPEN);
      await shift.close();

      await shift.declareCash(12_500_000);

      expect(api['declareCash']).toHaveBeenCalledWith(
        { brandId: OPEN.openShift!.brandId, locationId: OPEN.openShift!.locationId },
        'handover-1',
        12_500_000,
        expect.any(String),
      );
      expect(shift.cashDeclared()).toBe(true);
    });

    it('is not offered when the shift took no cash', async () => {
      api['closeShift'].mockResolvedValue({
        status: 'CLOSED',
        paidSeconds: 100,
        breakSeconds: 0,
        cashHandoverId: null,
      });
      const shift = await create(OPEN);
      await shift.close();

      await shift.declareCash(1);

      expect(api['declareCash']).not.toHaveBeenCalled();
    });
  });
});
