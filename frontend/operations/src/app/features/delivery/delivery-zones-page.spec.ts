import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { applyRegionalFormats, resetRegionalFormats } from '../../core/format/regional-format';
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import {
  ActiveVersionResponse,
  DeliveryTariffsApi,
  TariffDetailResponse,
  TariffSummaryResponse,
} from './delivery-tariffs-api';
import {
  DeliveryZonesApi,
  ZoneDetailResponse,
  ZoneOutlineResponse,
  ZoneSummaryResponse,
  ZoneVersionResponse,
} from './delivery-zones-api';
import { DeliveryZonesPage } from './delivery-zones-page';
import { RegionResponse, RegionsApi } from './regions-api';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const ZONE: ZoneSummaryResponse = {
  zoneId: 'zone-1',
  role: 'DELIVERY',
  code: 'CITY',
  displayNameRu: 'Город',
  displayNameUz: 'Shahar',
  displayNameEn: 'City',
  status: 'ACTIVE',
  activeVersion: 1,
  priority: 3,
  currency: 'UZS',
  deliveryTariffId: null,
  freeDeliveryFromMinor: 50_000,
  minBasketMinor: 20_000,
  areaSquareMeters: 12_000_000,
};

const PAID_TARIFF: TariffSummaryResponse = {
  tariffId: 'tariff-paid',
  code: 'CITY',
  name: 'City tariff',
  status: 'ACTIVE',
  brandDefault: true,
  activeVersion: 2,
  currency: 'UZS',
  feeSource: 'TARIFF',
  distanceMode: 'RADIUS',
  maxDistanceMeters: 15_000,
};

const FREE_TARIFF: TariffSummaryResponse = {
  ...PAID_TARIFF,
  tariffId: 'tariff-free',
  code: 'FREE',
  name: 'Free ring',
};

function version(overrides: Partial<ActiveVersionResponse> = {}): ActiveVersionResponse {
  return {
    version: 2,
    currency: 'UZS',
    feeSource: 'TARIFF',
    distanceMode: 'RADIUS',
    roadFactorBasisPoints: 13_000,
    routingProviderInstallationId: null,
    maxDistanceMeters: 15_000,
    minFeeMinor: 0,
    maxFeeMinor: null,
    distanceAccrual: 'STARTED_KILOMETRE',
    feeRoundingStepMinor: null,
    feeRoundingRule: null,
    bands: [{ bandSet: 'BASE', fromMeters: 0, toMeters: 15_000, baseMinor: 0, perKmMinor: 0 }],
    timeRules: [],
    discounts: [],
    ...overrides,
  };
}

function detailOf(
  tariff: TariffSummaryResponse,
  active: ActiveVersionResponse,
): TariffDetailResponse {
  return { tariff, activeVersion: active };
}

