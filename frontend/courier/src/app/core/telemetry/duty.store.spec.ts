import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { FakeGeolocation, FakeWakeLock, fix } from '../../../testing/fake-device';
import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import type { DutySession } from '../courier/courier-duty';
import { CourierProfileStore } from '../courier/courier-profile.store';
import { BATTERY } from '../device/battery';
import { GEOLOCATION } from '../device/geolocation';
import { WAKE_LOCK } from '../device/wake-lock';
import { NoticeStore } from '../notice/notice.store';
import { DutyStore, GAP_AFTER_MS } from './duty.store';
import { TelemetrySender } from './telemetry-sender';

const SHIFT = courierProfile().openShift!;
const WHERE = { brandId: SHIFT.brandId, locationId: SHIFT.locationId };

function session(overrides: Partial<DutySession> = {}): DutySession {
  return {
    sessionId: 'session-1',
    shiftId: SHIFT.shiftId,
    locationId: SHIFT.locationId,
    status: 'OPEN',
    collectionGate: 'ON_DUTY',
    startedAt: '2026-10-14T05:00:00Z',
    registrationValidUntil: '2027-01-31',
    batchCadenceSeconds: 10,
    maximumBatchSize: 60,
    ...overrides,
  };
}

describe('DutyStore', () => {
  let geo: FakeGeolocation;
  let lock: FakeWakeLock;
  let api: {
    me: ReturnType<typeof vi.fn>;
    currentDuty: ReturnType<typeof vi.fn>;
    openDuty: ReturnType<typeof vi.fn>;
    closeDuty: ReturnType<typeof vi.fn>;
    postObservations: ReturnType<typeof vi.fn>;
  };
  let acknowledged: ReturnType<typeof signal<boolean>>;

  async function create(profile = courierProfile()) {
    api.me.mockResolvedValue(profile);
    await TestBed.inject(CourierProfileStore).load();
    return TestBed.inject(DutyStore);
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-14T05:00:00Z'));
    localStorage.clear();
    acknowledged = signal(true);
    geo = new FakeGeolocation();
    lock = new FakeWakeLock();
    api = {
      me: vi.fn(),
      currentDuty: vi.fn().mockResolvedValue({ session: null }),
      openDuty: vi.fn().mockResolvedValue(session()),
      closeDuty: vi.fn().mockResolvedValue(null),
      postObservations: vi.fn().mockResolvedValue({
        observationsAccepted: 1,
        windowsWritten: 1,
        livePositionMoved: true,
        collectionGate: 'ON_DUTY',
        suspended: false,
        nextBatchAfterSeconds: 10,
      }),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: api },
        { provide: GEOLOCATION, useValue: geo },
        { provide: WAKE_LOCK, useValue: lock },
        { provide: BATTERY, useValue: async () => null },
        {
          provide: NoticeStore,
          useValue: { acknowledged },
        },
      ],
    });
  });

  afterEach(() => {
    delete (document as { visibilityState?: unknown }).visibilityState;
    TestBed.inject(TelemetrySender).stop();
    vi.useRealTimers();
    localStorage.clear();
  });

  describe('going on duty', () => {
    it('is refused before the courier has acknowledged the notice: no call, no lock, no watch', async () => {
      acknowledged.set(false);
      const duty = await create();

      await duty.goOnDuty();

      expect(duty.error()?.code).toBe('NOTICE_REQUIRED');
      expect(api.openDuty).not.toHaveBeenCalled();
      expect(lock.requests).toBe(0);
      expect(geo.watching).toBe(0);
      expect(duty.session()).toBeNull();
    });

    it('is refused when no shift is open, without asking the platform', async () => {
      const duty = await create(courierProfile({ openShift: null }));

      await duty.goOnDuty();

      expect(duty.error()?.code).toBe('NO_OPEN_SHIFT');
      expect(api.openDuty).not.toHaveBeenCalled();
    });

    it('opens the session at the shift’s branch with this installation’s id, then collects under a wake lock', async () => {
      const duty = await create();

      await duty.goOnDuty();

      expect(api.openDuty).toHaveBeenCalledWith(WHERE, expect.any(String), expect.any(String));
      expect(duty.session()?.sessionId).toBe('session-1');
      expect(duty.onDuty()).toBe(true);
      expect(geo.watching).toBe(1);
      expect(lock.held).toBe(1);
      expect(duty.wakeLock()).toBe('held');
    });

    it('uses the same device id every time on the same phone', async () => {
      const duty = await create();
      await duty.goOnDuty();
      const first = api.openDuty.mock.calls[0][1] as string;
      await duty.goOffDuty();
      await duty.goOnDuty();

      expect(api.openDuty.mock.calls[1][1]).toBe(first);
    });

    it('keeps the platform’s refusal so the screen can say why', async () => {
      api.openDuty.mockRejectedValue(
        new ApiError({
          status: 409,
          code: 'RESOURCE_CONFLICT',
          detail: 'x',
          reason: 'REGISTRATION_LAPSED',
        }),
      );
      const duty = await create();

      await duty.goOnDuty();

      expect(duty.error()?.reason).toBe('REGISTRATION_LAPSED');
      expect(duty.onDuty()).toBe(false);
      expect(geo.watching).toBe(0);
    });

    it('replays the same key when a dropped connection makes the courier tap again', async () => {
      api.openDuty
        .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }))
        .mockResolvedValueOnce(session());
      const duty = await create();

      await duty.goOnDuty();
      await duty.goOnDuty();

      expect(api.openDuty.mock.calls[1][2]).toBe(api.openDuty.mock.calls[0][2]);
      expect(duty.onDuty()).toBe(true);
    });

    it('still collects when the browser refuses the wake lock, and says the screen may sleep', async () => {
      lock.granted = false;
      const duty = await create();

      await duty.goOnDuty();

      expect(duty.onDuty()).toBe(true);
      expect(duty.wakeLock()).toBe('refused');
    });
  });

  describe('a session that is already open', () => {
    it('is picked up again after a reload: the sender starts without opening a second one', async () => {
      api.currentDuty.mockResolvedValue({ session: session() });
      const duty = await create();

      await duty.restore();

      expect(api.currentDuty).toHaveBeenCalledWith(WHERE);
      expect(api.openDuty).not.toHaveBeenCalled();
      expect(duty.onDuty()).toBe(true);
      expect(geo.watching).toBe(1);
    });

    it('stays off when the platform has none', async () => {
      const duty = await create();

      await duty.restore();

      expect(duty.session()).toBeNull();
      expect(duty.onDuty()).toBe(false);
      expect(geo.watching).toBe(0);
    });

    it('is not collected on this phone until the notice is acknowledged here, and says so', async () => {
      acknowledged.set(false);
      api.currentDuty.mockResolvedValue({ session: session() });
      const duty = await create();

      await duty.restore();

      expect(duty.awaitingNotice()).toBe(true);
      expect(duty.onDuty()).toBe(false);
      expect(geo.watching).toBe(0);
      expect(lock.requests).toBe(0);
    });

    it('stops waiting for the notice the moment it is acknowledged, and goes on duty on the session already open', async () => {
      acknowledged.set(false);
      api.currentDuty.mockResolvedValue({ session: session() });
      api.openDuty.mockResolvedValue(session());
      const duty = await create();
      await duty.restore();
      expect(duty.awaitingNotice()).toBe(true);

      acknowledged.set(true);

      expect(duty.awaitingNotice()).toBe(false);
      await duty.goOnDuty();
      // The platform answers an open with the session already open, so this attaches rather than duplicates.
      expect(api.openDuty).toHaveBeenCalledOnce();
      expect(duty.onDuty()).toBe(true);
      expect(duty.session()?.sessionId).toBe('session-1');
    });

    it('asks nothing when there is no shift', async () => {
      const duty = await create(courierProfile({ openShift: null }));

      await duty.restore();

      expect(api.currentDuty).not.toHaveBeenCalled();
    });
  });

  describe('going off duty', () => {
    it('closes the session, stops collecting and lets the screen sleep', async () => {
      const duty = await create();
      await duty.goOnDuty();

      await duty.goOffDuty();

      expect(api.closeDuty).toHaveBeenCalledWith(WHERE, 'session-1', expect.any(String));
      expect(duty.session()).toBeNull();
      expect(geo.watching).toBe(0);
      expect(lock.held).toBe(0);
      expect(duty.wakeLock()).toBe('none');
    });

    it('stays on duty, and says so, when the platform could not be told', async () => {
      const duty = await create();
      await duty.goOnDuty();
      api.closeDuty.mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK', detail: 'off' }));

      await duty.goOffDuty();

      expect(duty.onDuty()).toBe(true);
      expect(duty.error()?.isNetwork).toBe(true);
    });

    it('lets go without asking when the shift has ended -- the platform closed the session itself', async () => {
      const duty = await create();
      await duty.goOnDuty();

      duty.shiftEnded();

      expect(api.closeDuty).not.toHaveBeenCalled();
      expect(duty.session()).toBeNull();
      expect(geo.watching).toBe(0);
      expect(lock.held).toBe(0);
    });

    it('lets go when the platform says no session is open any more', async () => {
      api.postObservations.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          detail: 'x',
          reason: 'NO_OPEN_DUTY_SESSION',
        }),
      );
      const duty = await create();
      await duty.goOnDuty();
      geo.emit(fix());

      await vi.advanceTimersByTimeAsync(10_000);

      expect(duty.session()).toBeNull();
      expect(duty.onDuty()).toBe(false);
      expect(duty.endedByPlatform()).toBe(true);
      expect(lock.held).toBe(0);
    });
  });

  describe('the wake lock', () => {
    it('is taken again when the page comes back, because the browser drops it whenever the page is hidden', async () => {
      const duty = await create();
      await duty.goOnDuty();

      lock.browserReleases();
      expect(duty.wakeLock()).toBe('released');

      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);

      expect(lock.requests).toBe(2);
      expect(duty.wakeLock()).toBe('held');
    });

    it('is not taken while the page is hidden, nor once the courier is off duty', async () => {
      const duty = await create();
      await duty.goOnDuty();
      lock.browserReleases();
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);
      expect(lock.requests).toBe(1);

      await duty.goOffDuty();
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
      await vi.advanceTimersByTimeAsync(0);

      expect(lock.requests).toBe(1);
    });
  });

  describe('the indicator', () => {
    it('is off with no session, tracking while positions arrive, and searching when they stop', async () => {
      const duty = await create();
      expect(duty.indicator()).toBe('off');

      await duty.goOnDuty();
      geo.emit(fix());
      await vi.advanceTimersByTimeAsync(1_000);
      expect(duty.indicator()).toBe('tracking');

      await vi.advanceTimersByTimeAsync(GAP_AFTER_MS + 5_000);
      expect(duty.indicator()).toBe('searching');

      geo.emit(fix());
      await vi.advanceTimersByTimeAsync(5_000);
      expect(duty.indicator()).toBe('tracking');
    });

    it('says the location is refused when the handset denies it', async () => {
      const duty = await create();
      await duty.goOnDuty();

      geo.fail('denied');

      expect(duty.indicator()).toBe('denied');
    });

    it('says a break is running when the courier started one, and after it ends goes back to tracking', async () => {
      const duty = await create();
      await duty.goOnDuty();
      geo.emit(fix());

      duty.breakStarted();
      expect(duty.indicator()).toBe('break');

      duty.breakEnded();
      geo.emit(fix({ timestamp: Date.now() + 6_000 }));
      await vi.advanceTimersByTimeAsync(5_000);
      expect(duty.indicator()).toBe('tracking');
    });

    it('says the server is out of reach when posts keep failing', async () => {
      api.postObservations.mockRejectedValue(
        new ApiError({ status: 0, code: 'NETWORK', detail: 'off' }),
      );
      const duty = await create();
      await duty.goOnDuty();
      geo.emit(fix());

      await vi.advanceTimersByTimeAsync(10_000);

      expect(duty.indicator()).toBe('unreachable');
    });
  });

  describe('assignments', () => {
    it('reports the count the deliveries screen last gave it with each batch', async () => {
      const duty = await create();
      duty.assignments.set(2);
      await duty.goOnDuty();
      geo.emit(fix());

      await vi.advanceTimersByTimeAsync(10_000);

      expect(api.postObservations.mock.calls[0][1].activeAssignmentCount).toBe(2);
    });
  });
});
