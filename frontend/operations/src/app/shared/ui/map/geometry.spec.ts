import { describe, expect, it } from 'vitest';

import {
  boundsContain,
  boundsOf,
  boundsProblems,
  centreOf,
  isLatLng,
  openRing,
  parseCoordinate,
  ringProblems,
} from './geometry';
import { LatLng } from './map-provider';

const p = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });
const TASHKENT = { southWest: p(41.15, 69.04), northEast: p(41.47, 69.46) };
const SQUARE = [p(41.3, 69.2), p(41.3, 69.3), p(41.4, 69.3), p(41.4, 69.2)];

describe('parseCoordinate', () => {
  it('reads a decimal number, a comma as the decimal mark, and blank as nothing', () => {
    expect(parseCoordinate('41.311')).toBe(41.311);
    expect(parseCoordinate(' -69,24 ')).toBe(-69.24);
    expect(parseCoordinate('')).toBeNull();
    expect(parseCoordinate('   ')).toBeNull();
  });

  it('refuses anything else as NaN, so a typo is never read as a coordinate', () => {
    expect(parseCoordinate('41.3.1')).toBeNaN();
    expect(parseCoordinate('41.3N')).toBeNaN();
    expect(parseCoordinate('1e3')).toBeNaN();
    expect(parseCoordinate('Infinity')).toBeNaN();
  });
});

describe('isLatLng', () => {
  it('holds each axis to its own range', () => {
    expect(isLatLng(p(41.3, 69.2))).toBe(true);
    expect(isLatLng(p(90, 180))).toBe(true);
    expect(isLatLng(p(90.0001, 0))).toBe(false);
    expect(isLatLng(p(0, -180.5))).toBe(false);
    expect(isLatLng(p(Number.NaN, 0))).toBe(false);
  });
});

describe('bounds', () => {
  it('contains its edges and nothing past them', () => {
    expect(boundsContain(TASHKENT, p(41.3, 69.2))).toBe(true);
    expect(boundsContain(TASHKENT, p(41.15, 69.04))).toBe(true);
    expect(boundsContain(TASHKENT, p(41.1499, 69.2))).toBe(false);
    // A latitude/longitude transposition is a valid point somewhere else entirely.
    expect(boundsContain(TASHKENT, p(69.2, 41.3))).toBe(false);
  });

  it('is the smallest box around its points, and nothing for none', () => {
    expect(boundsOf([])).toBeNull();
    expect(boundsOf([p(1, 5), p(3, 2), p(2, 4)])).toEqual({
      southWest: p(1, 2),
      northEast: p(3, 5),
    });
    expect(centreOf({ southWest: p(0, 0), northEast: p(2, 4) })).toEqual(p(1, 2));
  });

  it('names the two shapes the database refuses: out of range, and not oriented', () => {
    expect(boundsProblems(TASHKENT)).toEqual([]);
    expect(boundsProblems({ southWest: p(41.47, 69.04), northEast: p(41.15, 69.46) })).toEqual([
      'INVERTED',
    ]);
    expect(boundsProblems({ southWest: p(41.15, 69.04), northEast: p(41.15, 69.46) })).toEqual([
      'INVERTED',
    ]);
    expect(boundsProblems({ southWest: p(-91, 69.04), northEast: p(41.15, 69.46) })).toContain(
      'OUT_OF_RANGE',
    );
  });
});

describe('ringProblems', () => {
  it('opens a ring that repeats its first corner at the end', () => {
    expect(openRing([...SQUARE, SQUARE[0]])).toEqual(SQUARE);
    expect(openRing(SQUARE)).toEqual(SQUARE);
    expect(openRing([])).toEqual([]);
  });

  it('is happy with a plain polygon inside its region', () => {
    expect(ringProblems(SQUARE, TASHKENT)).toEqual([]);
    expect(ringProblems([...SQUARE, SQUARE[0]], TASHKENT)).toEqual([]);
  });

  it('needs three corners, and says only that when it has fewer', () => {
    expect(ringProblems([], null)).toEqual(['TOO_FEW']);
    expect(ringProblems([p(41.3, 69.2), p(41.3, 69.3)], TASHKENT)).toEqual(['TOO_FEW']);
  });

  it('notices two neighbours that are one point', () => {
    expect(ringProblems([SQUARE[0], SQUARE[1], SQUARE[1], SQUARE[2], SQUARE[3]], null)).toContain(
      'DUPLICATE',
    );
  });

  it('notices an outline that crosses itself, but not one that merely shares a corner with its neighbour', () => {
    const bowTie = [p(41.3, 69.2), p(41.4, 69.3), p(41.4, 69.2), p(41.3, 69.3)];
    expect(ringProblems(bowTie, null)).toEqual(['CROSSING']);
    // An L shape and a triangle both touch only at their own corners.
    expect(ringProblems([p(0, 0), p(0, 2), p(1, 2), p(1, 1), p(2, 1), p(2, 0)], null)).toEqual([]);
    expect(ringProblems([p(41.3, 69.2), p(41.3, 69.3), p(41.4, 69.25)], null)).toEqual([]);
  });

  it('notices a corner outside the region, which is the transposed-latitude check', () => {
    const swapped = SQUARE.map((corner) => p(corner.longitude, corner.latitude));
    expect(ringProblems(swapped, TASHKENT)).toEqual(['OUTSIDE_REGION']);
    expect(ringProblems(swapped, null)).toEqual([]);
  });
});
