import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { MapConfig, MapConfigService } from './map-config';

const CONFIG: MapConfig = {
  provider: 'YANDEX',
  configured: true,
  browserKey: 'public-key',
  features: ['TILES', 'SUGGEST', 'GEOCODE', 'REVERSE'],
  attribution: '© Яндекс',
};

function setup(
  options: { scope?: { tenantId: string; brandId: string } | null; get?: () => unknown } = {},
) {
  const get = vi.fn(options.get ?? (() => of({ value: CONFIG, version: null })));
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: { get } },
      {
        provide: CurrentBrand,
        useValue: {
          ensureLoaded: () => Promise.resolve(),
          scope: signal(
            options.scope === undefined ? { tenantId: 't-1', brandId: 'b-1' } : options.scope,
          ),
        },
      },
    ],
  });
  return { service: TestBed.inject(MapConfigService), get };
}

describe('MapConfigService', () => {
  it('knows nothing, and so offers nothing, until it has read the configuration', () => {
    const { service } = setup();

    expect(service.state()).toEqual({ status: 'idle' });
    expect(service.config()).toBeNull();
    expect(service.has('TILES')).toBe(false);
  });

  it('reads the brand’s configuration once, however many screens ask at the same time', async () => {
    const { service, get } = setup();

    const [first, second] = await Promise.all([service.ensureLoaded(), service.ensureLoaded()]);
    await service.ensureLoaded();

    expect(first).toBe(CONFIG);
    expect(second).toBe(CONFIG);
    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/v1/operations/tenants/t-1/brands/b-1/map-config');
    expect(service.has('TILES')).toBe(true);
    expect(service.has('SUGGEST')).toBe(true);
  });

  it('does not remember a failure, so a screen opened after the platform came back gets a map', async () => {
    let calls = 0;
    const { service } = setup({
      get: () =>
        ++calls === 1 ? throwError(() => new Error('down')) : of({ value: CONFIG, version: null }),
    });

    expect(await service.ensureLoaded()).toBeNull();
    expect(service.state()).toEqual({ status: 'failed' });

    expect(await service.ensureLoaded()).toBe(CONFIG);
    expect(service.state()).toEqual({ status: 'ready', config: CONFIG });
  });

  it('fails, rather than guessing a brand, when the operator has none', async () => {
    const { service, get } = setup({ scope: null });

    expect(await service.ensureLoaded()).toBeNull();
    expect(get).not.toHaveBeenCalled();
    expect(service.state()).toEqual({ status: 'failed' });
  });
});
