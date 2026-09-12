import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { BatchImportResponse, DeliveryZonesApi } from './delivery-zones-api';
import { GeozoneBatchImportPage } from './geozone-batch-import-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

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

  async function render(api: Partial<DeliveryZonesApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [GeozoneBatchImportPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DeliveryZonesApi, useValue: api },
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
});
