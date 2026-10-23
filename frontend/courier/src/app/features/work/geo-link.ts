import type { SafeUrl } from '@angular/platform-browser';

/**
 * A `geo:` link, which hands a position to whichever maps app the phone has -- the app itself
 * calls no map service and sends nobody a coordinate.
 *
 * Angular's URL sanitiser does not know `geo:`, so the link is trusted explicitly; the only things
 * in it are two numbers this function has checked are finite, so there is nothing to inject.
 */
export function geoUri(
  sanitizer: { bypassSecurityTrustUrl(value: string): SafeUrl },
  latitude: number | null,
  longitude: number | null,
): SafeUrl | null {
  if (
    latitude === null ||
    longitude === null ||
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude)
  ) {
    return null;
  }
  return sanitizer.bypassSecurityTrustUrl(`geo:${latitude},${longitude}`);
}
