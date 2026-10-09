import { LatLng, MapBounds } from './map-provider';

/**
 * Pure geometry for the map components: validation a person sees as sentences, and nothing a
 * vendor SDK is needed for. Kept apart from the provider so it is tested without any map and so
 * the rules are the same whichever vendor draws the outline.
 *
 * The server is the authority (`ServiceZoneService` checks a polygon against its region's box
 * and PostGIS decides validity); these rules exist so a person is told before a save, not
 * instead of the server's answer.
 */

export function isLatitude(value: number): boolean {
  return Number.isFinite(value) && value >= -90 && value <= 90;
}

export function isLongitude(value: number): boolean {
  return Number.isFinite(value) && value >= -180 && value <= 180;
}

export function isLatLng(point: LatLng): boolean {
  return isLatitude(point.latitude) && isLongitude(point.longitude);
}

/** Parses a typed coordinate. Empty is `null`; anything not a finite number is `NaN`, which fails {@link isLatitude}. */
export function parseCoordinate(text: string): number | null {
  const trimmed = text.trim().replace(',', '.');
  if (trimmed === '') {
    return null;
  }
  return /^-?\d+(\.\d+)?$/.test(trimmed) ? Number(trimmed) : Number.NaN;
}

export function boundsContain(bounds: MapBounds, point: LatLng): boolean {
  return (
    point.latitude >= bounds.southWest.latitude &&
    point.latitude <= bounds.northEast.latitude &&
    point.longitude >= bounds.southWest.longitude &&
    point.longitude <= bounds.northEast.longitude
  );
}

/** The smallest box around the points, or `null` for none. */
export function boundsOf(points: readonly LatLng[]): MapBounds | null {
  if (points.length === 0) {
    return null;
  }
  let south = Infinity;
  let west = Infinity;
  let north = -Infinity;
  let east = -Infinity;
  for (const point of points) {
    south = Math.min(south, point.latitude);
    north = Math.max(north, point.latitude);
    west = Math.min(west, point.longitude);
    east = Math.max(east, point.longitude);
  }
  return {
    southWest: { latitude: south, longitude: west },
    northEast: { latitude: north, longitude: east },
  };
}

export function centreOf(bounds: MapBounds): LatLng {
  return {
    latitude: (bounds.southWest.latitude + bounds.northEast.latitude) / 2,
    longitude: (bounds.southWest.longitude + bounds.northEast.longitude) / 2,
  };
}

export type BoundsProblem = 'OUT_OF_RANGE' | 'INVERTED';

/**
 * What is wrong with a south-west / north-east box, if anything. The same two shapes the
 * database refuses in `ck_region_coordinates` and `ck_region_bbox_oriented`: an inverted or empty
 * box accepts nothing or everything and fails silently in both directions.
 */
export function boundsProblems(bounds: MapBounds): readonly BoundsProblem[] {
  const problems: BoundsProblem[] = [];
  if (!isLatLng(bounds.southWest) || !isLatLng(bounds.northEast)) {
    problems.push('OUT_OF_RANGE');
  }
  if (
    bounds.northEast.latitude <= bounds.southWest.latitude ||
    bounds.northEast.longitude <= bounds.southWest.longitude
  ) {
    problems.push('INVERTED');
  }
  return problems;
}

export type RingProblem = 'TOO_FEW' | 'DUPLICATE' | 'CROSSING' | 'OUTSIDE_REGION';

/** An outline with the closing repeat of its first corner removed. */
export function openRing(ring: readonly LatLng[]): readonly LatLng[] {
  if (ring.length > 1) {
    const first = ring[0];
    const last = ring[ring.length - 1];
    if (first.latitude === last.latitude && first.longitude === last.longitude) {
      return ring.slice(0, -1);
    }
  }
  return ring;
}

/**
 * What is wrong with an outline, in the order a person would fix it.
 *
 * `OUTSIDE_REGION` is checked only when a region box is given. It is the transposed-latitude
 * check: a polygon whose corners are swapped is perfectly valid geometry, simply somewhere else.
 */
export function ringProblems(
  ring: readonly LatLng[],
  region: MapBounds | null,
): readonly RingProblem[] {
  const corners = openRing(ring);
  const problems: RingProblem[] = [];
  if (corners.length < 3) {
    problems.push('TOO_FEW');
    return problems;
  }
  if (corners.some((corner, i) => same(corner, corners[(i + 1) % corners.length]))) {
    problems.push('DUPLICATE');
  }
  if (crosses(corners)) {
    problems.push('CROSSING');
  }
  if (region !== null && corners.some((corner) => !boundsContain(region, corner))) {
    problems.push('OUTSIDE_REGION');
  }
  return problems;
}

function same(a: LatLng, b: LatLng): boolean {
  return a.latitude === b.latitude && a.longitude === b.longitude;
}

/** Whether two non-neighbouring edges of the outline cross. Quadratic, which is fine for a zone. */
function crosses(corners: readonly LatLng[]): boolean {
  const count = corners.length;
  for (let i = 0; i < count; i++) {
    const a1 = corners[i];
    const a2 = corners[(i + 1) % count];
    for (let j = i + 1; j < count; j++) {
      // Edges that share a corner always touch there; that is not a crossing.
      if (j === i || (j + 1) % count === i || (i + 1) % count === j) {
        continue;
      }
      const b1 = corners[j];
      const b2 = corners[(j + 1) % count];
      if (segmentsCross(a1, a2, b1, b2)) {
        return true;
      }
    }
  }
  return false;
}

function orientation(a: LatLng, b: LatLng, c: LatLng): number {
  const value =
    (b.longitude - a.longitude) * (c.latitude - a.latitude) -
    (b.latitude - a.latitude) * (c.longitude - a.longitude);
  return value === 0 ? 0 : value > 0 ? 1 : -1;
}

function segmentsCross(p1: LatLng, p2: LatLng, q1: LatLng, q2: LatLng): boolean {
  const o1 = orientation(p1, p2, q1);
  const o2 = orientation(p1, p2, q2);
  const o3 = orientation(q1, q2, p1);
  const o4 = orientation(q1, q2, p2);
  return o1 !== o2 && o3 !== o4 && o1 !== 0 && o2 !== 0 && o3 !== 0 && o4 !== 0;
}
