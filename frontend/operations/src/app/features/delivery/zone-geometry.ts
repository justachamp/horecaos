import { boundsContain } from '../../shared/ui/map/geometry';
import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { OutlinePointResponse, ZoneOutlineResponse } from './delivery-zones-api';

/**
 * Where a zone's corners are written down as GeoJSON and read back, and nowhere else (rows `3.6`,
 * `3.6c`, ADR 0145, ADR 0037).
 *
 * **GeoJSON is `[longitude, latitude]`, and the console names a point `{latitude, longitude}`.**
 * The disagreement is exactly how a polygon ends up in the Indian Ocean: valid geometry, no error,
 * somewhere else (ADR 0037's own wording for why imported geometry is looked at before it governs
 * a fee). So both directions of the swap live here, in functions with tests that would fail if
 * either were wrong, and no screen builds or reads a coordinate pair by hand.
 */

/** The outline of a hand-drawn zone as the GeoJSON `draftVersion` takes: closed, `[lon, lat]`. */
export function toGeoJsonPolygon(ring: readonly LatLng[]): string {
  if (ring.length < 3) {
    throw new Error('A polygon needs at least three corners');
  }
  const positions = ring.map((corner) => [corner.longitude, corner.latitude]);
  // GeoJSON rings are closed: the first corner is written again at the end.
  positions.push([ring[0].longitude, ring[0].latitude]);
  return JSON.stringify({ type: 'Polygon', coordinates: [positions] });
}

/** The outer ring of every polygon in a GeoJSON document, in the console's own order, open. */
export interface ParsedGeoJson {
  readonly rings: readonly (readonly LatLng[])[];
  /** Whether the document holds anything but polygons' outer rings (holes, other shapes). */
  readonly simplified: boolean;
}

/**
 * Reads legacy geometry (`Polygon`, `MultiPolygon`, a `Feature` or `FeatureCollection` of them)
 * as `[longitude, latitude]`, which is what every GeoJSON producer writes. Anything that is not a
 * list of finite numbers is refused rather than skipped: a half-read outline drawn on a map as if
 * it were the whole thing is worse than no outline.
 *
 * @returns `null` when the text is not polygon GeoJSON at all
 */
export function parseGeoJson(text: string): ParsedGeoJson | null {
  let document: unknown;
  try {
    document = JSON.parse(text);
  } catch {
    return null;
  }
  const rings: LatLng[][] = [];
  const state = { simplified: false, ok: true };
  collect(document, rings, state);
  return state.ok && rings.length > 0 ? { rings, simplified: state.simplified } : null;
}

function collect(
  node: unknown,
  rings: LatLng[][],
  state: { simplified: boolean; ok: boolean },
): void {
  if (typeof node !== 'object' || node === null) {
    state.ok = false;
    return;
  }
  const value = node as {
    type?: unknown;
    coordinates?: unknown;
    features?: unknown;
    geometry?: unknown;
  };
  switch (value.type) {
    case 'FeatureCollection':
      if (!Array.isArray(value.features)) {
        state.ok = false;
        return;
      }
      value.features.forEach((feature) => collect(feature, rings, state));
      return;
    case 'Feature':
      collect(value.geometry, rings, state);
      return;
    case 'Polygon':
      addPolygon(value.coordinates, rings, state);
      return;
    case 'MultiPolygon':
      if (!Array.isArray(value.coordinates)) {
        state.ok = false;
        return;
      }
      value.coordinates.forEach((polygon) => addPolygon(polygon, rings, state));
      return;
    default:
      state.ok = false;
  }
}

function addPolygon(
  coordinates: unknown,
  rings: LatLng[][],
  state: { simplified: boolean; ok: boolean },
): void {
  if (!Array.isArray(coordinates) || coordinates.length === 0) {
    state.ok = false;
    return;
  }
  if (coordinates.length > 1) {
    state.simplified = true;
  }
  const outer: unknown = coordinates[0];
  if (!Array.isArray(outer)) {
    state.ok = false;
    return;
  }
  const ring: LatLng[] = [];
  for (const position of outer) {
    if (
      !Array.isArray(position) ||
      position.length < 2 ||
      typeof position[0] !== 'number' ||
      typeof position[1] !== 'number' ||
      !Number.isFinite(position[0]) ||
      !Number.isFinite(position[1])
    ) {
      state.ok = false;
      return;
    }
    // GeoJSON: longitude first.
    ring.push({ latitude: position[1], longitude: position[0] });
  }
  const first = ring[0];
  const last = ring[ring.length - 1];
  if (ring.length > 1 && first.latitude === last.latitude && first.longitude === last.longitude) {
    ring.pop();
  }
  rings.push(ring);
}

/** The outline a stored version holds, if it is one the polygon editor can edit: one polygon, no holes. */
export function editableRing(outline: ZoneOutlineResponse): readonly LatLng[] | null {
  if (outline.polygons.length !== 1 || outline.polygons[0].holes.length > 0) {
    return null;
  }
  return outline.polygons[0].ring.map(toLatLng);
}

/** The outer ring of every polygon of a stored outline, for drawing. Holes are not drawn (see the map canvas). */
export function outerRings(outline: ZoneOutlineResponse): readonly (readonly LatLng[])[] {
  return outline.polygons.map((polygon) => polygon.ring.map(toLatLng));
}

function toLatLng(point: OutlinePointResponse): LatLng {
  return { latitude: point.latitude, longitude: point.longitude };
}

/** The verdict on one outline against the region it is meant to sit in. */
export type OrderVerdict = 'INSIDE' | 'OUTSIDE' | 'LIKELY_SWAPPED' | 'NO_REGION';

/**
 * Whether an outline sits in the region, and — when it does not — whether it would if its
 * coordinates were read the other way round. That second answer is the whole point of looking at
 * an imported zone: "outside the region" is a fact, "outside, but inside once latitude and
 * longitude are swapped" is the diagnosis, and it is the mistake legacy exports actually contain.
 */
export function regionVerdict(ring: readonly LatLng[], region: MapBounds | null): OrderVerdict {
  if (region === null) {
    return 'NO_REGION';
  }
  if (ring.every((corner) => boundsContain(region, corner))) {
    return 'INSIDE';
  }
  const swapped = ring.map((corner) => ({
    latitude: corner.longitude,
    longitude: corner.latitude,
  }));
  return swapped.every((corner) => boundsContain(region, corner)) ? 'LIKELY_SWAPPED' : 'OUTSIDE';
}
