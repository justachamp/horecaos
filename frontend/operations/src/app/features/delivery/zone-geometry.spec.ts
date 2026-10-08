import { describe, expect, it } from 'vitest';

import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { ZoneOutlineResponse } from './delivery-zones-api';
import {
  editableRing,
  outerRings,
  parseGeoJson,
  regionVerdict,
  toGeoJsonPolygon,
} from './zone-geometry';

const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

const TASHKENT: MapBounds = {
  southWest: at(41.15, 69.04),
  northEast: at(41.47, 69.46),
};

function outline(polygons: ZoneOutlineResponse['polygons']): ZoneOutlineResponse {
  return {
    zoneId: 'z',
    code: 'Z1',
    role: 'DELIVERY',
    version: 1,
    status: 'DRAFT',
    shapeKind: 'POLYGON',
    polygons,
  };
}

describe('zone geometry (rows 3.6, 3.6c; ADR 0037, ADR 0145)', () => {
  describe('toGeoJsonPolygon', () => {
    it('writes [longitude, latitude] and closes the ring, which is what the server takes', () => {
      const geoJson = JSON.parse(
        toGeoJsonPolygon([at(41.28, 69.2), at(41.28, 69.28), at(41.34, 69.24)]),
      ) as { type: string; coordinates: number[][][] };

      expect(geoJson.type).toBe('Polygon');
      expect(geoJson.coordinates[0]).toEqual([
        [69.2, 41.28],
        [69.28, 41.28],
        [69.24, 41.34],
        [69.2, 41.28],
      ]);
    });

    it('refuses fewer than three corners rather than sending a line the server must refuse', () => {
      expect(() => toGeoJsonPolygon([at(41.28, 69.2), at(41.28, 69.28)])).toThrow();
    });

    it('reads back as the same open ring: the two directions agree', () => {
      const ring = [at(41.28, 69.2), at(41.28, 69.28), at(41.34, 69.24)];

      const parsed = parseGeoJson(toGeoJsonPolygon(ring));

      expect(parsed?.rings).toEqual([ring]);
    });
  });

  describe('parseGeoJson', () => {
    it('reads a Polygon, a MultiPolygon, a Feature and a FeatureCollection, longitude first', () => {
      const square = [
        [69.2, 41.28],
        [69.28, 41.28],
        [69.28, 41.34],
        [69.2, 41.34],
        [69.2, 41.28],
      ];
      const polygon = { type: 'Polygon', coordinates: [square] };

      for (const document of [
        polygon,
        { type: 'MultiPolygon', coordinates: [[square]] },
        { type: 'Feature', geometry: polygon, properties: {} },
        { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: polygon }] },
      ]) {
        const parsed = parseGeoJson(JSON.stringify(document));
        expect(parsed?.rings).toHaveLength(1);
        expect(parsed?.rings[0][0]).toEqual(at(41.28, 69.2));
        expect(parsed?.rings[0]).toHaveLength(4);
        expect(parsed?.simplified).toBe(false);
      }
    });

    it('says when it drew only the outer ring of a shape that has holes', () => {
      const outer = [
        [69.2, 41.28],
        [69.28, 41.28],
        [69.24, 41.34],
        [69.2, 41.28],
      ];
      const hole = [
        [69.22, 41.29],
        [69.24, 41.29],
        [69.23, 41.3],
        [69.22, 41.29],
      ];

      const parsed = parseGeoJson(JSON.stringify({ type: 'Polygon', coordinates: [outer, hole] }));

      expect(parsed?.rings).toHaveLength(1);
      expect(parsed?.simplified).toBe(true);
    });

    it('refuses what it cannot read whole, instead of drawing half of it', () => {
      expect(parseGeoJson('not json')).toBeNull();
      expect(parseGeoJson(JSON.stringify({ type: 'Point', coordinates: [69.2, 41.3] }))).toBeNull();
      expect(
        parseGeoJson(
          JSON.stringify({
            type: 'Polygon',
            coordinates: [
              [
                [69.2, 'x'],
                [1, 2],
                [3, 4],
              ],
            ],
          }),
        ),
      ).toBeNull();
      expect(parseGeoJson(JSON.stringify({ type: 'FeatureCollection', features: [] }))).toBeNull();
      expect(
        parseGeoJson(
          JSON.stringify({
            type: 'FeatureCollection',
            features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [1, 2] } }],
          }),
        ),
      ).toBeNull();
    });
  });

  describe('stored outlines', () => {
    const ring = [
      { latitude: 41.28, longitude: 69.2 },
      { latitude: 41.28, longitude: 69.28 },
      { latitude: 41.34, longitude: 69.24 },
    ];

    it('opens a single polygon without holes in the editor', () => {
      expect(editableRing(outline([{ ring, holes: [] }]))).toEqual(ring);
    });

    it('refuses to open in the editor what the editor would silently flatten', () => {
      expect(editableRing(outline([{ ring, holes: [ring] }]))).toBeNull();
      expect(
        editableRing(
          outline([
            { ring, holes: [] },
            { ring, holes: [] },
          ]),
        ),
      ).toBeNull();
    });

    it('draws every polygon of a stored outline', () => {
      expect(
        outerRings(
          outline([
            { ring, holes: [] },
            { ring, holes: [] },
          ]),
        ),
      ).toHaveLength(2);
    });
  });

  describe('regionVerdict', () => {
    const inside = [at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.25)];

    it('knows an outline that sits in its region', () => {
      expect(regionVerdict(inside, TASHKENT)).toBe('INSIDE');
    });

    it('says outside for an outline that is simply elsewhere', () => {
      expect(regionVerdict([at(39.6, 66.9), at(39.7, 66.9), at(39.65, 67)], TASHKENT)).toBe(
        'OUTSIDE',
      );
    });

    it('diagnoses swapped coordinates: outside as read, inside once latitude and longitude change places', () => {
      const swapped = inside.map((corner) => at(corner.longitude, corner.latitude));

      expect(regionVerdict(swapped, TASHKENT)).toBe('LIKELY_SWAPPED');
    });

    it('does not guess without a region', () => {
      expect(regionVerdict(inside, null)).toBe('NO_REGION');
    });
  });
});
