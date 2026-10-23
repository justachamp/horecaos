import { TestBed } from '@angular/core/testing';

import { FakeGeolocation, fix } from '../../../testing/fake-device';
import { ApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import type { DutySession, ObservationBatchResult } from '../courier/courier-duty';
import type { BranchRef } from '../courier/courier-shift';
import { BATTERY } from '../device/battery';
import { GEOLOCATION } from '../device/geolocation';
import { LocationService } from '../device/location.service';
import {
  BUFFER_BATCHES,
  SAMPLE_INTERVAL_MS,
  TelemetrySender,
  type SenderOptions,
} from './telemetry-sender';

const WHERE: BranchRef = { brandId: 'brand-1', locationId: 'location-1' };

function session(overrides: Partial<DutySession> = {}): DutySession {
  return {
    sessionId: 'session-1',
    shiftId: 'shift-1',
    locationId: 'location-1',
    status: 'OPEN',
    collectionGate: 'ON_DUTY',
    startedAt: '2026-10-14T05:00:00Z',
    registrationValidUntil: '2027-01-31',
    batchCadenceSeconds: 10,
    maximumBatchSize: 60,
    ...overrides,
  };
}

function answer(overrides: Partial<ObservationBatchResult> = {}): ObservationBatchResult {
  return {
    observationsAccepted: 1,
    windowsWritten: 1,
    livePositionMoved: true,
    collectionGate: 'ON_DUTY',
    suspended: false,
    nextBatchAfterSeconds: 10,
    ...overrides,
  };
}

describe('TelemetrySender', () => {
  let geo: FakeGeolocation;
  let postObservations: ReturnType<typeof vi.fn>;
  let onRejected: ReturnType<typeof vi.fn<() => void>>;
  let sender: TelemetrySender;
  let assignments = 0;

  function start(overrides: Partial<SenderOptions> = {}) {
    sender.start({
      where: WHERE,
      session: session(),
      activeAssignments: () => assignments,
      onRejected,
      ...overrides,
    });
  }

  /** A fix `seconds` after the previous spec clock tick, the way a phone reports one. */
  const emitAt = (offsetMs = 0) => geo.emit(fix({ timestamp: Date.now() + offsetMs }));

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-14T05:00:00Z'));
    assignments = 0;
    geo = new FakeGeolocation();
    postObservations = vi.fn().mockResolvedValue(answer());
    onRejected = vi.fn<() => void>();
    TestBed.configureTestingModule({
      providers: [
        { provide: GEOLOCATION, useValue: geo },
        { provide: BATTERY, useValue: async () => ({ percent: 81, charging: true }) },
        { provide: CourierApi, useValue: { postObservations } },
      ],
    });
    sender = TestBed.inject(TelemetrySender);
  });

  afterEach(() => {
    sender.stop();
    vi.useRealTimers();
  });

  it('posts what it has buffered when the platform’s cadence comes round, not before', async () => {
    start();
    emitAt();

    await vi.advanceTimersByTimeAsync(9_999);
    expect(postObservations).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(postObservations).toHaveBeenCalledOnce();
    const [where, batch] = postObservations.mock.calls[0];
    expect(where).toEqual(WHERE);
    expect(batch.observations).toHaveLength(1);
    expect(batch.observations[0]).toMatchObject({
      capturedAt: '2026-10-14T05:00:00.000Z',
      latitude: 41.2995,
      longitude: 69.2401,
      accuracyMeters: 12,
    });
  });

  it('obeys a cadence the session names, whatever the app would have chosen', async () => {
    start({ session: session({ batchCadenceSeconds: 30 }) });
    emitAt();

    await vi.advanceTimersByTimeAsync(29_000);
    expect(postObservations).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(postObservations).toHaveBeenCalledOnce();
  });

  it('follows the next-batch delay on each answer, kept inside what the platform will accept', async () => {
    postObservations
      .mockResolvedValueOnce(answer({ nextBatchAfterSeconds: 20 }))
      .mockResolvedValueOnce(answer({ nextBatchAfterSeconds: 1 }))
      .mockResolvedValue(answer());
    start();

    emitAt();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(postObservations).toHaveBeenCalledTimes(1);

    emitAt(SAMPLE_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(19_999);
    expect(postObservations).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(postObservations).toHaveBeenCalledTimes(2);

    // "1 second" is below the one-batch-per-five-seconds limit (ADR 0045); the sender waits five.
    emitAt(2 * SAMPLE_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(4_999);
    expect(postObservations).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(postObservations).toHaveBeenCalledTimes(3);
  });

  it('posts nothing when there is nothing to post', async () => {
    start();

    await vi.advanceTimersByTimeAsync(60_000);

    expect(postObservations).not.toHaveBeenCalled();
  });

  it('keeps one reading per sampling interval however often the phone reports', async () => {
    start();

    for (let second = 0; second < 10; second += 1) {
      emitAt(second * 1_000);
    }
    await vi.advanceTimersByTimeAsync(10_000);

    const batch = postObservations.mock.calls[0][1];
    expect(batch.observations.length).toBeLessThanOrEqual(2);
    expect(batch.observations.length).toBeGreaterThanOrEqual(1);
  });

  it('sends the battery on the newest reading only, and the shift’s assignment count', async () => {
    assignments = 2;
    start();
    emitAt();
    emitAt(SAMPLE_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(10_000);

    const batch = postObservations.mock.calls[0][1];
    expect(batch.activeAssignmentCount).toBe(2);
    expect(batch.observations).toHaveLength(2);
    expect(batch.observations[0]).toMatchObject({ batteryPercent: null, deviceCharging: null });
    expect(batch.observations[1]).toMatchObject({ batteryPercent: 81, deviceCharging: true });
  });

  it('never puts more than the session’s ceiling in one post, oldest first, and sends the rest next', async () => {
    start({ session: session({ maximumBatchSize: 2 }) });
    for (let n = 0; n < 4; n += 1) {
      emitAt(n * SAMPLE_INTERVAL_MS);
    }

    await vi.advanceTimersByTimeAsync(10_000);
    expect(postObservations.mock.calls[0][1].observations).toHaveLength(2);
    expect(postObservations.mock.calls[0][1].observations[0].capturedAt).toBe(
      '2026-10-14T05:00:00.000Z',
    );

    await vi.advanceTimersByTimeAsync(10_000);
    expect(postObservations.mock.calls[1][1].observations).toHaveLength(2);
    expect(postObservations.mock.calls[1][1].observations[0].capturedAt).toBe(
      '2026-10-14T05:00:10.000Z',
    );
  });

  it('says so while the platform reports a break, and says nothing once it does not', async () => {
    postObservations
      .mockResolvedValueOnce(answer({ suspended: true }))
      .mockResolvedValue(answer({ suspended: false }));
    start();

    emitAt();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(sender.suspended()).toBe(true);

    emitAt(SAMPLE_INTERVAL_MS);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(sender.suspended()).toBe(false);
  });

  it('keeps its readings through a dropped connection and sends them late rather than losing them', async () => {
    postObservations
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }))
      .mockResolvedValue(answer());
    start();
    emitAt();

    await vi.advanceTimersByTimeAsync(10_000);
    expect(sender.failure()).toBe('unreachable');
    expect(sender.buffered()).toBe(1);

    await vi.advanceTimersByTimeAsync(20_000);
    expect(postObservations).toHaveBeenCalledTimes(2);
    expect(postObservations.mock.calls[1][1].observations).toEqual(
      postObservations.mock.calls[0][1].observations,
    );
    expect(sender.failure()).toBeNull();
    expect(sender.buffered()).toBe(0);
  });

  it('bounds what it carries through a long outage, dropping the oldest', async () => {
    postObservations.mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK', detail: 'off' }));
    start({ session: session({ maximumBatchSize: 2 }) });

    for (let n = 0; n < BUFFER_BATCHES * 2 + 6; n += 1) {
      emitAt(n * SAMPLE_INTERVAL_MS);
    }

    expect(sender.buffered()).toBe(BUFFER_BATCHES * 2);
  });

  it('stops for good, forgets what it held and tells its owner when the platform says no session is open', async () => {
    postObservations.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        detail: 'x',
        reason: 'NO_OPEN_DUTY_SESSION',
      }),
    );
    start();
    emitAt();

    await vi.advanceTimersByTimeAsync(10_000);

    expect(onRejected).toHaveBeenCalledOnce();
    expect(sender.running()).toBe(false);
    expect(sender.buffered()).toBe(0);
    expect(geo.watching).toBe(0);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(postObservations).toHaveBeenCalledTimes(1);
  });

  it('drops a batch the platform refuses as invalid so one bad reading cannot block the rest', async () => {
    postObservations
      .mockRejectedValueOnce(new ApiError({ status: 400, code: 'VALIDATION_FAILED', detail: 'x' }))
      .mockResolvedValue(answer());
    start();
    emitAt();

    await vi.advanceTimersByTimeAsync(10_000);

    expect(sender.buffered()).toBe(0);
    expect(sender.running()).toBe(true);
  });

  it('does not sample or post during a break the courier started, and carries on after', async () => {
    start();
    emitAt();
    sender.pause();
    emitAt(SAMPLE_INTERVAL_MS);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(postObservations).not.toHaveBeenCalled();

    sender.resume();
    emitAt();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(postObservations).toHaveBeenCalledOnce();
    expect(postObservations.mock.calls[0][1].observations).toHaveLength(1);
    expect(postObservations.mock.calls[0][1].observations[0].capturedAt).toBe(
      '2026-10-14T05:00:30.000Z',
    );
  });

  it('tells the location service about every fix, so a step uses the position the app already has', async () => {
    start();
    emitAt();

    const position = await TestBed.inject(LocationService).position();

    expect(position).toEqual({ latitude: 41.2995, longitude: 69.2401, accuracyMeters: 12 });
    expect(geo.onceCalls).toBe(0);
  });

  it('reports a refused or missing location instead of failing quietly', async () => {
    start();

    geo.fail('denied');

    expect(sender.locationProblem()).toBe('denied');
    emitAt();
    expect(sender.locationProblem()).toBeNull();
  });

  it('stops watching and posting when stopped, and a second start begins clean', async () => {
    start();
    emitAt();
    sender.stop();

    await vi.advanceTimersByTimeAsync(30_000);
    expect(postObservations).not.toHaveBeenCalled();
    expect(geo.watching).toBe(0);
    expect(sender.buffered()).toBe(0);

    start();
    expect(geo.watching).toBe(1);
    expect(sender.running()).toBe(true);
  });
});
