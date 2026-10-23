import { Injectable, inject, signal } from '@angular/core';

import { toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import type { DutySession, Observation } from '../courier/courier-duty';
import type { BranchRef } from '../courier/courier-shift';
import { BATTERY } from '../device/battery';
import { GEOLOCATION, type GeoFailure, type GeoFix } from '../device/geolocation';
import { LocationService } from '../device/location.service';

/** One reading per this long, whatever the phone reports: a batch carries a few readings, not a stream. */
export const SAMPLE_INTERVAL_MS = 5_000;

/** The platform takes one batch per five seconds per courier (ADR 0045); never ask for sooner. */
export const MIN_DELAY_SECONDS = 5;

/** A cadence longer than this is a configuration slip, not a lever: the dispatcher's pin would go stale. */
export const MAX_DELAY_SECONDS = 120;

/** The longest the sender backs off after a failed post. */
export const RETRY_CEILING_SECONDS = 60;

/** A long outage carries this many batches' worth of readings and no more; older ones are dropped first. */
export const BUFFER_BATCHES = 5;

export interface SenderOptions {
  readonly where: BranchRef;
  /** The session the platform opened; its cadence and ceiling are the sender's orders. */
  readonly session: DutySession;
  /** How many deliveries the courier carries now, asked at each post. */
  readonly activeAssignments: () => number;
  /** The platform said no session is open: the owner must stop being "on duty". */
  readonly onRejected: () => void;
}

/** What the sender can truthfully say about how the last post went. */
export type SenderFailure = 'unreachable' | 'rejected';

/**
 * The telemetry sender (ADR 0045, ADR 0179 D7): samples the handset's position while a duty session
 * is open and posts the buffered readings at the cadence the platform returns.
 *
 * - **The platform sets the pace.** The session carries the cadence and each answer carries the next
 *   delay; the sender takes both, inside the platform's one-batch-per-five-seconds limit.
 * - **A late batch beats a gap.** A post that did not arrive keeps its readings and goes again with a
 *   growing delay; the buffer is bounded so a long outage cannot grow without limit.
 * - **It stops when the platform says no session is open** (`422 NO_OPEN_DUTY_SESSION`), because
 *   collection that continues after a courier signs off is the failure ADR 0045 exists to prevent.
 * - **Positions are held here, in memory, and nowhere else** -- never logged, never in a URL, never
 *   in storage. A reload loses the buffer, and that is the right trade: a stale track is worthless.
 *
 * It does not hold the wake lock (the duty store does) and does not decide whether the courier may
 * be tracked (the notice and the session do).
 */
@Injectable({ providedIn: 'root' })
export class TelemetrySender {
  private readonly api = inject(CourierApi);
  private readonly source = inject(GEOLOCATION);
  private readonly locations = inject(LocationService);
  private readonly battery = inject(BATTERY);

  private options: SenderOptions | null = null;
  private stopWatching: (() => void) | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private buffer: Observation[] = [];
  private lastSampledAt = Number.NEGATIVE_INFINITY;
  private retryDelay = 0;
  /** Bumped on every start and stop, so an answer to an old post cannot touch a new run. */
  private run = 0;

  private readonly active = signal(false);
  private readonly onBreak = signal(false);
  private readonly serverSuspended = signal(false);
  private readonly lastFailure = signal<SenderFailure | null>(null);
  private readonly sentAt = signal<number | null>(null);
  private readonly fixAt = signal<number | null>(null);
  private readonly pending = signal(0);
  private readonly geoProblem = signal<GeoFailure | null>(null);

  readonly running = this.active.asReadonly();
  /** The courier started a break here: nothing is sampled or posted. */
  readonly paused = this.onBreak.asReadonly();
  /** The platform answered that a break is running, so it kept nothing. */
  readonly suspended = this.serverSuspended.asReadonly();
  readonly failure = this.lastFailure.asReadonly();
  /** When the last post was accepted (epoch ms). */
  readonly lastSentAt = this.sentAt.asReadonly();
  /** When the handset last reported a position (epoch ms): the indicator's "is it really tracking". */
  readonly lastFixAt = this.fixAt.asReadonly();
  readonly buffered = this.pending.asReadonly();
  readonly locationProblem = this.geoProblem.asReadonly();

  start(options: SenderOptions): void {
    this.stop();
    this.run += 1;
    this.options = options;
    this.active.set(true);
    this.retryDelay = options.session.batchCadenceSeconds;
    this.stopWatching = this.source.watch(
      (fix) => this.onFix(fix),
      (reason) => {
        this.geoProblem.set(reason);
        this.locations.fail(reason);
      },
    );
    this.schedule(options.session.batchCadenceSeconds);
  }

  /** A break the courier started: stop sampling and posting until {@link resume}. */
  pause(): void {
    this.onBreak.set(true);
    this.buffer = [];
    this.pending.set(0);
  }

  resume(): void {
    this.onBreak.set(false);
  }

  /** Cancels everything and forgets every reading held. Safe to call when not running. */
  stop(): void {
    this.run += 1;
    this.stopWatching?.();
    this.stopWatching = null;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.options = null;
    this.buffer = [];
    this.lastSampledAt = Number.NEGATIVE_INFINITY;
    this.pending.set(0);
    this.active.set(false);
    this.onBreak.set(false);
    this.serverSuspended.set(false);
    this.lastFailure.set(null);
    this.sentAt.set(null);
    this.fixAt.set(null);
    this.geoProblem.set(null);
    this.locations.forget();
  }

  private onFix(fix: GeoFix): void {
    const options = this.options;
    if (options === null) {
      return;
    }
    this.geoProblem.set(null);
    this.fixAt.set(Date.now());
    this.locations.remember(fix);
    if (this.onBreak() || fix.timestamp - this.lastSampledAt < SAMPLE_INTERVAL_MS) {
      return;
    }
    this.lastSampledAt = fix.timestamp;
    this.buffer.push({
      capturedAt: new Date(fix.timestamp).toISOString(),
      latitude: fix.latitude,
      longitude: fix.longitude,
      accuracyMeters: fix.accuracyMeters,
      headingDegrees: fix.headingDegrees,
      speedMps: fix.speedMps,
      batteryPercent: null,
      deviceCharging: null,
    });
    const ceiling = options.session.maximumBatchSize * BUFFER_BATCHES;
    if (this.buffer.length > ceiling) {
      this.buffer = this.buffer.slice(this.buffer.length - ceiling);
    }
    this.pending.set(this.buffer.length);
  }

  private schedule(seconds: number): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
    }
    const run = this.run;
    const bounded = Math.min(Math.max(seconds, MIN_DELAY_SECONDS), MAX_DELAY_SECONDS);
    this.timer = setTimeout(() => void this.flush(run), bounded * 1_000);
  }

  private async flush(run: number): Promise<void> {
    const options = this.options;
    if (options === null || run !== this.run) {
      return;
    }
    const cadence = options.session.batchCadenceSeconds;
    if (this.onBreak() || this.buffer.length === 0) {
      this.schedule(cadence);
      return;
    }

    const chunk = this.buffer.slice(0, options.session.maximumBatchSize);
    const reading = await this.battery().catch(() => null);
    if (run !== this.run) {
      return;
    }
    const observations = chunk.map((observation, index) =>
      index === chunk.length - 1 && reading !== null
        ? { ...observation, batteryPercent: reading.percent, deviceCharging: reading.charging }
        : observation,
    );

    try {
      const result = await this.api.postObservations(options.where, {
        activeAssignmentCount: options.activeAssignments(),
        observations,
      });
      if (run !== this.run) {
        return;
      }
      this.drop(chunk);
      this.lastFailure.set(null);
      this.serverSuspended.set(result.suspended);
      this.sentAt.set(Date.now());
      this.retryDelay = cadence;
      this.schedule(result.nextBatchAfterSeconds);
    } catch (failure) {
      if (run !== this.run) {
        return;
      }
      const error = toApiError(failure);
      if (error.reason === 'NO_OPEN_DUTY_SESSION') {
        this.stop();
        options.onRejected();
        return;
      }
      const transient =
        error.status === 0 ||
        error.status >= 500 ||
        error.status === 429 ||
        error.isUnauthenticated;
      if (transient) {
        this.lastFailure.set('unreachable');
        this.retryDelay = Math.min(Math.max(this.retryDelay, cadence) * 2, RETRY_CEILING_SECONDS);
        this.schedule(this.retryDelay);
        return;
      }
      // The platform refused the readings themselves. Keeping them would refuse the same ones
      // forever and starve every later batch, so this chunk goes.
      this.drop(chunk);
      this.lastFailure.set('rejected');
      this.schedule(cadence);
    }
  }

  private drop(sent: readonly Observation[]): void {
    const gone = new Set(sent);
    this.buffer = this.buffer.filter((observation) => !gone.has(observation));
    this.pending.set(this.buffer.length);
  }
}
