import { Injectable, inject, signal } from '@angular/core';

import type { PositionBody } from '../courier/courier-work';
import { GEOLOCATION, GeolocationError, type GeoFailure, type GeoFix } from './geolocation';

/** A fix this young is as good as asking again: a courier tapping "picked up" does not wait for a satellite. */
export const FRESH_FIX_MS = 15_000;

/** How long a single request waits before the step goes ahead without a position. */
export const ONCE_TIMEOUT_MS = 8_000;

/**
 * The courier's position for the moments the platform measures it (accepting an offer, arriving,
 * picking up, handing over, opening or closing a shift).
 *
 * A position is read, handed to the one call that needs it, and forgotten: it is never logged, never
 * put in a URL and never stored. The duty sender feeds the latest fix in, so while a courier is on
 * duty a step uses the position the app already has and does not wait for a new one.
 *
 * `null` is an honest answer, not an error: the gate that wanted the position answers
 * `GPS_POSITION_REQUIRED` and the screen says what to switch on. The app never invents a position.
 */
@Injectable({ providedIn: 'root' })
export class LocationService {
  private readonly source = inject(GEOLOCATION);
  private readonly now = (): number => Date.now();

  private latest: GeoFix | null = null;
  private readonly failure = signal<GeoFailure | null>(null);

  /** Why the last attempt to locate the handset failed, or null if it has not or the last one worked. */
  readonly problem = this.failure.asReadonly();

  /** The duty sender hands over every fix it sees. */
  remember(fix: GeoFix): void {
    this.latest = fix;
    this.failure.set(null);
  }

  /** The sender reports a failure of its watch. */
  fail(reason: GeoFailure): void {
    this.failure.set(reason);
  }

  forget(): void {
    this.latest = null;
  }

  async position(): Promise<PositionBody | null> {
    const fix = await this.fix();
    return fix === null
      ? null
      : { latitude: fix.latitude, longitude: fix.longitude, accuracyMeters: fix.accuracyMeters };
  }

  /** `"latitude,longitude"`, the shape a shift stores its opening and closing point in. */
  async point(): Promise<string | null> {
    const fix = await this.fix();
    return fix === null ? null : `${fix.latitude},${fix.longitude}`;
  }

  private async fix(): Promise<GeoFix | null> {
    if (this.latest !== null && this.now() - this.latest.timestamp <= FRESH_FIX_MS) {
      return this.latest;
    }
    try {
      const fix = await this.source.once(ONCE_TIMEOUT_MS);
      this.latest = fix;
      this.failure.set(null);
      return fix;
    } catch (failure) {
      this.failure.set(failure instanceof GeolocationError ? failure.reason : 'unavailable');
      return null;
    }
  }
}
