import { Component, Directive, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { YaMapComponent, YaPlacemarkDirective } from 'angular8-yandex-maps';

import { LocationsAddComponent } from './locations-add.component';
import {
  GeocodingService,
  type AddressSuggestion,
  type GeocodedPlace,
  type Lookup,
} from '../../../services/geocoding.service';
import { MapConfigService, NO_MAP, type MapConfig } from '../../../services/map-config.service';
import { NavigationHistoryService } from '../../../services/navigation-history.service';
import { TranslateService } from '../../../services/translate.service';

/** The real map component loads the vendor's script; a spec draws a box with the same selector instead. */
@Component({ selector: 'ya-map', standalone: true, template: '<ng-content />' })
class FakeYaMap {
  readonly center = input<unknown>();
  readonly zoom = input<unknown>();
  readonly state = input<unknown>();
}

@Directive({ selector: 'ya-placemark', standalone: true })
class FakeYaPlacemark {
  readonly geometry = input<unknown>();
  readonly options = input<unknown>();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

const TILES: MapConfig = {
  provider: 'YANDEX',
  configured: true,
  browserKey: 'a-browser-key',
  features: ['TILES', 'SUGGEST', 'GEOCODE', 'REVERSE'],
  attribution: null,
};
const LOOKUPS_ONLY: MapConfig = {
  ...TILES,
  browserKey: null,
  features: ['SUGGEST', 'GEOCODE', 'REVERSE'],
};

const SUGGESTION: AddressSuggestion = {
  title: 'Amir Temur',
  subtitle: 'Tashkent',
  text: 'Tashkent, Amir Temur 15',
};
const PLACE: GeocodedPlace = {
  address: 'Tashkent, Amir Temur ko‘chasi 15',
  lat: 41.3111,
  lng: 69.2797,
  lowConfidence: false,
};

const lookup = <T>(items: readonly T[], available = true): Lookup<T> => ({ available, items });
const tick = (ms = 0) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function stubGeolocation(latitude = 41.2995, longitude = 69.2401): void {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (ok: (position: unknown) => void) =>
        ok({ coords: { latitude, longitude } }),
    },
  });
}

function removeGeolocation(): void {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined });
}

