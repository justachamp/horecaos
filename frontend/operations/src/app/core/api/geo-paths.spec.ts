import { describe, expect, it } from 'vitest';

import { geoPaths } from './geo-paths';

const SCOPE = { tenantId: 't-1', brandId: 'b-1' };

describe('geoPaths (ADR 0145)', () => {
  it('builds the map configuration under the brand', () => {
    expect(geoPaths.mapConfig(SCOPE)).toBe('/api/v1/operations/tenants/t-1/brands/b-1/map-config');
  });

  it('builds the three lookups under the brand when no branch is named', () => {
    expect(geoPaths.lookup(SCOPE, 'suggestions')).toBe(
      '/api/v1/operations/tenants/t-1/brands/b-1/geocode/suggestions',
    );
    expect(geoPaths.lookup(SCOPE, 'resolutions')).toBe(
      '/api/v1/operations/tenants/t-1/brands/b-1/geocode/resolutions',
    );
    expect(geoPaths.lookup(SCOPE, 'reverse-resolutions')).toBe(
      '/api/v1/operations/tenants/t-1/brands/b-1/geocode/reverse-resolutions',
    );
  });

  it('builds them under the branch when one is named, which is the path a branch grant covers', () => {
    expect(geoPaths.lookup(SCOPE, 'suggestions', 'l-9')).toBe(
      '/api/v1/operations/tenants/t-1/brands/b-1/locations/l-9/geocode/suggestions',
    );
    expect(geoPaths.lookup(SCOPE, 'suggestions', null)).toBe(
      '/api/v1/operations/tenants/t-1/brands/b-1/geocode/suggestions',
    );
  });

  it('encodes an identifier rather than trusting it', () => {
    expect(geoPaths.mapConfig({ tenantId: 'a/b', brandId: 'c d' })).toBe(
      '/api/v1/operations/tenants/a%2Fb/brands/c%20d/map-config',
    );
    expect(geoPaths.lookup(SCOPE, 'resolutions', '../x')).toContain('/locations/..%2Fx/');
  });
});
