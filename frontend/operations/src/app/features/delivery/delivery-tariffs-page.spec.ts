import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { applyRegionalFormats, resetRegionalFormats } from '../../core/format/regional-format';
import { I18n } from '../../core/i18n/i18n';
import {
  ActiveVersionResponse,
  DeliveryTariffsApi,
  RoutingView,
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

/** What the server's routing read says, with everything the screen reads filled in. */
function routing(overrides: Partial<RoutingView> = {}): RoutingView {
  return {
    basis: 'ROAD',
    basisEvidence: 'FEES',
    roadFactorBasisPoints: 13_000,
    engineEnabled: true,
    installationStatus: 'ACTIVE',
    provider: 'osrm',
    engineDatasetVersion: '2026-10-01',
    lastDatasetVersion: '2026-10-01',
    roadFees: 12,
    fallbackFees: 0,
    windowHours: 24,
    lastDistanceSource: 'ROAD',
    lastResolvedAt: '2026-10-07T08:00:00Z',
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryTariffsPage', () => {
  let fixture: ComponentFixture<DeliveryTariffsPage>;

  afterEach(() => resetRegionalFormats());

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

  it('writes every fee and rate-table amount the way the brand chose (row 10.12)', async () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });
    await expand({
      tariff: TARIFF,
      activeVersion: version({
        minFeeMinor: 5_000,
        maxFeeMinor: 40_000,
        feeRoundingStepMinor: 1_000,
        feeRoundingRule: 'HALF_UP',
        bands: [
          { bandSet: 'BASE', fromMeters: 0, toMeters: 5_000, baseMinor: 12_000, perKmMinor: 2_500 },
        ],
        timeRules: [
          {
            priority: 0,
            dayMask: 31,
            fromTime: '18:00:00',
            toTime: '22:00:00',
            bandSet: null,
            multiplierBasisPoints: 15_000,
            surchargeMinor: 3_000,
          },
        ],
        discounts: [
          {
            priority: 0,
            kind: 'AMOUNT',
            amountMinor: 4_000,
            allowanceMeters: null,
            dayMask: 127,
            fromTime: '10:00:00',
            toTime: '14:00:00',
          },
        ],
      }),
    });

    const facts = host().querySelector('.tariff-detail__facts')!.textContent!;
    const written = facts.replace(/\s+/g, ' ');
    expect(written).toContain('UZS 5,000');
    expect(written).toContain('UZS 40,000');
    expect(written).toContain('UZS 1,000');
    const band = host().querySelector('[data-testid="tariff-band-row"]')!.textContent!;
    expect(band).toContain('12,000');
    expect(band).toContain('2,500');
    expect(host().querySelector('[data-testid="tariff-time-rule-row"]')!.textContent).toContain(
      '3,000',
    );
    expect(host().querySelector('[data-testid="tariff-discount-row"]')!.textContent).toContain(
      '4,000',
    );
  });

  describe('what the distance is measured by (ADR 0147)', () => {
    const ROAD_TARIFF = { ...TARIFF, distanceMode: 'ROAD' };

    it('renders RADIUS_FALLBACK when the fees say a ROAD tariff is falling back', async () => {
      await expand({
        tariff: ROAD_TARIFF,
        activeVersion: version({ distanceMode: 'ROAD', roadFactorBasisPoints: 13_000 }),
        routing: routing({
          basis: 'STRAIGHT_LINE_FALLBACK',
          roadFees: 0,
          fallbackFees: 7,
          lastDistanceSource: 'RADIUS_FALLBACK',
          lastDatasetVersion: null,
        }),
      });

      const banner = host().querySelector('[data-testid="tariff-radius-fallback"]');
      expect(banner).not.toBeNull();
      expect(banner!.textContent).toContain('RADIUS_FALLBACK');
      expect(banner!.textContent).toContain('1.3');
      expect(host().querySelector('[data-testid="tariff-basis"]')!.textContent).toContain(
        'detour factor',
      );
      expect(host().querySelector('[data-testid="tariff-basis-evidence"]')!.textContent).toContain(
        '7 by straight line',
      );
    });

    it('says nothing of a fallback while the engine is measuring, and names the dataset', async () => {
      await expand({
        tariff: ROAD_TARIFF,
        activeVersion: version({ distanceMode: 'ROAD', routingProviderInstallationId: 'inst-1' }),
        routing: routing(),
      });

      // The notice used to be unconditional on the distance mode, which would have told an
      // operator whose routing works that it is broken.
      expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).toBeNull();
      expect(host().querySelector('[data-testid="tariff-basis"]')!.textContent).toContain(
        'Road distance',
      );
      expect(host().querySelector('[data-testid="tariff-dataset"]')!.textContent).toContain(
        '2026-10-01',
      );
      expect(host().querySelector('[data-testid="tariff-basis-evidence"]')!.textContent).toContain(
        '12 by road',
      );
    });

    it('says when it is reading the configuration rather than fees, in both directions', async () => {
      await expand({
        tariff: ROAD_TARIFF,
        activeVersion: version({ distanceMode: 'ROAD', routingProviderInstallationId: 'inst-1' }),
        routing: routing({
          basisEvidence: 'CONFIGURATION',
          roadFees: 0,
          lastDatasetVersion: null,
          lastDistanceSource: null,
        }),
      });
      // An inference is worded as one, so nobody reads "road" as something the screen saw.
      expect(host().querySelector('[data-testid="tariff-basis-evidence"]')!.textContent).toContain(
        'Expected from configuration',
      );
      expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).toBeNull();
      // Dataset from what the engine holds now, because no fee has named one.
      expect(host().querySelector('[data-testid="tariff-dataset"]')!.textContent).toContain(
        '2026-10-01',
      );
      fixture.destroy();
      TestBed.resetTestingModule();

      await expand({
        tariff: ROAD_TARIFF,
        activeVersion: version({ distanceMode: 'ROAD', routingProviderInstallationId: 'inst-1' }),
        routing: routing({
          basis: 'STRAIGHT_LINE_FALLBACK',
          basisEvidence: 'CONFIGURATION',
          engineEnabled: false,
          roadFees: 0,
          lastDatasetVersion: null,
          engineDatasetVersion: null,
        }),
      });
      expect(host().querySelector('[data-testid="tariff-basis-evidence"]')!.textContent).toContain(
        'Expected from configuration',
      );
      expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).not.toBeNull();
    });

    it('says the straight line by choice on a RADIUS tariff, with no fallback and no dataset', async () => {
      await expand({
        tariff: TARIFF,
        activeVersion: version(),
        routing: routing({
          basis: 'STRAIGHT_LINE',
          basisEvidence: 'CONFIGURATION',
          engineEnabled: false,
          provider: null,
          installationStatus: null,
          roadFees: 0,
          lastDatasetVersion: null,
          engineDatasetVersion: null,
        }),
      });

      expect(host().querySelector('[data-testid="tariff-basis"]')!.textContent).toContain(
        'straight line from the branch',
      );
      expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).toBeNull();
      expect(host().querySelector('[data-testid="tariff-dataset"]')).toBeNull();
      expect(host().querySelector('[data-testid="tariff-basis-evidence"]')).toBeNull();
    });

    it('claims nothing about routing when the server sends no routing read', async () => {
      await expand({
        tariff: ROAD_TARIFF,
        activeVersion: version({ distanceMode: 'ROAD' }),
      });

      expect(host().querySelector('[data-testid="tariff-routing"]')).toBeNull();
      expect(host().querySelector('[data-testid="tariff-radius-fallback"]')).toBeNull();
    });
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

  describe('drafting a road tariff (ADR 0147)', () => {
    async function openDraft(
      options: {
        engine?: { engineEnabled: boolean; datasetVersion?: string | null } | 'fails';
        detail?: TariffDetailResponse;
      } = {},
    ): Promise<ReturnType<typeof vi.fn>> {
      const draftVersion = vi
        .fn()
        .mockResolvedValue({ tariffId: TARIFF.tariffId, version: 2, status: 'DRAFT' });
      await render({
        list: vi.fn().mockResolvedValue([TARIFF]),
        detail: vi
          .fn()
          .mockResolvedValue(options.detail ?? { tariff: TARIFF, activeVersion: version() }),
        routingEngine:
          options.engine === 'fails'
            ? vi.fn().mockRejectedValue(new Error('down'))
            : vi.fn().mockResolvedValue(options.engine ?? { engineEnabled: false }),
        draftVersion,
      });
      if (options.detail) {
        host().querySelector<HTMLElement>('[data-testid="tariff-row"]')!.click();
        await flushMicrotasks();
        fixture.detectChanges();
      }
      host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft"]')!.click();
      fixture.detectChanges();
      return draftVersion;
    }

    function chooseRoad(): void {
      const mode = host().querySelector<HTMLSelectElement>('[data-testid="tariff-distance-mode"]')!;
      mode.value = 'ROAD';
      mode.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }

    async function submit(): Promise<void> {
      host().querySelector<HTMLButtonElement>('[data-testid="tariff-draft-submit"]')!.click();
      await flushMicrotasks();
    }

    it('offers platform routing, on, for a road tariff, and asks for no installation id', async () => {
      await openDraft();
      expect(host().querySelector('[data-testid="tariff-use-platform-routing"]')).toBeNull();

      chooseRoad();

      const box = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-use-platform-routing"]',
      )!;
      expect(box.checked).toBe(true);
      expect(host().querySelector('[data-testid="tariff-routing-installation"]')).toBeNull();
      expect(host().querySelector('[data-testid="tariff-road-warning"]')).toBeNull();
    });

    it('sends usePlatformRouting and no installation for a road draft on platform routing', async () => {
      const draftVersion = await openDraft();
      chooseRoad();
      await submit();

      const body = draftVersion.mock.calls[0][2];
      expect(body.distanceMode).toBe('ROAD');
      expect(body.usePlatformRouting).toBe(true);
      expect(body.routingProviderInstallationId).toBeNull();
    });

    it('sends neither for a straight-line draft, which the server would refuse otherwise', async () => {
      const draftVersion = await openDraft();
      await submit();

      const body = draftVersion.mock.calls[0][2];
      expect(body.distanceMode).toBe('RADIUS');
      expect(body.usePlatformRouting).toBe(false);
      expect(body.routingProviderInstallationId).toBeNull();
    });

    it('warns before drafting ROAD against an installation id nobody has typed', async () => {
      await openDraft();
      chooseRoad();
      const box = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-use-platform-routing"]',
      )!;
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(host().querySelector('[data-testid="tariff-routing-installation"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="tariff-road-warning"]')).not.toBeNull();
    });

    it('sends the installation the operator named, and not platform routing', async () => {
      const draftVersion = await openDraft();
      chooseRoad();
      const box = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-use-platform-routing"]',
      )!;
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      const input = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-routing-installation"]',
      )!;
      input.value = ' 0f4e6c2a-1111-2222-3333-444455556666 ';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      await submit();

      const body = draftVersion.mock.calls[0][2];
      expect(body.usePlatformRouting).toBe(false);
      expect(body.routingProviderInstallationId).toBe('0f4e6c2a-1111-2222-3333-444455556666');
    });

    it('says which basis applies and the dataset when the engine is on', async () => {
      await openDraft({ engine: { engineEnabled: true, datasetVersion: '2026-10-01' } });
      chooseRoad();

      const text = host().querySelector('[data-testid="tariff-draft-basis"]')!.textContent!;
      expect(text).toContain('measured by road');
      expect(text).toContain('2026-10-01');
    });

    it('says what happens until the engine is on, rather than promising a road', async () => {
      await openDraft({ engine: { engineEnabled: false } });
      chooseRoad();

      const text = host().querySelector('[data-testid="tariff-draft-basis"]')!.textContent!;
      expect(text).toContain('not switched on yet');
      expect(text).toContain('RADIUS_FALLBACK');
      expect(text).toContain('1.3');
    });

    it('says only what is certain when the engine read failed', async () => {
      await openDraft({ engine: 'fails' });
      chooseRoad();

      const text = host().querySelector('[data-testid="tariff-draft-basis"]')!.textContent!;
      expect(text).toContain('If it does not answer');
      expect(text).not.toContain('2026');
    });

    it('says the straight line for a straight-line draft', async () => {
      await openDraft();

      expect(host().querySelector('[data-testid="tariff-draft-basis"]')!.textContent).toContain(
        'straight line from the branch',
      );
    });

    it('keeps a re-draft of a platform-routing version on platform routing', async () => {
      const draftVersion = await openDraft({
        detail: {
          tariff: { ...TARIFF, distanceMode: 'ROAD' },
          activeVersion: version({
            distanceMode: 'ROAD',
            routingProviderInstallationId: 'platform-installation',
          }),
          routing: routing(),
        },
      });

      const box = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-use-platform-routing"]',
      )!;
      expect(box.checked).toBe(true);
      await submit();

      const body = draftVersion.mock.calls[0][2];
      expect(body.usePlatformRouting).toBe(true);
      // The id of the platform installation is not sent back: the server re-finds it, and
      // sending both is the "two answers to one question" it refuses.
      expect(body.routingProviderInstallationId).toBeNull();
    });

    it('keeps a re-draft of a version bound to an installation the operator named on it', async () => {
      const draftVersion = await openDraft({
        detail: {
          tariff: { ...TARIFF, distanceMode: 'ROAD' },
          activeVersion: version({
            distanceMode: 'ROAD',
            routingProviderInstallationId: 'their-installation',
          }),
          routing: routing({ provider: null, installationStatus: 'ACTIVE' }),
        },
      });

      const box = host().querySelector<HTMLInputElement>(
        '[data-testid="tariff-use-platform-routing"]',
      )!;
      expect(box.checked).toBe(false);
      await submit();

      const body = draftVersion.mock.calls[0][2];
      expect(body.usePlatformRouting).toBe(false);
      expect(body.routingProviderInstallationId).toBe('their-installation');
    });
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
