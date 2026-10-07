import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  GeoAnswer,
  GeoLookupApi,
  GeoLookupContext,
  GeoResult,
  GeoSuggestion,
} from '../../../core/api/geo-lookup-api';
import { I18n } from '../../../core/i18n/i18n';
import { AddressPicker, PickedAddress } from './address-picker';
import { MapConfig, MapConfigService } from './map-config';
import { LatLng } from './map-provider';
import { NullMapProvider, provideNullMapProvider } from './null-map-provider';

const SCOPE = { tenantId: 't-1', brandId: 'b-1' };
const CENTRE: LatLng = { latitude: 41.31, longitude: 69.24 };
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

const SUGGESTION: GeoSuggestion = {
  title: 'Fixture ko‘chasi',
  subtitle: 'Ташкент, Узбекистан',
  fullText: 'Узбекистан, Ташкент, Fixture ko‘chasi, 12',
  providerReference: 'ref-1',
};

function result(overrides: Partial<GeoResult> = {}): GeoResult {
  return {
    latitude: 41.3111,
    longitude: 69.2797,
    components: {
      country: 'UZ',
      locality: 'Ташкент',
      district: null,
      street: 'Fixture ko‘chasi',
      house: '12',
      formatted: SUGGESTION.fullText,
    },
    providerReference: 'ref-1',
    confidence: 'HIGH',
    precision: 'HOUSE',
    resolvedAt: '2026-10-07T08:00:00Z',
    provider: 'FAKE',
    ...overrides,
  };
}

class FakeGeo {
  suggestions: GeoAnswer<readonly GeoSuggestion[]> = { status: 'ANSWERED', value: [SUGGESTION] };
  resolutions: GeoAnswer<readonly GeoResult[]> = { status: 'ANSWERED', value: [result()] };
  readonly suggested: {
    context: GeoLookupContext;
    text: string;
    near: LatLng | null | undefined;
  }[] = [];
  readonly resolved: { context: GeoLookupContext; text: string }[] = [];
  gate: Promise<void> | null = null;

  async suggest(context: GeoLookupContext, text: string, near?: LatLng | null) {
    this.suggested.push({ context, text, near });
    if (this.gate) {
      await this.gate;
    }
    return this.suggestions;
  }

  async resolve(context: GeoLookupContext, text: string) {
    this.resolved.push({ context, text });
    return this.resolutions;
  }
}

async function pass(fixture: ComponentFixture<unknown>, ms = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

function el(fixture: ComponentFixture<unknown>, testId: string): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    `[data-testid="${testId}"]`,
  );
}

function elements(fixture: ComponentFixture<unknown>, testId: string): HTMLElement[] {
  return [
    ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
      `[data-testid="${testId}"]`,
    ),
  ];
}

