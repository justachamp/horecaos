import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { MapBounds } from '../../shared/ui/map/map-provider';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { DeliveryTariffsApi, TariffSummaryResponse } from './delivery-tariffs-api';
import { BatchImportResponse, DeliveryZonesApi, ZoneOutlineResponse } from './delivery-zones-api';
import { GeozoneBatchImportPage } from './geozone-batch-import-page';
import { MapRegion, MapRegionService } from './map-region';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

/** A legacy export's row: the old exporter wrote the three name columns, which the page reads as the file's own format. */
const VALID_ROW = {
  externalRef: 'legacy-1',
  code: 'CITY',
  displayNameRu: 'Город',
  displayNameUz: 'Shahar',
  displayNameEn: 'City',
  currency: 'UZS',
  priority: 0,
  geoJson:
    '{"type":"Polygon","coordinates":[[[69.2,41.3],[69.25,41.3],[69.225,41.35],[69.2,41.3]]]}',
};

const CITY_TARIFF: TariffSummaryResponse = {
  tariffId: 'tariff-city',
  code: 'CITY',
  name: 'City tariff',
  status: 'ACTIVE',
  brandDefault: false,
};

const TASHKENT: MapBounds = {
  southWest: { latitude: 41.15, longitude: 69.04 },
  northEast: { latitude: 41.47, longitude: 69.46 },
};

/** The brand's locale set, as far as the page reads it: its default language. */
function localeSet(defaultLocale: string): Partial<LocaleSet> {
  return {
    ensureLoaded: () => Promise.resolve(),
    defaultLocale: signal(defaultLocale),
  };
}

/** The regions the page reads: Tashkent's box, or nothing at all for a tenant that may not read them. */
class FakeRegions {
  region: MapRegion | null = {
    regionId: 'region-tashkent',
    code: 'TASHKENT',
    platform: true,
    bounds: TASHKENT,
    centre: { latitude: 41.3, longitude: 69.25 },
  };
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  regionFor = (): MapRegion | null => this.region;
}

/** The same square written the wrong way round: latitude where longitude belongs. */
const SWAPPED_ROW = {
  ...VALID_ROW,
  externalRef: 'legacy-2',
  code: 'RING',
  geoJson:
    '{"type":"Polygon","coordinates":[[[41.3,69.2],[41.3,69.25],[41.35,69.225],[41.3,69.2]]]}',
};

const UNREADABLE_ROW = {
  ...VALID_ROW,
  externalRef: 'legacy-3',
  code: 'NOPE',
  geoJson: '{"type":"Point","coordinates":[69.2,41.3]}',
};

const STORED: ZoneOutlineResponse = {
  zoneId: 'zone-1',
  code: 'CITY',
  role: 'DELIVERY',
  version: 1,
  status: 'DRAFT',
  shapeKind: 'POLYGON',
  polygons: [
    {
      ring: [
        { latitude: 41.3, longitude: 69.2 },
        { latitude: 41.3, longitude: 69.25 },
        { latitude: 41.35, longitude: 69.225 },
      ],
      holes: [],
    },
  ],
};

