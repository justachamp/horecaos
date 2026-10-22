import {
  GeolocationError,
  type GeoFailure,
  type GeoFix,
  type GeolocationSource,
} from '../app/core/device/geolocation';
import type { WakeLockSource } from '../app/core/device/wake-lock';

/** A fix at a fixed place in Tashkent, stamped now. Override what a case is about. */
export function fix(overrides: Partial<GeoFix> = {}): GeoFix {
  return {
    latitude: 41.2995,
    longitude: 69.2401,
    accuracyMeters: 12,
    headingDegrees: null,
    speedMps: null,
    timestamp: Date.now(),
    ...overrides,
  };
}

/**
 * A handset's location a spec drives by hand: `emit` is the browser calling back, `fail` is the
 * browser refusing, and `once` answers whatever the spec set up (a fix, or a failure).
 */
export class FakeGeolocation implements GeolocationSource {
  private readonly watchers = new Set<{
    onFix: (fix: GeoFix) => void;
    onError: (reason: GeoFailure) => void;
  }>();

  /** What the next one-shot request answers. */
  onceAnswer: GeoFix | GeoFailure = 'unavailable';
  onceCalls = 0;

  get watching(): number {
    return this.watchers.size;
  }

  watch(onFix: (fix: GeoFix) => void, onError: (reason: GeoFailure) => void): () => void {
    const watcher = { onFix, onError };
    this.watchers.add(watcher);
    return () => this.watchers.delete(watcher);
  }

  once(): Promise<GeoFix> {
    this.onceCalls += 1;
    return typeof this.onceAnswer === 'string'
      ? Promise.reject(new GeolocationError(this.onceAnswer))
      : Promise.resolve(this.onceAnswer);
  }

  emit(next: GeoFix = fix()): void {
    [...this.watchers].forEach((watcher) => watcher.onFix(next));
  }

  fail(reason: GeoFailure): void {
    [...this.watchers].forEach((watcher) => watcher.onError(reason));
  }
}

/** A wake lock a spec can refuse or have the browser take back. */
export class FakeWakeLock implements WakeLockSource {
  granted = true;
  requests = 0;
  held = 0;
  private onReleased: (() => void) | null = null;

  async request(onReleased: () => void): Promise<(() => Promise<void>) | null> {
    this.requests += 1;
    if (!this.granted) {
      return null;
    }
    this.held += 1;
    this.onReleased = onReleased;
    return async () => {
      this.held = Math.max(0, this.held - 1);
    };
  }

  /** The browser lets go on its own, as it does whenever the page is hidden. */
  browserReleases(): void {
    this.held = Math.max(0, this.held - 1);
    this.onReleased?.();
  }
}