function typeInto(target: HTMLElement | null, value: string): void {
  const field = target as HTMLInputElement;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('AddressPicker (ADR 0145, row X.4)', () => {
  let provider: NullMapProvider;
  let geo: FakeGeo;
  let picked: PickedAddress[];
  const config = signal<MapConfig | null>(null);

  beforeEach(() => {
    vi.useFakeTimers();
    provider = new NullMapProvider();
    geo = new FakeGeo();
    picked = [];
    config.set(null);
    TestBed.configureTestingModule({
      providers: [
        provideNullMapProvider(provider),
        { provide: GeoLookupApi, useValue: geo },
        {
          provide: MapConfigService,
          useValue: { ensureLoaded: () => Promise.resolve(config()), config },
        },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<AddressPicker> {
    const fixture = TestBed.createComponent(AddressPicker);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.componentInstance.pickedChange.subscribe((p) => picked.push(p));
    fixture.detectChanges();
    return fixture;
  }

  async function search(fixture: ComponentFixture<unknown>, text: string): Promise<void> {
    typeInto(el(fixture, 'q-combobox-input'), text);
    await pass(fixture, 300);
  }

  async function choose(fixture: ComponentFixture<unknown>): Promise<void> {
    elements(fixture, 'q-combobox-option')[0].click();
    await pass(fixture);
  }

  it('searches once typing settles, for the person’s own region, branch and language, and lists what comes back', async () => {
    const fixture = create({ locationId: 'l-9', regionId: 'r-1' });
    await pass(fixture);
    typeInto(el(fixture, 'q-combobox-input'), 'fixt');
    await pass(fixture, 100);
    expect(geo.suggested).toHaveLength(0);

    await pass(fixture, 200);

    expect(geo.suggested).toHaveLength(1);
    expect(geo.suggested[0].text).toBe('fixt');
    expect(geo.suggested[0].context).toEqual(
      expect.objectContaining({ scope: SCOPE, locationId: 'l-9', regionId: 'r-1', locale: 'en' }),
    );
    expect(geo.suggested[0].near).toEqual(CENTRE);
    const options = elements(fixture, 'q-combobox-option');
    expect(options).toHaveLength(1);
    expect(options[0].textContent).toContain('Fixture ko‘chasi');
    expect(options[0].textContent).toContain('Ташкент, Узбекистан');
  });

  it('does not search for a single character', async () => {
    const fixture = create();
    await pass(fixture);

    await search(fixture, 'f');

    expect(geo.suggested).toHaveLength(0);
  });

  it('ignores a slow answer for text the person has since typed over', async () => {
    let release: () => void = () => undefined;
    geo.gate = new Promise<void>((resolve) => (release = resolve));
    const fixture = create();
    await pass(fixture);

    await search(fixture, 'fixt');
    geo.gate = null;
    geo.suggestions = { status: 'ANSWERED', value: [{ ...SUGGESTION, title: 'Newer answer' }] };
    await search(fixture, 'fixture');
    release();
    await pass(fixture);

    expect(elements(fixture, 'q-combobox-option').map((o) => o.textContent)).toEqual([
      expect.stringContaining('Newer answer'),
    ]);
  });

  describe('choosing a suggestion', () => {
    it('resolves it, shows the pin for the person to look at, fills the street and house, and emits nothing with a point yet', async () => {
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');

      await choose(fixture);

      expect(geo.resolved).toHaveLength(1);
      expect(geo.resolved[0].text).toBe(SUGGESTION.fullText);
      expect(provider.map.livePins[0].position).toEqual(at(41.3111, 69.2797));
      expect((el(fixture, 'q-address-street') as HTMLInputElement).value).toBe('Fixture ko‘chasi');
      expect((el(fixture, 'q-address-house') as HTMLInputElement).value).toBe('12');
      expect(el(fixture, 'q-address-confirm')).not.toBeNull();

      // A geocoder result is a proposal. Nothing leaves this component as a point until a person agrees.
      expect(picked.at(-1)).toEqual({
        point: null,
        coordinateSource: 'NOT_GEOCODED',
        components: expect.objectContaining({
          formatted: SUGGESTION.fullText,
          street: 'Fixture ko‘chasi',
          house: '12',
          locality: 'Ташкент',
        }),
      });
    });

    it('emits the point, as the operator’s pin, once they press "Use this point"', async () => {
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);

      el(fixture, 'q-address-confirm')!.click();
      await pass(fixture);

      expect(picked.at(-1)).toEqual(
        expect.objectContaining({ point: at(41.3111, 69.2797), coordinateSource: 'OPERATOR_PIN' }),
      );
      expect(el(fixture, 'q-address-confirm')).toBeNull();
    });

    it('says so when the pin belongs to a customer, and only ever to an operator or a customer', async () => {
      const fixture = create({ source: 'CUSTOMER_PIN' });
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);
      el(fixture, 'q-address-confirm')!.click();
      await pass(fixture);

      expect(picked.at(-1)?.coordinateSource).toBe('CUSTOMER_PIN');
      // ADR 0145 decision 5: what is stored is a pin a person confirmed, never a geocoder's answer.
      expect(picked.map((p) => p.coordinateSource)).not.toContain('GEOCODER');
    });

    it('counts dragging the pin as the person’s own decision', async () => {
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);

      provider.map.livePins[0].simulateDrag(at(41.312, 69.281));
      await pass(fixture);

      expect(picked.at(-1)).toEqual(
        expect.objectContaining({ point: at(41.312, 69.281), coordinateSource: 'OPERATOR_PIN' }),
      );
      expect(el(fixture, 'q-address-confirm')).toBeNull();
    });

    it('warns that the provider is unsure of a LOW_CONFIDENCE result, until the person has looked at the pin', async () => {
      geo.resolutions = {
        status: 'ANSWERED',
        value: [result({ confidence: 'LOW_CONFIDENCE', precision: 'STREET' })],
      };
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);

      expect(fixture.nativeElement.textContent).toContain('The provider is not sure of this one');

      el(fixture, 'q-address-confirm')!.click();
      await pass(fixture);
      expect(fixture.nativeElement.textContent).not.toContain(
        'The provider is not sure of this one',
      );
    });

    it('says the address could not be placed when the provider finds nothing, and keeps what was typed', async () => {
      geo.resolutions = { status: 'ANSWERED', value: [] };
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);

      expect(fixture.nativeElement.textContent).toContain('could not be placed on the map');
      expect(provider.map.livePins).toHaveLength(0);
      expect(picked.at(-1)?.point).toBeNull();
      expect(picked.at(-1)?.components.formatted).toBe(SUGGESTION.fullText);
    });

    it('never overwrites the address text because the pin moved', async () => {
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'fixt');
      await choose(fixture);
      const before = picked.at(-1)!.components.formatted;

      provider.map.livePins[0].simulateDrag(at(41.4, 69.4));
      await pass(fixture);

      expect(picked.at(-1)!.components.formatted).toBe(before);
    });
  });

  describe('when address search does not work', () => {
    it('says it is not set up, and lets the address be used as typed with no point', async () => {
      geo.suggestions = { status: 'UNAVAILABLE', reason: 'NOT_CONFIGURED' };
      const fixture = create();
      await pass(fixture);

      await search(fixture, 'Zaglushka ko‘chasi 7');

      expect(fixture.nativeElement.textContent).toContain(
        'Address search is not set up in this environment',
      );
      expect(elements(fixture, 'q-combobox-no-results')).toHaveLength(0);
      el(fixture, 'q-combobox-create-row')!.click();
      await pass(fixture);

      expect(picked.at(-1)).toEqual({
        point: null,
        coordinateSource: 'NOT_GEOCODED',
        components: expect.objectContaining({ formatted: 'Zaglushka ko‘chasi 7' }),
      });
    });

    it.each([
      ['PROVIDER_REFUSED', 'refusing requests'],
      ['PROVIDER_UNAVAILABLE', 'not answering right now'],
      ['RATE_LIMITED', 'Too many searches'],
      ['NO_REGION', 'No region is registered'],
      ['REQUEST_FAILED', 'did not get through'],
    ] as const)('says %s in words a person can act on', async (reason, phrase) => {
      geo.suggestions = { status: 'UNAVAILABLE', reason };
      const fixture = create();
      await pass(fixture);

      await search(fixture, 'fixt');

      expect(fixture.nativeElement.textContent).toContain(phrase);
    });

    it('still takes the pin by hand when the map is up and the search is down', async () => {
      geo.suggestions = { status: 'UNAVAILABLE', reason: 'PROVIDER_UNAVAILABLE' };
      const fixture = create();
      await pass(fixture);
      await search(fixture, 'Zaglushka');

      provider.map.simulateClick(at(41.33, 69.27));
      await pass(fixture);

      expect(picked.at(-1)).toEqual(
        expect.objectContaining({ point: at(41.33, 69.27), coordinateSource: 'OPERATOR_PIN' }),
      );
    });
  });

  it('carries entrance, floor, flat and landmark, which no provider knows, and leaves blanks as null', async () => {
    const fixture = create();
    await pass(fixture);

    typeInto(el(fixture, 'q-address-entrance'), ' 2 ');
    typeInto(el(fixture, 'q-address-floor'), '5');
    typeInto(el(fixture, 'q-address-flat'), '   ');
    typeInto(el(fixture, 'q-address-landmark'), 'Opposite the pharmacy');

    expect(picked.at(-1)!.components).toEqual(
      expect.objectContaining({
        entrance: '2',
        floor: '5',
        flat: null,
        landmark: 'Opposite the pharmacy',
      }),
    );
  });

  it('starts from a saved address: its text, its parts and its pin', async () => {
    const saved: PickedAddress = {
      point: at(41.3, 69.2),
      coordinateSource: 'OPERATOR_PIN',
      components: {
        formatted: 'Saved address',
        street: 'Saved ko‘cha',
        house: '3',
        locality: 'Ташкент',
        entrance: '1',
        floor: null,
        flat: '9',
        landmark: 'Gate',
      },
    };
    const fixture = create({ initial: saved });
    await pass(fixture);

    expect((el(fixture, 'q-address-street') as HTMLInputElement).value).toBe('Saved ko‘cha');
    expect((el(fixture, 'q-address-flat') as HTMLInputElement).value).toBe('9');
    expect(provider.map.livePins[0].position).toEqual(at(41.3, 69.2));
    expect(el(fixture, 'q-address-confirm')).toBeNull();

    typeInto(el(fixture, 'q-address-landmark'), 'Blue gate');
    expect(picked.at(-1)).toEqual(
      expect.objectContaining({ point: at(41.3, 69.2), coordinateSource: 'OPERATOR_PIN' }),
    );
  });

  it('shows the provider’s own attribution beside address search, when there is one', async () => {
    config.set({
      provider: 'YANDEX',
      configured: true,
      browserKey: 'k',
      features: ['TILES', 'SUGGEST'],
      attribution: '© Яндекс',
    });
    const fixture = create();
    await pass(fixture);

    expect(el(fixture, 'q-address-attribution')?.textContent).toContain('© Яндекс');
  });

  it('never emits a GEOCODER source from any path through it', async () => {
    const fixture = create();
    await pass(fixture);
    await search(fixture, 'fixt');
    await choose(fixture);
    el(fixture, 'q-address-confirm')!.click();
    provider.map.livePins[0].simulateDrag(at(41.4, 69.4));
    typeInto(el(fixture, 'q-address-landmark'), 'x');
    await pass(fixture);

    expect(picked.length).toBeGreaterThan(3);
    for (const emission of picked) {
      expect(['OPERATOR_PIN', 'CUSTOMER_PIN', 'NOT_GEOCODED']).toContain(emission.coordinateSource);
      expect(emission.coordinateSource === 'NOT_GEOCODED').toBe(emission.point === null);
    }
  });
});