function committed(accepted = true): BatchImportResponse {
  return {
    totalRows: 1,
    accepted: accepted ? 1 : 0,
    rejected: accepted ? 0 : 1,
    dryRun: false,
    rows: [
      {
        externalRef: 'legacy-1',
        accepted,
        zoneId: accepted ? 'zone-1' : null,
        version: accepted ? 1 : null,
        areaSquareMeters: 12_000,
        warnings: [],
        error: accepted ? null : 'REFUSED',
      },
    ],
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function jsonFile(name: string, content: unknown): File {
  return new File([JSON.stringify(content)], name, { type: 'application/json' });
}

function selectFile(host: HTMLElement, file: File): void {
  const input = host.querySelector('[data-testid="zone-import-file"]') as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

describe('GeozoneBatchImportPage', () => {
  let fixture: ComponentFixture<GeozoneBatchImportPage>;
  let provider: NullMapProvider;
  let regions: FakeRegions;

  async function render(
    api: Partial<DeliveryZonesApi>,
    tariffs: Partial<DeliveryTariffsApi> = { list: vi.fn().mockResolvedValue([]) },
    defaultLocale = 'ru',
  ): Promise<void> {
    provider = new NullMapProvider();
    regions = new FakeRegions();
    await TestBed.configureTestingModule({
      imports: [GeozoneBatchImportPage],
      providers: [
        provideNullMapProvider(provider),
        { provide: MapRegionService, useValue: regions },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DeliveryZonesApi, useValue: api },
        { provide: LocaleSet, useValue: localeSet(defaultLocale) },
        { provide: DeliveryTariffsApi, useValue: tariffs },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(GeozoneBatchImportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [GeozoneBatchImportPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DeliveryZonesApi, useValue: {} },
        { provide: LocaleSet, useValue: localeSet('ru') },
        { provide: DeliveryTariffsApi, useValue: { list: vi.fn() } },
        { provide: MapRegionService, useValue: new FakeRegions() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(GeozoneBatchImportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="zone-import-denied"]'),
    ).not.toBeNull();
  });

  it('previews the rows parsed from an uploaded JSON file', async () => {
    await render({});
    const host = fixture.nativeElement as HTMLElement;

    selectFile(host, jsonFile('zones.json', [VALID_ROW]));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="zone-import-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="zone-import-file-name"]')?.textContent).toContain(
      'zones.json',
    );
    expect(host.querySelector('[data-testid="zone-import-parse-error"]')).toBeNull();
  });

  it('reports a parse error rather than a silent empty table on a malformed file', async () => {
    await render({});
    const host = fixture.nativeElement as HTMLElement;

    selectFile(host, new File(['not json'], 'zones.json', { type: 'application/json' }));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="zone-import-parse-error"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="zone-import-row"]')).toHaveLength(0);
  });

  it('runs a dry run and renders the per-row outcome, including a coordinate-order warning', async () => {
    const report: BatchImportResponse = {
      totalRows: 1,
      accepted: 1,
      rejected: 0,
      dryRun: true,
      rows: [
        {
          externalRef: 'legacy-1',
          accepted: true,
          zoneId: 'zone-1',
          version: 1,
          areaSquareMeters: 12_000,
          warnings: ['OUTSIDE_REGION_BBOX: falls outside its region'],
          error: null,
        },
      ],
    };
    const importBatch = vi.fn().mockResolvedValue(report);
    await render({ importBatch });

    const host = fixture.nativeElement as HTMLElement;
    selectFile(host, jsonFile('zones.json', [VALID_ROW]));
    await flushMicrotasks();
    fixture.detectChanges();

    (host.querySelector('[data-testid="zone-import-dry-run"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(importBatch).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ dryRun: true, rows: expect.any(Array) }),
    );
    expect(host.querySelector('[data-testid="zone-import-outcome"]')?.textContent).toContain(
      'Accepted',
    );
    expect(host.querySelector('[data-testid="zone-import-warnings"]')?.textContent).toContain(
      'OUTSIDE_REGION_BBOX',
    );
    expect(host.querySelector('[data-testid="zone-import-summary"]')?.textContent).toContain(
      'Dry run',
    );
  });

  it('commits the batch with dryRun false when Import is clicked', async () => {
    const report: BatchImportResponse = {
      totalRows: 1,
      accepted: 1,
      rejected: 0,
      dryRun: false,
      rows: [
        {
          externalRef: 'legacy-1',
          accepted: true,
          zoneId: 'zone-1',
          version: 1,
          areaSquareMeters: 12_000,
          warnings: [],
          error: null,
        },
      ],
    };
    const importBatch = vi.fn().mockResolvedValue(report);
    await render({ importBatch });

    const host = fixture.nativeElement as HTMLElement;
    selectFile(host, jsonFile('zones.json', [VALID_ROW]));
    await flushMicrotasks();
    fixture.detectChanges();

    (host.querySelector('[data-testid="zone-import-commit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(importBatch).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ dryRun: false }),
    );
    expect(host.querySelector('[data-testid="zone-import-summary"]')?.textContent).toContain(
      'Imported',
    );
  });

  // ---------------------------------------------- row 10.12: a zone is named per language

  async function sentRows(
    rows: unknown[],
    defaultLocale = 'ru',
  ): Promise<readonly { displayNames: unknown }[]> {
    const importBatch = vi.fn().mockResolvedValue({ ...committed(), dryRun: true });
    await render({ importBatch }, undefined, defaultLocale);
    const host = await upload(rows);
    click(host, 'zone-import-dry-run');
    await flushMicrotasks();
    return importBatch.mock.calls[0][1].rows;
  }

  it('sends a legacy export’s three name columns as the name per language, and none of the old fields', async () => {
    const rows = await sentRows([VALID_ROW]);

    expect(rows[0].displayNames).toEqual({ ru: 'Город', 'uz-Latn': 'Shahar', en: 'City' });
    expect(Object.keys(rows[0])).not.toContain('displayNameRu');
  });

  it('reads a row’s own per-language names, a language the triple never held included', async () => {
    const { displayNameRu, displayNameUz, displayNameEn, ...bare } = VALID_ROW;
    void [displayNameRu, displayNameUz, displayNameEn];
    const rows = await sentRows([{ ...bare, displayNames: { kk: 'Қала', en: ' City ' } }]);

    expect(rows[0].displayNames).toEqual({ kk: 'Қала', en: 'City' });
  });

  it('names a row that names nothing by its code in the brand’s default language', async () => {
    const { displayNameRu, displayNameUz, displayNameEn, ...bare } = VALID_ROW;
    void [displayNameRu, displayNameUz, displayNameEn];
    const rows = await sentRows([bare], 'uz-Latn');

    expect(rows[0].displayNames).toEqual({ 'uz-Latn': 'CITY' });
  });

  // ------------------------------------------------ ADR 0145: looked at on a map

  async function upload(rows: unknown[]): Promise<HTMLElement> {
    const host = fixture.nativeElement as HTMLElement;
    selectFile(host, jsonFile('zones.json', rows));
    await flushMicrotasks();
    fixture.detectChanges();
    return host;
  }

  function click(host: HTMLElement, testId: string, index = 0): void {
    host.querySelectorAll<HTMLButtonElement>(`[data-testid="${testId}"]`)[index].click();
  }

  it('judges every row against its region before anything is sent, and names swapped coordinates', async () => {
    await render({});

    const host = await upload([VALID_ROW, SWAPPED_ROW]);

    const verdicts = [...host.querySelectorAll('[data-testid="zone-import-verdict"]')];
    expect(verdicts.map((v) => v.getAttribute('data-verdict'))).toEqual([
      'INSIDE',
      'LIKELY_SWAPPED',
    ]);
    expect(verdicts[1].textContent).toContain('swapped');
  });

  it('says it cannot draw a row that is not polygon GeoJSON, and offers no map for it', async () => {
    await render({});

    const host = await upload([UNREADABLE_ROW]);

    expect(host.querySelector('[data-testid="zone-import-unreadable"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="zone-import-show"]')).toBeNull();
  });

  it('judges nothing it cannot know when the tenant has no region to read', async () => {
    await render({});
    regions.region = null;

    const host = await upload([VALID_ROW]);

    expect(
      host.querySelector('[data-testid="zone-import-verdict"]')?.getAttribute('data-verdict'),
    ).toBe('NO_REGION');
  });

  it('shows a row on the map with its region’s box, beside the corners as the file wrote them and as they were read', async () => {
    await render({});
    const host = await upload([VALID_ROW]);

    click(host, 'zone-import-show');
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(provider.map.livePolygons).toHaveLength(1);
    expect(provider.map.livePolygons[0].ring[0]).toEqual({ latitude: 41.3, longitude: 69.2 });
    expect(provider.map.liveRectangles[0].bounds).toEqual(TASHKENT);
    const corners = host.querySelectorAll('[data-testid="zone-import-source-corner"]');
    expect(corners).toHaveLength(3);
    // Longitude first in the file; latitude first as read. Both are shown, so a swap is visible.
    expect(corners[0].textContent).toContain('[69.2, 41.3]');
    expect(corners[0].textContent).toContain('41.3, 69.2');
    expect(host.querySelector('[data-testid="zone-import-preview-verdict"]')).not.toBeNull();
  });

  it('offers no way to activate after a dry run, only after a real import of an accepted row', async () => {
    const importBatch = vi.fn().mockResolvedValue({ ...committed(), dryRun: true });
    await render({ importBatch });
    const host = await upload([VALID_ROW]);

    click(host, 'zone-import-dry-run');
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="zone-import-review"]')).toBeNull();

    importBatch.mockResolvedValue(committed(false));
    click(host, 'zone-import-commit');
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="zone-import-review"]')).toBeNull();

    importBatch.mockResolvedValue(committed());
    click(host, 'zone-import-commit');
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="zone-import-review"]')).not.toBeNull();
  });

  it('reviews the stored version and activates it only after confirmation, then marks the row live', async () => {
    const importBatch = vi.fn().mockResolvedValue(committed());
    const activate = vi.fn().mockResolvedValue({ zoneId: 'zone-1', version: 1, status: 'ACTIVE' });
    const outline = vi.fn().mockResolvedValue(STORED);
    await render({ importBatch, activate, outline });
    const host = await upload([VALID_ROW]);
    click(host, 'zone-import-commit');
    await flushMicrotasks();
    fixture.detectChanges();

    click(host, 'zone-import-review');
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(outline).toHaveBeenCalledWith(BRAND_SCOPE, 'zone-1', 1);
    expect(activate).not.toHaveBeenCalled();
    const confirm = host.querySelector<HTMLInputElement>('[data-testid="zone-review-confirm"]')!;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click(host, 'zone-review-activate');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(activate).toHaveBeenCalledWith(BRAND_SCOPE, 'zone-1', 1);
    expect(host.querySelector('[data-testid="zone-import-live"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="zone-import-review"]')).toBeNull();
    expect(host.querySelector('[data-testid="zone-import-review-dialog"]')).toBeNull();
  });

  it('keeps the review open and says why when the server refuses the activation', async () => {
    const importBatch = vi.fn().mockResolvedValue(committed());
    const activate = vi.fn().mockRejectedValue(new ApiError('VALIDATION_FAILED', 422, null, null));
    await render({ importBatch, activate, outline: vi.fn().mockResolvedValue(STORED) });
    const host = await upload([VALID_ROW]);
    click(host, 'zone-import-commit');
    await flushMicrotasks();
    fixture.detectChanges();
    click(host, 'zone-import-review');
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const confirm = host.querySelector<HTMLInputElement>('[data-testid="zone-review-confirm"]')!;
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    click(host, 'zone-review-activate');
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="zone-import-review-dialog"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="zone-import-review-error"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="zone-import-live"]')).toBeNull();
  });

  describe('the tariff the activation review reports (ADR 0037: look before geometry governs a fee)', () => {
    async function openReviewFor(
      row: unknown,
      tariffs: Partial<DeliveryTariffsApi>,
    ): Promise<string> {
      const importBatch = vi.fn().mockResolvedValue(committed());
      await render({ importBatch, outline: vi.fn().mockResolvedValue(STORED) }, tariffs);
      const host = await upload([row]);
      click(host, 'zone-import-commit');
      await flushMicrotasks();
      fixture.detectChanges();
      click(host, 'zone-import-review');
      await flushMicrotasks();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
      return host.querySelector('[data-testid="zone-review-tariff"]')?.textContent?.trim() ?? '';
    }

    it('names the tariff the imported version is bound to, not "no tariff"', async () => {
      const shown = await openReviewFor(
        { ...VALID_ROW, deliveryTariffId: CITY_TARIFF.tariffId },
        { list: vi.fn().mockResolvedValue([CITY_TARIFF]) },
      );

      expect(shown).toBe('Tariff: CITY — City tariff');
    });

    it('still discloses that a tariff is bound when the brand’s tariff list cannot be read', async () => {
      const shown = await openReviewFor(
        { ...VALID_ROW, deliveryTariffId: CITY_TARIFF.tariffId },
        { list: vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, null)) },
      );

      expect(shown).toBe('Tariff: tariff-city');
    });

    it('says no tariff is bound when the row carries none', async () => {
      const shown = await openReviewFor(VALID_ROW, {
        list: vi.fn().mockResolvedValue([CITY_TARIFF]),
      });

      expect(shown).toContain('No tariff is bound');
    });
  });
});
