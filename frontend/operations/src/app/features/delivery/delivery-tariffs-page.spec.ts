import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  ActiveVersionResponse,
  DeliveryTariffsApi,
  TariffDetailResponse,
  TariffSummaryResponse,
} from './delivery-tariffs-api';
import { DeliveryTariffsPage } from './delivery-tariffs-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const TARIFF: TariffSummaryResponse = {
  tariffId: 'tariff-1',
  code: 'CITY',
  name: 'City tariff',
  status: 'ACTIVE',
  brandDefault: true,
  activeVersion: 1,
  currency: 'UZS',
  feeSource: 'TARIFF',
  distanceMode: 'RADIUS',
  maxDistanceMeters: 15_000,
};

function version(overrides: Partial<ActiveVersionResponse> = {}): ActiveVersionResponse {
  return {
    version: 1,
    currency: 'UZS',
    feeSource: 'TARIFF',
    distanceMode: 'RADIUS',
    roadFactorBasisPoints: 13_000,
    routingProviderInstallationId: null,
    maxDistanceMeters: 15_000,
    minFeeMinor: 5_000,
    maxFeeMinor: null,
    distanceAccrual: 'STARTED_KILOMETRE',
    feeRoundingStepMinor: null,
    feeRoundingRule: null,
    bands: [{ bandSet: 'BASE', fromMeters: 0, toMeters: 15_000, baseMinor: 10_000, perKmMinor: 0 }],
    timeRules: [],
    discounts: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryTariffsPage', () => {
  let fixture: ComponentFixture<DeliveryTariffsPage>;

  async function render(api: Partial<DeliveryTariffsApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DeliveryTariffsPage],
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
        { provide: DeliveryTariffsApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryTariffsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function expand(detail: TariffDetailResponse): Promise<void> {
    await render({
      list: vi.fn().mockResolvedValue([detail.tariff]),
      detail: vi.fn().mockResolvedValue(detail),
    });
    host().querySelector<HTMLElement>('[data-testid="tariff-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists a tariff with its live version and brand-default flag', async () => {
    await render({ list: vi.fn().mockResolvedValue([TARIFF]) });

    expect(host().querySelectorAll('[data-testid="tariff-row"]')).toHaveLength(1);
    expect(host().textContent).toContain('CITY');
    expect(host().textContent).toContain('v1');
  });

  it('renders every band, peak window and discount rather than a count', async () => {
    await expand({
      tariff: TARIFF,
      activeVersion: version({
        bands: [
          { bandSet: 'BASE', fromMeters: 0, toMeters: 5_000, baseMinor: 10_000, perKmMinor: 0 },
          { bandSet: 'BASE', fromMeters: 5_000, toMeters: 15_000, baseMinor: 0, perKmMinor: 2_000 },
        ],
        timeRules: [
          {
            priority: 0,
            dayMask: 31,
            fromTime: '18:00:00',
            toTime: '22:00:00',
            bandSet: null,
            multiplierBasisPoints: 15_000,
            surchargeMinor: 0,
          },
        ],
        discounts: [
          {
            priority: 0,
            kind: 'DISTANCE_ALLOWANCE',
            amountMinor: null,
            allowanceMeters: 2_000,
            dayMask: 127,
            fromTime: '10:00:00',
            toTime: '14:00:00',
          },
        ],
      }),
    });

    expect(host().querySelectorAll('[data-testid="tariff-band-row"]')).toHaveLength(2);
    expect(host().querySelectorAll('[data-testid="tariff-time-rule-row"]')).toHaveLength(1);
    expect(host().querySelectorAll('[data-testid="tariff-discount-row"]')).toHaveLength(1);
    // The day mask is rendered as the days it names, not as the integer 31 and
    // not as "every day" — 31 is Monday to Friday and a weekend surcharge that
    // is not there must not be read into it.
    const rule = host().querySelector('[data-testid="tariff-time-rule-row"]')!;
    expect(rule.textContent).toContain('Mon, Tue, Wed, Thu, Fri');
    expect(rule.textContent).not.toContain('Sat');
    expect(rule.textContent).not.toContain('Every day');
  });

  it('renders RADIUS_FALLBACK on a ROAD tariff rather than hiding it', async () => {
    await expand({
      tariff: { ...TARIFF, distanceMode: 'ROAD' },
      activeVersion: version({ distanceMode: 'ROAD', roadFactorBasisPoints: 13_000 }),
    });

    const banner = host().querySelector('[data-testid="tariff-radius-fallback"]');
    expect(banner).not.toBeNull();
    expect(banner!.textContent).toContain('RADIUS_FALLBACK');
    expect(banner!.textContent).toContain('1.3');
  });

  it('says nothing about a fallback on a RADIUS tariff', async () => {
    await expand({ tariff: TARIFF, activeVersion: version() });

    expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).toBeNull();
  });

  it('drafts the whole rate table — many bands, a peak window and a discount', async () => {
    const draftVersion = vi
      .fn()
      .mockResolvedValue({ tariffId: TARIFF.tariffId, version: 2, status: 'DRAFT' });
    await render({
      list: vi.fn().mockResolvedValue([TARIFF]),
      detail: vi.fn().mockResolvedValue({ tariff: TARIFF, activeVersion: version() }),
      draftVersion,
    });

    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft"]')!.click();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-add-band"]')!.click();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-add-time-rule"]')!.click();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-add-discount"]')!.click();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft-submit"]')!.click();
    await flushMicrotasks();

    expect(draftVersion).toHaveBeenCalledTimes(1);
    const body = draftVersion.mock.calls[0][2];
    expect(body.bands).toHaveLength(2);
    expect(body.timeRules).toHaveLength(1);
    expect(body.discounts).toHaveLength(1);
    // The wire wants HH:MM:SS; the form holds HH:MM.
    expect(body.timeRules[0].fromTime).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    // Exactly one of the two discount amounts is set — `TariffDiscount`'s contract.
    expect(body.discounts[0].amountMinor).not.toBeNull();
    expect(body.discounts[0].allowanceMeters).toBeNull();
    // The base table is `null` on the wire; the server writes 'BASE'.
    expect(body.bands[0].bandSet).toBeNull();
  });

  it('sets the minimum fee through the shared q-money-input, grouped and in whole som', async () => {
    const draftVersion = vi
      .fn()
      .mockResolvedValue({ tariffId: TARIFF.tariffId, version: 2, status: 'DRAFT' });
    await render({
      list: vi.fn().mockResolvedValue([TARIFF]),
      detail: vi.fn().mockResolvedValue({ tariff: TARIFF, activeVersion: version() }),
      draftVersion,
    });

    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft"]')!.click();
    fixture.detectChanges();
    const minFeeField = host()
      .querySelector('[data-testid="tariff-min-fee"]')!
      .querySelector('[data-testid="q-money-input-field"]') as HTMLInputElement;

    minFeeField.value = '12 000';
    minFeeField.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(minFeeField.value).toBe('12 000');
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft-submit"]')!.click();
    await flushMicrotasks();

    expect(draftVersion.mock.calls[0][2].minFeeMinor).toBe(12_000);
  });

  it('carries the live version’s accrual into a redraft instead of resetting it', async () => {
    const draftVersion = vi
      .fn()
      .mockResolvedValue({ tariffId: TARIFF.tariffId, version: 2, status: 'DRAFT' });
    await render({
      list: vi.fn().mockResolvedValue([TARIFF]),
      detail: vi.fn().mockResolvedValue({
        tariff: TARIFF,
        activeVersion: version({ distanceAccrual: 'PRORATED_METRE' }),
      }),
      draftVersion,
    });

    host().querySelector<HTMLElement>('[data-testid="tariff-row"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft"]')!.click();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft-submit"]')!.click();
    await flushMicrotasks();

    expect(draftVersion.mock.calls[0][2].distanceAccrual).toBe('PRORATED_METRE');
  });

  it('warns before drafting ROAD with no routing installation', async () => {
    await render({
      list: vi.fn().mockResolvedValue([TARIFF]),
      detail: vi.fn().mockResolvedValue({ tariff: TARIFF, activeVersion: version() }),
    });

    host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft"]')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="tariff-road-warning"]')).toBeNull();

    const mode = host().querySelector<HTMLSelectElement>('[data-testid="tariff-distance-mode"]')!;
    mode.value = 'ROAD';
    mode.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="tariff-road-warning"]')).not.toBeNull();
  });

  it('binds the tariff to a branch — the caller `bindLocation` never had', async () => {
    const bindLocation = vi.fn().mockResolvedValue(undefined);
    await render({ list: vi.fn().mockResolvedValue([TARIFF]), bindLocation });

    host().querySelector<HTMLButtonElement>('[data-testid="tariff-bind"]')!.click();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="tariff-bind-submit"]')!.click();
    await flushMicrotasks();

    expect(bindLocation).toHaveBeenCalledWith(BRAND_SCOPE, TARIFF.tariffId, 'loc-1');
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DeliveryTariffsPage],
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
        { provide: DeliveryTariffsApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryTariffsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="tariffs-denied"]')).not.toBeNull();
  });
});
