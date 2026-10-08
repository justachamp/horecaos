import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { MapRegionService } from './map-region';
import { RegionResponse, RegionsApi } from './regions-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function region(overrides: Partial<RegionResponse>): RegionResponse {
  return {
    regionId: 'r1',
    platform: true,
    code: 'TASHKENT',
    displayNameRu: 'Ташкент',
    displayNameUz: 'Toshkent',
    displayNameEn: 'Tashkent',
    centreLat: 41.3,
    centreLon: 69.25,
    bboxSwLat: 41.15,
    bboxSwLon: 69.04,
    bboxNeLat: 41.47,
    bboxNeLon: 69.46,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

function service(list: () => Promise<readonly RegionResponse[]>, scope: BrandScope | null = SCOPE) {
  const api = { list: vi.fn(list) };
  TestBed.configureTestingModule({
    providers: [
      { provide: RegionsApi, useValue: api },
      {
        provide: CurrentBrand,
        useValue: { scope: signal(scope), ensureLoaded: () => Promise.resolve() },
      },
    ],
  });
  return { regions: TestBed.inject(MapRegionService), api };
}

describe('MapRegionService (ADR 0145 decision 7)', () => {
  it('reads the active regions once, whoever asks and however many ask at once', async () => {
    const { regions, api } = service(() => Promise.resolve([region({})]));

    await Promise.all([regions.ensureLoaded(), regions.ensureLoaded()]);
    await regions.ensureLoaded();

    expect(api.list).toHaveBeenCalledTimes(1);
    expect(api.list).toHaveBeenCalledWith('t1');
    expect(regions.state()).toBe('ready');
    expect(regions.primary()?.bounds.southWest).toEqual({ latitude: 41.15, longitude: 69.04 });
    expect(regions.primary()?.centre).toEqual({ latitude: 41.3, longitude: 69.25 });
  });

  it('prefers the tenant’s own region over the platform’s, and ignores an archived one', async () => {
    const { regions } = service(() =>
      Promise.resolve([
        region({ regionId: 'platform', platform: true, code: 'TASHKENT' }),
        region({ regionId: 'own', platform: false, code: 'SAMARKAND' }),
        region({ regionId: 'old', platform: false, code: 'AAA', status: 'ARCHIVED' }),
      ]),
    );

    await regions.ensureLoaded();

    expect(regions.regions().map((r) => r.regionId)).toEqual(['own', 'platform']);
    expect(regions.primary()?.regionId).toBe('own');
    expect(regions.regionFor('platform')?.regionId).toBe('platform');
    expect(regions.regionFor('missing')?.regionId).toBe('own');
    expect(regions.regionFor(null)?.regionId).toBe('own');
  });

  it('opens on the middle of the box when a region has no centre of its own', async () => {
    const { regions } = service(() =>
      Promise.resolve([region({ centreLat: Number.NaN, centreLon: Number.NaN })]),
    );

    await regions.ensureLoaded();

    expect(regions.primary()?.centre.latitude).toBeCloseTo(41.31, 5);
    expect(regions.primary()?.centre.longitude).toBeCloseTo(69.25, 5);
  });

  it('is denied, not broken, for staff who may not read regions, and does not ask again', async () => {
    const { regions, api } = service(() =>
      Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    );

    await regions.ensureLoaded();
    await regions.ensureLoaded();

    expect(regions.state()).toBe('denied');
    expect(regions.primary()).toBeNull();
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it('does not remember a failed read: the next screen tries again', async () => {
    let fail = true;
    const { regions, api } = service(() =>
      fail
        ? Promise.reject(new ApiError('INTERNAL_ERROR', 500, null, null))
        : Promise.resolve([region({})]),
    );

    await regions.ensureLoaded();
    expect(regions.state()).toBe('failed');

    fail = false;
    await regions.ensureLoaded();

    expect(regions.state()).toBe('ready');
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('is failed when there is no brand to read regions for', async () => {
    const { regions, api } = service(() => Promise.resolve([]), null);

    await regions.ensureLoaded();

    expect(regions.state()).toBe('failed');
    expect(api.list).not.toHaveBeenCalled();
  });
});
