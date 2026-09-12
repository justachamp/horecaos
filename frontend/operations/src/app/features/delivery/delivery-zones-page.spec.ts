import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n, Locale } from '../../core/i18n/i18n';
import {
  ActiveVersionResponse,
  DeliveryTariffsApi,
  TariffDetailResponse,
  TariffSummaryResponse,
} from './delivery-tariffs-api';
import {
  DeliveryZonesApi,
  ZoneDetailResponse,
  ZoneSummaryResponse,
  ZoneVersionResponse,
} from './delivery-zones-api';
import { DeliveryZonesPage } from './delivery-zones-page';
import { RegionsApi } from './regions-api';

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

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryZonesPage', () => {
  let fixture: ComponentFixture<DeliveryZonesPage>;

  async function render(
    api: Partial<DeliveryZonesApi>,
    tariffs: Partial<DeliveryTariffsApi> = { list: vi.fn().mockResolvedValue([]) },
    locale: Locale = 'en',
  ): Promise<void> {
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
        { provide: RegionsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
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
