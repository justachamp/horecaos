import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { LazyMapProvider } from './lazy-map-provider';
import { MapConfig, MapConfigService } from './map-config';
import { MapUnavailableError } from './map-provider';

const FULL: MapConfig = {
  provider: 'YANDEX',
  configured: true,
  browserKey: 'public-key',
  features: ['TILES', 'SUGGEST', 'GEOCODE', 'REVERSE'],
  attribution: null,
};

function setup(read: () => Promise<MapConfig | null>) {
  const config = signal<MapConfig | null>(null);
  let reads = 0;
  TestBed.configureTestingModule({
    providers: [
      {
        provide: MapConfigService,
        useValue: {
          config,
          ensureLoaded: () => {
            reads += 1;
            return read().then((value) => {
              config.set(value);
              return value;
            });
          },
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { provider: TestBed.inject(LazyMapProvider), reads: () => reads };
}

async function reasonOf(provider: LazyMapProvider): Promise<string> {
  try {
    await provider.load();
  } catch (failure) {
    expect(failure).toBeInstanceOf(MapUnavailableError);
    return (failure as MapUnavailableError).reason;
  }
  throw new Error('load() was expected to fail');
}

describe('LazyMapProvider (ADR 0145: nothing vendor-shaped until a map is asked for)', () => {
  afterEach(() => delete (window as unknown as { ymaps?: unknown }).ymaps);

  it('says "not configured" when the platform says nothing is set up, and fetches no vendor', async () => {
    const { provider } = setup(() =>
      Promise.resolve({
        provider: 'NONE',
        configured: false,
        browserKey: null,
        features: [],
        attribution: null,
      }),
    );

    expect(await reasonOf(provider)).toBe('NOT_CONFIGURED');
    expect(document.head.querySelector('script[src^="https://api-maps.yandex.ru"]')).toBeNull();
  });

  it('says "not configured" when the configuration could not be read at all', async () => {
    const { provider } = setup(() => Promise.resolve(null));

    expect(await reasonOf(provider)).toBe('NOT_CONFIGURED');
  });

  it('says "no tiles" for a provider that can search addresses but has no map to draw', async () => {
    const fake = setup(() =>
      Promise.resolve({
        provider: 'FAKE',
        configured: true,
        browserKey: null,
        features: ['SUGGEST', 'GEOCODE', 'REVERSE'],
        attribution: null,
      }),
    );

    expect(await reasonOf(fake.provider)).toBe('NO_TILES');
  });

  it('says "no tiles" for Yandex without a browser key, rather than loading a script that cannot work', async () => {
    const { provider } = setup(() => Promise.resolve({ ...FULL, browserKey: null }));

    expect(await reasonOf(provider)).toBe('NO_TILES');
    expect(document.head.querySelector('script[src^="https://api-maps.yandex.ru"]')).toBeNull();
  });

  it('does not pretend to draw a provider this build has no adapter for', async () => {
    const { provider } = setup(() => Promise.resolve({ ...FULL, provider: '2GIS' }));

    expect(await reasonOf(provider)).toBe('NOT_CONFIGURED');
  });

  it('forgets a failure, so the next screen to open a map asks again', async () => {
    let answer: MapConfig | null = null;
    const { provider, reads } = setup(() => Promise.resolve(answer));

    expect(await reasonOf(provider)).toBe('NOT_CONFIGURED');
    answer = {
      provider: 'NONE',
      configured: false,
      browserKey: null,
      features: [],
      attribution: null,
    };
    expect(await reasonOf(provider)).toBe('NOT_CONFIGURED');

    expect(reads()).toBe(2);
  });

  it('imports the Yandex adapter on first use and builds maps through it', async () => {
    const created: { center: unknown; zoom: unknown }[] = [];
    // The vendor's script is replaced by a global, which the adapter's own loader finds and uses.
    (window as unknown as { ymaps: unknown }).ymaps = {
      ready: (callback: () => void) => callback(),
      Map: class {
        geoObjects = { add: () => undefined, remove: () => undefined };
        events = { add: () => undefined, remove: () => undefined };
        constructor(_el: HTMLElement, state: { center: unknown; zoom: unknown }) {
          created.push(state);
        }
      },
    };
    const { provider } = setup(() => Promise.resolve(FULL));

    expect(() =>
      provider.createMap(document.createElement('div'), {
        center: { latitude: 1, longitude: 2 },
        zoom: 3,
      }),
    ).toThrow('before load');
    await provider.load();
    provider.createMap(document.createElement('div'), {
      center: { latitude: 41.3, longitude: 69.2 },
      zoom: 12,
    });

    expect(provider.code).toBe('YANDEX');
    expect(created).toEqual([expect.objectContaining({ center: [41.3, 69.2], zoom: 12 })]);
  });
});