/** Row 10.12: a brand's resolved locale set, defaulting to the platform's own fallback triple — same fake the other converted editors' specs use. */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru', 'uz-Latn', 'en']);
  readonly defaultLocale = signal<Locale>('ru');
  readonly isConfigured = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryZonesPage', () => {
  let fixture: ComponentFixture<DeliveryZonesPage>;

  afterEach(() => resetRegionalFormats());

  let mapProvider: NullMapProvider;

  async function render(
    api: Partial<DeliveryZonesApi>,
    tariffs: Partial<DeliveryTariffsApi> = { list: vi.fn().mockResolvedValue([]) },
    locale: Locale = 'en',
    localeSet: FakeLocaleSet = new FakeLocaleSet(),
    regions: readonly RegionResponse[] = [],
  ): Promise<void> {
    mapProvider = new NullMapProvider();
    await TestBed.configureTestingModule({
      imports: [DeliveryZonesPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal({ tenantId: 't1', brandId: 'b1', locationId: 'loc-1' }),
            options: signal([{ id: 'loc-1', displayName: 'Chilanzar' }]),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal('actor-1') } },
        { provide: DeliveryZonesApi, useValue: api },
        { provide: DeliveryTariffsApi, useValue: tariffs },
        { provide: RegionsApi, useValue: { list: vi.fn().mockResolvedValue(regions) } },
        { provide: LocaleSet, useValue: localeSet },
        provideNullMapProvider(mapProvider),
        provideRouter([]),
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale(locale);
    fixture = TestBed.createComponent(DeliveryZonesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('lists a zone with its live version’s priority and status', async () => {
    await render({ list: vi.fn().mockResolvedValue([ZONE]) });

    expect(host().querySelectorAll('[data-testid="zone-row"]')).toHaveLength(1);
    expect(host().textContent).toContain('CITY');
    expect(host().textContent).toContain('v1');
    expect(host().querySelector('.zones__error-band')).toBeNull();
  });

  it('shows the zone name in the operator’s own locale, not one string in all three', async () => {
    await render({ list: vi.fn().mockResolvedValue([ZONE]) }, undefined, 'uz-Latn');
    expect(host().querySelector('[data-testid="zone-name"]')?.textContent?.trim()).toBe('Shahar');

    TestBed.inject(I18n).setLocale('ru');
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-name"]')?.textContent?.trim()).toBe('Город');
  });

  it('shows a zone named only in the per-locale map, falling back through the brand’s languages', async () => {
    const named: ZoneSummaryResponse = {
      ...ZONE,
      displayNameRu: '',
      displayNameUz: '',
      displayNameEn: '',
      displayNames: { kaa: 'Orayı', en: 'Centre' },
    };
    await render({ list: vi.fn().mockResolvedValue([named]) }, undefined, 'ru');

    expect(host().querySelector('[data-testid="zone-name"]')?.textContent?.trim()).toBe('Centre');
  });

  it('offers one name field per language of the brand’s set, default first, and requires the default', async () => {
    const create = vi
      .fn()
      .mockResolvedValue({ zoneId: 'zone-new', code: 'RING', role: 'DELIVERY' });
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['uz-Latn', 'en']);
    localeSet.defaultLocale.set('uz-Latn');
    await render({ list: vi.fn().mockResolvedValue([]), create }, undefined, 'en', localeSet);

    host().querySelector<HTMLButtonElement>('.zones__create')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-name-uz-Latn"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="zone-name-en"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="zone-name-ru"]')).toBeNull();

    const type = (el: HTMLInputElement, value: string): void => {
      el.value = value;
      el.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };
    const dialogInputs = host().querySelectorAll<HTMLInputElement>('.q-modal input[type="text"]');
    type(dialogInputs[0], 'ring'); // code
    type(host().querySelector<HTMLInputElement>('[data-testid="zone-name-en"]')!, 'Ring road');
    const submit = [...host().querySelectorAll<HTMLButtonElement>('.q-modal__actions button')].at(
      -1,
    )!;
    expect(submit.disabled).toBe(true); // English alone is not the default language

    type(host().querySelector<HTMLInputElement>('[data-testid="zone-name-uz-Latn"]')!, 'Halqa');
    submit.click();
    await flushMicrotasks();

    expect(create).toHaveBeenCalledWith(BRAND_SCOPE, {
      role: 'DELIVERY',
      code: 'ring',
      // The contract keeps the platform triple required: ru is not offered, so it takes the default's name.
      displayNameRu: 'Halqa',
      displayNameUz: 'Halqa',
      displayNameEn: 'Ring road',
      displayNames: { 'uz-Latn': 'Halqa', en: 'Ring road' },
    });
  });

  it('renames a zone through the names endpoint, sending only the offered, filled-in languages', async () => {
    const rename = vi.fn().mockResolvedValue({ zoneId: 'zone-1', displayNames: {} });
    await render({ list: vi.fn().mockResolvedValue([ZONE]), rename });

    host().querySelector<HTMLButtonElement>('[data-testid="zone-rename"]')!.click();
    fixture.detectChanges();
    // Prefilled from the zone's own names.
    expect(
      host().querySelector<HTMLInputElement>('[data-testid="zone-rename-name-ru"]')!.value,
    ).toBe('Город');
    const en = host().querySelector<HTMLInputElement>('[data-testid="zone-rename-name-en"]')!;
    en.value = 'City centre';
    en.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="zone-rename-submit"]')!.click();
    await flushMicrotasks();

    // Only the language the operator changed: the prefilled ru and uz-Latn are the
    // values the list held when the dialog opened, and resending them would write a
    // stale copy over whatever another operator saved since (the rename is not
    // versioned).
    expect(rename).toHaveBeenCalledWith(BRAND_SCOPE, 'zone-1', { en: 'City centre' });
    expect(host().querySelector('[data-testid="zone-rename-dialog"]')).toBeNull();
  });

  it('does not resend a name the operator left as it was loaded, so a concurrent rename is not reverted', async () => {
    const rename = vi.fn().mockResolvedValue({ zoneId: 'zone-1', displayNames: {} });
    await render({ list: vi.fn().mockResolvedValue([ZONE]), rename });

    host().querySelector<HTMLButtonElement>('[data-testid="zone-rename"]')!.click();
    fixture.detectChanges();
    const submit = host().querySelector<HTMLButtonElement>('[data-testid="zone-rename-submit"]')!;
    expect(submit.disabled, 'nothing was changed').toBe(true);

    const ru = host().querySelector<HTMLInputElement>('[data-testid="zone-rename-name-ru"]')!;
    ru.value = '  Центр  ';
    ru.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    const sent = rename.mock.calls[0][2];
    expect(sent).toEqual({ ru: 'Центр' });
    expect(Object.keys(sent)).not.toContain('en');
    expect(Object.keys(sent)).not.toContain('uz-Latn');
  });

  it('never sends, blanks or deletes a language the brand does not offer when it renames a zone', async () => {
    const carrying: ZoneSummaryResponse = {
      ...ZONE,
      displayNames: { ru: 'Город', 'uz-Latn': 'Shahar', en: 'City', kaa: 'Orayı' },
    };
    const rename = vi.fn().mockResolvedValue({ zoneId: 'zone-1', displayNames: {} });
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['ru', 'en']);
    localeSet.defaultLocale.set('ru');
    await render(
      { list: vi.fn().mockResolvedValue([carrying]), rename },
      undefined,
      'en',
      localeSet,
    );

    host().querySelector<HTMLButtonElement>('[data-testid="zone-rename"]')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-rename-name-uz-Latn"]')).toBeNull();
    expect(host().querySelector('[data-testid="zone-rename-name-kaa"]')).toBeNull();
    expect(host().querySelector('[data-testid="zone-hidden-kept"]')).not.toBeNull();

    // Clearing an offered, non-default field is "leave it", not "delete it".
    const en = host().querySelector<HTMLInputElement>('[data-testid="zone-rename-name-en"]')!;
    en.value = '';
    en.dispatchEvent(new Event('input'));
    const ru = host().querySelector<HTMLInputElement>('[data-testid="zone-rename-name-ru"]')!;
    ru.value = 'Центр';
    ru.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="zone-rename-submit"]')!.click();
    await flushMicrotasks();

    const sent = rename.mock.calls[0][2];
    expect(sent).toEqual({ ru: 'Центр' });
    expect(Object.keys(sent)).not.toContain('uz-Latn');
    expect(Object.keys(sent)).not.toContain('kaa');
  });

  it('writes the free-delivery and minimum-basket amounts the way the brand chose (row 10.12)', async () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });
    await render({
      list: vi.fn().mockResolvedValue([ZONE]),
      detail: vi.fn().mockResolvedValue({ zone: ZONE, boundLocationIds: [] } as ZoneDetailResponse),
      versions: vi.fn().mockResolvedValue([]),
    });

    host().querySelector<HTMLElement>('[data-testid="zone-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    const facts = host().querySelector('.zone-detail__facts')!.textContent!.replace(/\s+/g, ' ');
    expect(facts).toContain('Free delivery from UZS 50,000');
    expect(facts).toContain('Minimum basket UZS 20,000');
  });

  it('marks a zone bound to a zero-resolving tariff as free, and one bound to a priced tariff not (§3.6d)', async () => {
    const free = {
      ...ZONE,
      zoneId: 'zone-free',
      code: 'FREE',
      deliveryTariffId: FREE_TARIFF.tariffId,
    };
    const paid = {
      ...ZONE,
      zoneId: 'zone-paid',
      code: 'PAID',
      deliveryTariffId: PAID_TARIFF.tariffId,
    };
    await render(
      { list: vi.fn().mockResolvedValue([free, paid]) },
      {
        list: vi.fn().mockResolvedValue([FREE_TARIFF, PAID_TARIFF]),
        detail: vi.fn().mockImplementation((_scope: BrandScope, id: string) =>
          Promise.resolve(
            id === FREE_TARIFF.tariffId
              ? detailOf(FREE_TARIFF, version())
              : detailOf(
                  PAID_TARIFF,
                  version({
                    bands: [
                      {
                        bandSet: 'BASE',
                        fromMeters: 0,
                        toMeters: 15_000,
                        baseMinor: 10_000,
                        perKmMinor: 0,
                      },
                    ],
                  }),
                ),
          ),
        ),
      },
    );

    const rows = host().querySelectorAll('[data-testid="zone-row"]');
    expect(rows[0].querySelector('[data-testid="zone-free-marker"]')).not.toBeNull();
    expect(rows[1].querySelector('[data-testid="zone-free-marker"]')).toBeNull();
  });

  it('does not call a tariff free when a peak window surcharges it', async () => {
    const zone = { ...ZONE, deliveryTariffId: FREE_TARIFF.tariffId };
    await render(
      { list: vi.fn().mockResolvedValue([zone]) },
      {
        list: vi.fn().mockResolvedValue([FREE_TARIFF]),
        detail: vi.fn().mockResolvedValue(
          detailOf(
            FREE_TARIFF,
            version({
              timeRules: [
                {
                  priority: 0,
                  dayMask: 127,
                  fromTime: '18:00:00',
                  toTime: '22:00:00',
                  bandSet: null,
                  multiplierBasisPoints: 10_000,
                  surchargeMinor: 5_000,
                },
              ],
            }),
          ),
        ),
      },
    );

    expect(host().querySelector('[data-testid="zone-free-marker"]')).toBeNull();
  });

  it('sends the chosen tariff on the draft — the field the page used to drop', async () => {
    const draftCircleVersion = vi
      .fn()
      .mockResolvedValue({ zoneId: ZONE.zoneId, version: 2, status: 'DRAFT' });
    await render(
      {
        list: vi.fn().mockResolvedValue([ZONE]),
        detail: vi
          .fn()
          .mockResolvedValue({ zone: ZONE, boundLocationIds: [] } as ZoneDetailResponse),
        versions: vi.fn().mockResolvedValue([]),
        draftCircleVersion,
      },
      { list: vi.fn().mockResolvedValue([PAID_TARIFF]) },
    );

    host().querySelector<HTMLButtonElement>('[data-testid="zone-row"] button')!.click();
    fixture.detectChanges();
    const select = host().querySelector<HTMLSelectElement>('[data-testid="zone-tariff-select"]')!;
    select.value = PAID_TARIFF.tariffId;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="zone-draft-submit"]')!.click();
    await flushMicrotasks();

    expect(draftCircleVersion).toHaveBeenCalledWith(
      BRAND_SCOPE,
      ZONE.zoneId,
      expect.objectContaining({ deliveryTariffId: PAID_TARIFF.tariffId }),
    );
  });

  it('never sends a tariff or a threshold on a CATCHMENT draft — ck_zone_version_catchment_is_not_priced refuses them', async () => {
    const catchment = { ...ZONE, zoneId: 'zone-c', role: 'CATCHMENT', deliveryTariffId: null };
    const draftCircleVersion = vi
      .fn()
      .mockResolvedValue({ zoneId: catchment.zoneId, version: 1, status: 'DRAFT' });
    await render(
      {
        list: vi.fn().mockResolvedValue([catchment]),
        detail: vi
          .fn()
          .mockResolvedValue({ zone: catchment, boundLocationIds: [] } as ZoneDetailResponse),
        versions: vi.fn().mockResolvedValue([]),
        draftCircleVersion,
      },
      { list: vi.fn().mockResolvedValue([PAID_TARIFF]) },
    );

    host().querySelector<HTMLButtonElement>('[data-testid="zone-row"] button')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-tariff-select"]')).toBeNull();
    expect(host().querySelector('[data-testid="zone-catchment-hint"]')).not.toBeNull();

    host().querySelector<HTMLButtonElement>('[data-testid="zone-draft-submit"]')!.click();
    await flushMicrotasks();

    expect(draftCircleVersion).toHaveBeenCalledWith(
      BRAND_SCOPE,
      catchment.zoneId,
      expect.objectContaining({
        deliveryTariffId: null,
        freeDeliveryFromMinor: null,
        minBasketMinor: null,
      }),
    );
  });

  it('unbinds a branch from the zone and reloads the row', async () => {
    const detail = vi
      .fn()
      .mockResolvedValueOnce({ zone: ZONE, boundLocationIds: ['loc-1'] } as ZoneDetailResponse)
      .mockResolvedValue({ zone: ZONE, boundLocationIds: [] } as ZoneDetailResponse);
    const unbindLocation = vi.fn().mockResolvedValue(undefined);
    await render({
      list: vi.fn().mockResolvedValue([ZONE]),
      detail,
      versions: vi.fn().mockResolvedValue([]),
      unbindLocation,
    });

    host().querySelector<HTMLElement>('[data-testid="zone-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-bound-branch"]')?.textContent).toContain(
      'Chilanzar',
    );

    host().querySelector<HTMLButtonElement>('[data-testid="zone-unbind"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(unbindLocation).toHaveBeenCalledWith(BRAND_SCOPE, ZONE.zoneId, 'loc-1');
    expect(host().querySelector('[data-testid="zone-bound-branch"]')).toBeNull();
    expect(host().querySelector('[data-testid="zone-no-branches"]')).not.toBeNull();
  });

  it('offers activate on a draft version and deactivate on the live one', async () => {
    const versions: ZoneVersionResponse[] = [
      {
        version: 2,
        status: 'DRAFT',
        priority: 3,
        currency: 'UZS',
        deliveryTariffId: null,
        freeDeliveryFromMinor: null,
        minBasketMinor: null,
        areaSquareMeters: 12_000_000,
        regionId: null,
        originLocationId: 'loc-1',
        shapeKind: 'CIRCLE',
        createdAt: null,
        activatedAt: null,
        retiredAt: null,
      },
      {
        version: 1,
        status: 'ACTIVE',
        priority: 3,
        currency: 'UZS',
        deliveryTariffId: null,
        freeDeliveryFromMinor: null,
        minBasketMinor: null,
        areaSquareMeters: 12_000_000,
        regionId: null,
        originLocationId: 'loc-1',
        shapeKind: 'CIRCLE',
        createdAt: null,
        activatedAt: null,
        retiredAt: null,
      },
    ];
    const deactivate = vi
      .fn()
      .mockResolvedValue({ zoneId: ZONE.zoneId, version: 1, status: 'RETIRED' });
    await render({
      list: vi.fn().mockResolvedValue([ZONE]),
      detail: vi.fn().mockResolvedValue({ zone: ZONE, boundLocationIds: [] } as ZoneDetailResponse),
      versions: vi.fn().mockResolvedValue(versions),
      deactivate,
    });

    host().querySelector<HTMLElement>('[data-testid="zone-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().querySelectorAll('[data-testid="zone-version-row"]')).toHaveLength(2);
    expect(host().querySelector('[data-testid="zone-activate"]')).not.toBeNull();
    host().querySelector<HTMLButtonElement>('[data-testid="zone-deactivate"]')!.click();
    await flushMicrotasks();

    expect(deactivate).toHaveBeenCalledWith(BRAND_SCOPE, ZONE.zoneId, 1);
  });

  // ------------------------------------------------------ ADR 0145: zones on a map

  const SAMARKAND: RegionResponse = {
    regionId: 'region-sam',
    platform: false,
    code: 'SAMARKAND',
    displayNameRu: 'Самарканд',
    displayNameUz: 'Samarqand',
    displayNameEn: 'Samarkand',
    centreLat: 39.65,
    centreLon: 66.96,
    bboxSwLat: 39.4,
    bboxSwLon: 66.7,
    bboxNeLat: 39.9,
    bboxNeLon: 67.2,
    status: 'ACTIVE',
    version: 1,
  };

  function versionRow(overrides: Partial<ZoneVersionResponse>): ZoneVersionResponse {
    return {
      version: 2,
      status: 'DRAFT',
      priority: 3,
      currency: 'UZS',
      deliveryTariffId: null,
      freeDeliveryFromMinor: null,
      minBasketMinor: null,
      areaSquareMeters: 12_000_000,
      regionId: SAMARKAND.regionId,
      originLocationId: null,
      shapeKind: 'POLYGON',
      createdAt: null,
      activatedAt: null,
      retiredAt: null,
      ...overrides,
    };
  }

  function outlineOf(overrides: Partial<ZoneOutlineResponse> = {}): ZoneOutlineResponse {
    return {
      zoneId: ZONE.zoneId,
      code: ZONE.code,
      role: 'DELIVERY',
      version: 2,
      status: 'DRAFT',
      shapeKind: 'POLYGON',
      polygons: [
        {
          ring: [
            { latitude: 39.6, longitude: 66.9 },
            { latitude: 39.6, longitude: 67.0 },
            { latitude: 39.7, longitude: 66.95 },
          ],
          holes: [],
        },
      ],
      ...overrides,
    };
  }

  async function expandWithVersions(
    versions: ZoneVersionResponse[],
    api: Partial<DeliveryZonesApi> = {},
    regions: readonly RegionResponse[] = [SAMARKAND],
  ): Promise<void> {
    await render(
      {
        list: vi.fn().mockResolvedValue([ZONE]),
        detail: vi
          .fn()
          .mockResolvedValue({ zone: ZONE, boundLocationIds: [] } as ZoneDetailResponse),
        versions: vi.fn().mockResolvedValue(versions),
        outline: vi.fn().mockResolvedValue(outlineOf()),
        ...api,
      },
      { list: vi.fn().mockResolvedValue([PAID_TARIFF]) },
      'en',
      new FakeLocaleSet(),
      regions,
    );
    host().querySelector<HTMLElement>('[data-testid="zone-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function press(testId: string): void {
    host().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
  }

  async function settle(): Promise<void> {
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('drafts a polygon drawn on the map, sent as closed GeoJSON in [longitude, latitude] with the chosen tariff', async () => {
    const draftPolygonVersion = vi
      .fn()
      .mockResolvedValue({ zoneId: ZONE.zoneId, version: 2, status: 'DRAFT' });
    await expandWithVersions([], { draftPolygonVersion });

    host().querySelector<HTMLButtonElement>('[data-testid="zone-row"] button')!.click();
    fixture.detectChanges();
    const shape = host().querySelector<HTMLSelectElement>('[data-testid="zone-shape-select"]')!;
    shape.value = 'POLYGON';
    shape.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await settle();

    expect(host().querySelector('[data-testid="zone-origin-select"]')).toBeNull();
    expect(host().querySelector('[data-testid="q-polygon-editor"]')).not.toBeNull();
    expect(
      host().querySelector<HTMLButtonElement>('[data-testid="zone-draft-submit"]')!.disabled,
    ).toBe(true);

    for (let i = 0; i < 3; i++) {
      press('q-polygon-add-corner');
      fixture.detectChanges();
    }
    const tariff = host().querySelector<HTMLSelectElement>('[data-testid="zone-tariff-select"]')!;
    tariff.value = PAID_TARIFF.tariffId;
    tariff.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(
      host().querySelector<HTMLButtonElement>('[data-testid="zone-draft-submit"]')!.disabled,
    ).toBe(false);
    press('zone-draft-submit');
    await settle();

    expect(draftPolygonVersion).toHaveBeenCalledTimes(1);
    const [scope, zoneId, request] = draftPolygonVersion.mock.calls[0];
    expect(scope).toEqual(BRAND_SCOPE);
    expect(zoneId).toBe(ZONE.zoneId);
    expect(request.deliveryTariffId).toBe(PAID_TARIFF.tariffId);
    const geoJson = JSON.parse(request.geoJson) as { type: string; coordinates: number[][][] };
    expect(geoJson.type).toBe('Polygon');
    const ring = geoJson.coordinates[0];
    expect(ring).toHaveLength(4);
    expect(ring[0]).toEqual(ring[3]);
    // Samarkand's own centre is where a new outline starts, and GeoJSON puts longitude first.
    expect(ring[0]).toEqual([SAMARKAND.centreLon, SAMARKAND.centreLat]);
  });

  it('will not save an outline that has a problem the editor can name, such as a corner outside the region', async () => {
    const draftPolygonVersion = vi.fn();
    await expandWithVersions([], { draftPolygonVersion });
    host().querySelector<HTMLButtonElement>('[data-testid="zone-row"] button')!.click();
    fixture.detectChanges();
    const shape = host().querySelector<HTMLSelectElement>('[data-testid="zone-shape-select"]')!;
    shape.value = 'POLYGON';
    shape.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await settle();
    for (let i = 0; i < 3; i++) {
      press('q-polygon-add-corner');
      fixture.detectChanges();
    }
    const latitude = host().querySelector<HTMLInputElement>(
      '[data-testid="q-polygon-corner-latitude"]',
    )!;
    latitude.value = '41.2';
    latitude.dispatchEvent(new Event('change'));
    await settle();

    expect(host().querySelector('[data-problem="OUTSIDE_REGION"]')).not.toBeNull();
    const submit = host().querySelector<HTMLButtonElement>('[data-testid="zone-draft-submit"]')!;
    expect(submit.disabled).toBe(true);
    submit.click();
    await settle();
    expect(draftPolygonVersion).not.toHaveBeenCalled();
  });

  it('opens a stored polygon version in the editor to draft its successor, keeping its terms', async () => {
    const draftPolygonVersion = vi
      .fn()
      .mockResolvedValue({ zoneId: ZONE.zoneId, version: 3, status: 'DRAFT' });
    const outline = vi.fn().mockResolvedValue(outlineOf({ version: 2 }));
    await expandWithVersions(
      [versionRow({ version: 2, priority: 7, deliveryTariffId: PAID_TARIFF.tariffId })],
      { draftPolygonVersion, outline },
    );

    press('zone-edit-outline');
    await settle();

    expect(outline).toHaveBeenCalledWith(BRAND_SCOPE, ZONE.zoneId, 2);
    expect(host().querySelector('[data-testid="zone-polygon-from"]')?.textContent).toContain('2');
    expect(host().querySelectorAll('[data-testid="q-polygon-corner"]')).toHaveLength(3);
    press('zone-draft-submit');
    await settle();

    const request = draftPolygonVersion.mock.calls[0][2];
    expect(request.priority).toBe(7);
    expect(request.deliveryTariffId).toBe(PAID_TARIFF.tariffId);
    expect(request.regionId).toBe(SAMARKAND.regionId);
    expect(JSON.parse(request.geoJson).coordinates[0][0]).toEqual([66.9, 39.6]);
  });

  it('refuses to open in the editor a version it would flatten (holes, several parts)', async () => {
    const ring = outlineOf().polygons[0].ring;
    const withHole = outlineOf({ polygons: [{ ring, holes: [ring] }] });
    await expandWithVersions([versionRow({ version: 2 })], {
      outline: vi.fn().mockResolvedValue(withHole),
    });

    press('zone-edit-outline');
    await settle();

    expect(host().querySelector('[data-testid="zone-row-error"]')?.textContent).toContain(
      'holes or several parts',
    );
    expect(host().querySelector('[data-testid="q-polygon-editor"]')).toBeNull();
  });

  it('puts the review in front of activation: nothing is activated until the outline has been looked at and confirmed', async () => {
    const activate = vi
      .fn()
      .mockResolvedValue({ zoneId: ZONE.zoneId, version: 2, status: 'ACTIVE' });
    await expandWithVersions([versionRow({ version: 2 })], { activate });

    press('zone-activate');
    await settle();

    expect(host().querySelector('[data-testid="zone-review"]')).not.toBeNull();
    expect(mapProvider.map.livePolygons).toHaveLength(1);
    expect(activate).not.toHaveBeenCalled();
    expect(
      host().querySelector<HTMLButtonElement>('[data-testid="zone-review-activate"]')!.disabled,
    ).toBe(true);

    const confirm = host().querySelector<HTMLInputElement>('[data-testid="zone-review-confirm"]')!;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    press('zone-review-activate');
    await settle();

    expect(activate).toHaveBeenCalledWith(BRAND_SCOPE, ZONE.zoneId, 2);
    expect(host().querySelector('[data-testid="zone-review"]')).toBeNull();
  });

  it('keeps the review open and shows why when activation is refused', async () => {
    const activate = vi.fn().mockRejectedValue(new ApiError('VALIDATION_FAILED', 422, null, null));
    await expandWithVersions([versionRow({ version: 2 })], { activate });
    press('zone-activate');
    await settle();
    const confirm = host().querySelector<HTMLInputElement>('[data-testid="zone-review-confirm"]')!;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    press('zone-review-activate');
    await settle();

    expect(activate).toHaveBeenCalledTimes(1);
    expect(host().querySelector('[data-testid="zone-review"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="zone-review-error"]')).not.toBeNull();
  });

  it('shows a version on the map without offering to activate it', async () => {
    const activate = vi.fn();
    await expandWithVersions([versionRow({ version: 1, status: 'ACTIVE' })], { activate });

    press('zone-show-on-map');
    await settle();

    expect(host().querySelector('[data-testid="zone-review"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="zone-review-activate"]')).toBeNull();
    expect(host().querySelector('[data-testid="zone-review-confirm"]')).toBeNull();
    press('zone-review-close');
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="zone-review"]')).toBeNull();
    expect(activate).not.toHaveBeenCalled();
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DeliveryZonesPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            options: signal([]),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal(null) } },
        { provide: DeliveryZonesApi, useValue: { list: vi.fn() } },
        { provide: DeliveryTariffsApi, useValue: { list: vi.fn() } },
        { provide: RegionsApi, useValue: { list: vi.fn() } },
        provideRouter([]),
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryZonesPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="zones-denied"]')).not.toBeNull();
  });

  it('links to the bulk geozone import page', async () => {
    await render({ list: vi.fn().mockResolvedValue([]) });
    const link = host().querySelector('[data-testid="zones-import-link"]');
    expect(link?.getAttribute('href')).toBe('/delivery/zones/import');
  });
});
