import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  GeoAnswer,
  GeoLookupApi,
  GeoLookupContext,
  GeoResult,
  GeoSuggestion,
} from '../../core/api/geo-lookup-api';
import { LocationScope } from '../../core/api/operations-paths';
import { I18n } from '../../core/i18n/i18n';
import { MapConfig, MapConfigService } from '../../shared/ui/map/map-config';
import { LatLng } from '../../shared/ui/map/map-provider';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { MapRegionService } from '../delivery/map-region';
import { CustomerAddressDraft, CustomerAddressEditor } from './customer-address-editor';
import { RevealedCustomerAddress } from './customers-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

const SUGGESTION: GeoSuggestion = {
  title: 'Amir Temur ko‘chasi',
  subtitle: 'Ташкент',
  fullText: 'Ташкент, Amir Temur ko‘chasi, 15',
  providerReference: 'ref-1',
};

function resolved(overrides: Partial<GeoResult['components']> = {}): GeoResult {
  return {
    latitude: 41.3111,
    longitude: 69.2797,
    components: {
      country: 'UZ',
      locality: 'Ташкент',
      district: 'Юнусабад',
      street: 'Amir Temur ko‘chasi',
      house: '15',
      formatted: SUGGESTION.fullText,
      ...overrides,
    },
    providerReference: 'ref-1',
    confidence: 'HIGH',
    precision: 'HOUSE',
    resolvedAt: '2026-10-07T08:00:00Z',
    provider: 'FAKE',
  };
}

class FakeGeo {
  suggestions: GeoAnswer<readonly GeoSuggestion[]> = { status: 'ANSWERED', value: [SUGGESTION] };
  resolutions: GeoAnswer<readonly GeoResult[]> = { status: 'ANSWERED', value: [resolved()] };
  readonly contexts: GeoLookupContext[] = [];
  async suggest(context: GeoLookupContext) {
    this.contexts.push(context);
    return this.suggestions;
  }
  async resolve() {
    return this.resolutions;
  }
}

function saved(overrides: Partial<RevealedCustomerAddress> = {}): RevealedCustomerAddress {
  return {
    id: 'addr-1',
    label: 'Home',
    fields: {
      line1: 'Bunyodkor 12',
      line2: 'Block B',
      city: 'Ташкент',
      district: 'Чиланзар',
      postalCode: '100000',
      entrance: '2',
      floor: '5',
      apartment: '41',
      landmark: 'blue gate',
    },
    deliveryInstructions: null,
    latitude: 41.2,
    longitude: 69.1,
    coordinateSource: 'CUSTOMER_PIN',
    version: 3,
    ...overrides,
  };
}

async function pass(fixture: ComponentFixture<unknown>, ms = 0): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms);
  fixture.detectChanges();
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
}

function el(fixture: ComponentFixture<unknown>, id: string): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);
}

