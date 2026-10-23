import { InjectionToken } from '@angular/core';

/** One position from the handset, in the shape the app uses everywhere (heading and speed are often unknown). */
export interface GeoFix {
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
  readonly headingDegrees: number | null;
  readonly speedMps: number | null;
  /** Milliseconds since the epoch, from the fix itself and not from this app's clock. */
  readonly timestamp: number;
}

/** Why a position could not be had. `denied` is the courier's own choice and is told so; the rest are the handset's. */
export type GeoFailure = 'denied' | 'unavailable' | 'timeout' | 'unsupported';

export class GeolocationError extends Error {
  constructor(readonly reason: GeoFailure) {
    super(`geolocation: ${reason}`);
    this.name = 'GeolocationError';
  }
}

/**
 * The handset's location, behind a seam: the app asks this, never `navigator` directly, so a spec
 * (and the browser's own emulation in development) can stand in for a phone.
 */
export interface GeolocationSource {
  /** Streams positions until the returned function is called. */
  watch(onFix: (fix: GeoFix) => void, onError: (reason: GeoFailure) => void): () => void;
  /** One position, or a {@link GeolocationError}. */
  once(timeoutMs: number): Promise<GeoFix>;
}

export const GEOLOCATION = new InjectionToken<GeolocationSource>('GEOLOCATION', {
  providedIn: 'root',
  factory: browserGeolocation,
});

function failureOf(error: GeolocationPositionError): GeoFailure {
  switch (error.code) {
    case 1:
      return 'denied';
    case 3:
      return 'timeout';
    default:
      return 'unavailable';
  }
}

function fixOf(position: GeolocationPosition): GeoFix {
  const { latitude, longitude, accuracy, heading, speed } = position.coords;
  return {
    latitude,
    longitude,
    accuracyMeters: accuracy,
    headingDegrees: typeof heading === 'number' && Number.isFinite(heading) ? heading : null,
    speedMps: typeof speed === 'number' && Number.isFinite(speed) ? speed : null,
    timestamp: position.timestamp,
  };
}

function browserGeolocation(): GeolocationSource {
  const api = (): Geolocation | null => globalThis.navigator?.geolocation ?? null;
  return {
    watch(onFix, onError) {
      const geolocation = api();
      if (geolocation === null) {
        onError('unsupported');
        return () => undefined;
      }
      const id = geolocation.watchPosition(
        (position) => onFix(fixOf(position)),
        (error) => onError(failureOf(error)),
        { enableHighAccuracy: true, maximumAge: 5_000, timeout: 30_000 },
      );
      return () => geolocation.clearWatch(id);
    },
    once(timeoutMs) {
      return new Promise<GeoFix>((resolve, reject) => {
        const geolocation = api();
        if (geolocation === null) {
          reject(new GeolocationError('unsupported'));
          return;
        }
        geolocation.getCurrentPosition(
          (position) => resolve(fixOf(position)),
          (error) => reject(new GeolocationError(failureOf(error))),
          { enableHighAccuracy: true, maximumAge: 10_000, timeout: timeoutMs },
        );
      });
    },
  };
}