async function setUp(
  config: MapConfig,
  geocoding: Partial<Record<keyof GeocodingService, unknown>> = {},
) {
  const geo = {
    suggest: vi.fn().mockResolvedValue(lookup([SUGGESTION])),
    resolve: vi.fn().mockResolvedValue(lookup([PLACE])),
    describe: vi.fn().mockResolvedValue('Navoi ko‘chasi 28'),
    ...geocoding,
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: GeocodingService, useValue: geo },
      { provide: MapConfigService, useValue: { ensureLoaded: () => Promise.resolve(config) } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: NavigationHistoryService, useValue: { back: vi.fn() } },
    ],
  });
  TestBed.overrideComponent(LocationsAddComponent, {
    remove: { imports: [YaMapComponent, YaPlacemarkDirective] },
    add: { imports: [FakeYaMap, FakeYaPlacemark] },
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(LocationsAddComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await tick();
  fixture.detectChanges();
  return { fixture, geo, navigate, host: fixture.nativeElement as HTMLElement };
}

function type(host: HTMLElement, value: string): void {
  const input = host.querySelector<HTMLInputElement>('[data-testid="locations-add-search"]')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

const stored = () => JSON.parse(sessionStorage.getItem('new-location') ?? 'null');

describe('LocationsAddComponent (ADR 0145 decision 6: a map when there is one, a typed address when there is not)', () => {
  beforeEach(() => sessionStorage.removeItem('new-location'));
  afterEach(() => {
    sessionStorage.removeItem('new-location');
    removeGeolocation();
  });

  describe('with no provider set up', () => {
    it('is a text field, says why, draws no map and never asks to know where the customer is', async () => {
      const asked = vi.fn();
      Object.defineProperty(navigator, 'geolocation', {
        configurable: true,
        value: { getCurrentPosition: asked },
      });

      const { host, geo } = await setUp(NO_MAP);

      expect(host.querySelector('ya-map')).toBeNull();
      expect(host.querySelector('[data-testid="locations-add-no-map"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="locations-add-search"]')).not.toBeNull();
      expect(asked).not.toHaveBeenCalled();
      expect(geo.describe).not.toHaveBeenCalled();
    });

    it('asks nothing of the platform while the customer types, because the platform has said it has no lookups', async () => {
      const { host, geo } = await setUp(NO_MAP);

      type(host, 'Amir Temur 15');
      await tick(400);

      expect(geo.suggest).not.toHaveBeenCalled();
    });

    it('will not continue on a few letters, and then saves what was typed with no point at all', async () => {
      const { fixture, host, navigate } = await setUp(NO_MAP);
      const next = () =>
        host.querySelector<HTMLButtonElement>('[data-testid="locations-add-continue"]')!;

      type(host, 'Am');
      fixture.detectChanges();
      expect(next().disabled).toBe(true);

      type(host, '  Amir Temur 15, kv. 4  ');
      fixture.detectChanges();
      expect(next().disabled).toBe(false);
      next().click();

      expect(stored()).toEqual({ lat: null, lng: null, address: 'Amir Temur 15, kv. 4' });
      expect(navigate).toHaveBeenCalledWith(['/locations/save'], {
        state: { address: 'Amir Temur 15, kv. 4' },
      });
    });
  });

  describe('with lookups but no tiles', () => {
    it('suggests while the customer types, and a chosen line is the text, not a pin nobody saw', async () => {
      const { fixture, host, geo, navigate } = await setUp(LOOKUPS_ONLY);

      type(host, 'Amir');
      await tick(400);
      fixture.detectChanges();
      expect(geo.suggest).toHaveBeenCalledWith('Amir');
      host.querySelector<HTMLButtonElement>('[data-testid="locations-add-suggestion"]')!.click();
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="locations-add-continue"]')!.click();

      expect(geo.resolve).not.toHaveBeenCalled();
      expect(stored()).toEqual({ lat: null, lng: null, address: SUGGESTION.text });
      expect(navigate).toHaveBeenCalled();
    });
  });

  describe('with tiles', () => {
    it('draws the map and names the address under the customer’s position through the platform', async () => {
      stubGeolocation(41.2995, 69.2401);

      const { host, geo } = await setUp(TILES);

      expect(host.querySelector('ya-map')).not.toBeNull();
      expect(host.querySelector('[data-testid="locations-add-no-map"]')).toBeNull();
      expect(geo.describe).toHaveBeenCalledWith(41.2995, 69.2401);
    });

    it('resolves a chosen suggestion to a point on the map, and saves that pin as the customer’s', async () => {
      stubGeolocation();
      const { fixture, host, geo } = await setUp(TILES);

      type(host, 'Amir');
      await tick(400);
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('[data-testid="locations-add-suggestion"]')!.click();
      await tick();
      fixture.detectChanges();
      host.querySelector<HTMLButtonElement>('button.mt-8')!.click();

      expect(geo.resolve).toHaveBeenCalledWith(SUGGESTION.text);
      expect(stored()).toEqual({ lat: PLACE.lat, lng: PLACE.lng, address: PLACE.address });
    });

    it('keeps the pin and the typed address when the type-ahead cannot answer, and says the field is plain text', async () => {
      stubGeolocation(41.3, 69.24);
      const { fixture, host } = await setUp(TILES, {
        suggest: vi.fn().mockResolvedValue(lookup([], false)),
      });

      type(host, 'Amir Temur 15, kv. 4');
      await tick(400);
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="locations-add-search-unavailable"]')).not.toBeNull();
      host.querySelector<HTMLButtonElement>('button.mt-8')!.click();

      expect(stored()).toEqual({ lat: 41.3, lng: 69.24, address: 'Amir Temur 15, kv. 4' });
    });

    it('does not mistake a missing map-config for a map: an unreachable platform is the text field', async () => {
      stubGeolocation();

      const { host } = await setUp(NO_MAP);

      expect(host.querySelector('ya-map')).toBeNull();
      expect(host.querySelector('[data-testid="locations-add-no-map"]')).not.toBeNull();
    });
  });
});