function type(target: HTMLElement | null, value: string): void {
  const field = target as HTMLInputElement;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('CustomerAddressEditor (rows 1.3b, 5.2c; ADR 0145)', () => {
  let provider: NullMapProvider;
  let geo: FakeGeo;
  let drafts: CustomerAddressDraft[];
  const config = signal<MapConfig | null>(null);
  const primary = signal<ReturnType<typeof region> | null>(null);

  function region() {
    return {
      regionId: 'r-tashkent',
      code: 'TASHKENT',
      platform: true,
      bounds: { southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) },
      centre: at(41.3, 69.25),
    };
  }

  beforeEach(() => {
    vi.useFakeTimers();
    provider = new NullMapProvider();
    geo = new FakeGeo();
    drafts = [];
    config.set(null);
    primary.set(region());
    TestBed.configureTestingModule({
      providers: [
        provideNullMapProvider(provider),
        { provide: GeoLookupApi, useValue: geo },
        {
          provide: MapConfigService,
          useValue: { ensureLoaded: () => Promise.resolve(config()), config },
        },
        { provide: MapRegionService, useValue: { ensureLoaded: () => Promise.resolve(), primary } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<CustomerAddressEditor> {
    const fixture = TestBed.createComponent(CustomerAddressEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.componentInstance.draftChange.subscribe((draft) => drafts.push(draft));
    fixture.detectChanges();
    return fixture;
  }

  const last = (): CustomerAddressDraft => drafts.at(-1)!;

  async function chooseSuggestion(fixture: ComponentFixture<unknown>): Promise<void> {
    type(el(fixture, 'q-combobox-input'), 'amir');
    await pass(fixture, 300);
    (fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLElement>('[data-testid="q-combobox-option"]')[0]
      .click();
    await pass(fixture);
  }

  it('starts a new address empty, unsaveable, with no point and no claim to one', async () => {
    const fixture = create();
    await pass(fixture);

    expect(last().valid).toBe(false);
    expect(last().coordinateSource).toBe('NOT_GEOCODED');
    expect(last().latitude).toBeNull();
    expect(el(fixture, 'address-editor-incomplete')).not.toBeNull();
  });

  it('searches in the branch’s own region and path, which the operator’s grant covers', async () => {
    const fixture = create();
    await pass(fixture);

    type(el(fixture, 'q-combobox-input'), 'amir');
    await pass(fixture, 300);

    expect(geo.contexts[0]).toEqual(
      expect.objectContaining({ locationId: 'l1', regionId: 'r-tashkent' }),
    );
  });

  it('can be completed by typing alone: no provider, no point, and a landmark makes it LANDMARK_ONLY', async () => {
    const fixture = create();
    await pass(fixture);

    type(el(fixture, 'q-address-street'), 'Bunyodkor 12');
    type(el(fixture, 'address-editor-city'), 'Ташкент');
    type(el(fixture, 'address-editor-district'), 'Чиланзар');
    await pass(fixture);
    expect(last().valid).toBe(true);
    expect(last().fields.line1).toBe('Bunyodkor 12');
    expect(last().coordinateSource).toBe('NOT_GEOCODED');

    type(el(fixture, 'q-address-landmark'), 'blue gate');
    await pass(fixture);

    expect(last().coordinateSource).toBe('LANDMARK_ONLY');
    expect(last().latitude).toBeNull();
    expect(last().fields.landmark).toBe('blue gate');
  });

  it('turns a chosen suggestion into an operator’s pin only once they have looked at it, and fills the city and district', async () => {
    const fixture = create();
    await pass(fixture);

    await chooseSuggestion(fixture);

    expect(last().latitude).toBeNull();
    expect(last().coordinateSource).toBe('NOT_GEOCODED');
    expect((el(fixture, 'address-editor-city') as HTMLInputElement).value).toBe('Ташкент');
    expect((el(fixture, 'address-editor-district') as HTMLInputElement).value).toBe('Юнусабад');

    el(fixture, 'q-address-confirm')!.click();
    await pass(fixture);

    expect(last().latitude).toBe(41.3111);
    expect(last().longitude).toBe(69.2797);
    expect(last().coordinateSource).toBe('OPERATOR_PIN');
    expect(last().fields.line1).toBe('Amir Temur ko‘chasi 15');
    expect(last().valid).toBe(true);
  });

  it('does not overwrite a city or district the operator typed with what the provider says', async () => {
    const fixture = create();
    await pass(fixture);
    type(el(fixture, 'address-editor-district'), 'Мирзо-Улугбек');

    await chooseSuggestion(fixture);

    expect((el(fixture, 'address-editor-district') as HTMLInputElement).value).toBe(
      'Мирзо-Улугбек',
    );
    expect((el(fixture, 'address-editor-city') as HTMLInputElement).value).toBe('Ташкент');
  });

  describe('editing a saved address', () => {
    it('shows it as it was, and an untouched save changes nothing about its pin, not even whose it is', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      expect(last()).toEqual({
        label: 'Home',
        fields: expect.objectContaining({
          line1: 'Bunyodkor 12',
          line2: 'Block B',
          postalCode: '100000',
          city: 'Ташкент',
          district: 'Чиланзар',
          entrance: '2',
          floor: '5',
          apartment: '41',
          landmark: 'blue gate',
        }),
        latitude: 41.2,
        longitude: 69.1,
        coordinateSource: 'CUSTOMER_PIN',
        valid: true,
      });
    });

    it('keeps a customer’s own pin the customer’s through a text-only edit (row 5.2c)', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      type(el(fixture, 'address-editor-label'), 'Work');
      type(el(fixture, 'address-editor-city'), 'Самарканд');
      type(el(fixture, 'q-address-flat'), '42');
      await pass(fixture);

      expect(last().label).toBe('Work');
      expect(last().fields.city).toBe('Самарканд');
      expect(last().fields.apartment).toBe('42');
      expect(last().latitude).toBe(41.2);
      expect(last().coordinateSource).toBe('CUSTOMER_PIN');
    });

    it('makes a pin the operator moved their own, and never claims the provider placed it', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      provider.map.livePins[0].simulateDrag(at(41.25, 69.15));
      await pass(fixture);

      expect(last().latitude).toBe(41.25);
      expect(last().longitude).toBe(69.15);
      expect(last().coordinateSource).toBe('OPERATOR_PIN');
    });

    it('keeps the source when a pin is moved and put back exactly where it was', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      provider.map.livePins[0].simulateDrag(at(41.25, 69.15));
      await pass(fixture);
      provider.map.livePins[0].simulateDrag(at(41.2, 69.1));
      await pass(fixture);

      expect(last().coordinateSource).toBe('CUSTOMER_PIN');
    });

    it('places a first pin on an address that had none, as the operator’s', async () => {
      const fixture = create({
        initial: saved({ latitude: null, longitude: null, coordinateSource: 'LANDMARK_ONLY' }),
      });
      await pass(fixture);
      expect(last().coordinateSource).toBe('LANDMARK_ONLY');

      type(el(fixture, 'q-map-pin-latitude'), '41.3');
      type(el(fixture, 'q-map-pin-longitude'), '69.2');
      await pass(fixture);

      expect(last().latitude).toBe(41.3);
      expect(last().coordinateSource).toBe('OPERATOR_PIN');
    });

    it('leaves no point and no claim to one when the pin is taken away', async () => {
      const fixture = create({ initial: saved({ fields: { ...saved().fields, landmark: null } }) });
      await pass(fixture);

      el(fixture, 'q-map-pin-remove')!.click();
      await pass(fixture);

      expect(last().latitude).toBeNull();
      expect(last().longitude).toBeNull();
      expect(last().coordinateSource).toBe('NOT_GEOCODED');
    });

    it('starts again from a different saved address when asked to edit another', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      fixture.componentRef.setInput(
        'initial',
        saved({ id: 'addr-2', label: 'Office', fields: { ...saved().fields, city: 'Бухара' } }),
      );
      await pass(fixture);

      expect(last().label).toBe('Office');
      expect(last().fields.city).toBe('Бухара');
    });
  });

  describe('the second line and postal code', () => {
    it('carries them through unseen when the editor is not extended', async () => {
      const fixture = create({ initial: saved() });
      await pass(fixture);

      expect(el(fixture, 'address-editor-line2')).toBeNull();
      expect(last().fields.line2).toBe('Block B');
      expect(last().fields.postalCode).toBe('100000');
    });

    it('offers and sends them when it is extended, and clears them when emptied', async () => {
      const fixture = create({ initial: saved(), extended: true });
      await pass(fixture);
      expect((el(fixture, 'address-editor-line2') as HTMLInputElement).value).toBe('Block B');

      type(el(fixture, 'address-editor-line2'), '');
      type(el(fixture, 'address-editor-postal-code'), '100100');
      await pass(fixture);

      expect(last().fields.line2).toBeNull();
      expect(last().fields.postalCode).toBe('100100');
    });
  });

  it('can never produce a provider-sourced point, whatever the person does', async () => {
    const fixture = create();
    await pass(fixture);
    await chooseSuggestion(fixture);
    el(fixture, 'q-address-confirm')!.click();
    await pass(fixture);
    provider.map.livePins[0].simulateDrag(at(41.4, 69.4));
    await pass(fixture);

    expect(new Set(drafts.map((draft) => draft.coordinateSource))).toEqual(
      new Set(['NOT_GEOCODED', 'OPERATOR_PIN']),
    );
  });
});
