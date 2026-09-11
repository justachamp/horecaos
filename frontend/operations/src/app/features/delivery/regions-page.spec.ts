import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n, Locale } from '../../core/i18n/i18n';
import { RegionResponse, RegionsApi } from './regions-api';
import { RegionsPage } from './regions-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const TENANT_REGION: RegionResponse = {
  regionId: 'region-1',
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

const PLATFORM_REGION: RegionResponse = {
  ...TENANT_REGION,
  regionId: 'region-platform',
  platform: true,
  code: 'TASHKENT',
  displayNameRu: 'Ташкент',
  displayNameUz: 'Toshkent',
  displayNameEn: 'Tashkent',
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RegionsPage', () => {
  let fixture: ComponentFixture<RegionsPage>;

  async function render(api: Partial<RegionsApi>, locale: Locale = 'en'): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [RegionsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal('actor-1') } },
        { provide: RegionsApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale(locale);
    fixture = TestBed.createComponent(RegionsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('says so when the tenant has no region at all — the guard is inert until it does', async () => {
    await render({ list: vi.fn().mockResolvedValue([]) });

    expect(host().querySelector('[data-testid="regions-empty"]')).not.toBeNull();
  });

  it('lists the tenant’s region and the platform’s, and offers no edit on the platform’s', async () => {
    await render({ list: vi.fn().mockResolvedValue([TENANT_REGION, PLATFORM_REGION]) });

    const rows = host().querySelectorAll('[data-testid="region-row"]');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('[data-testid="region-edit"]')).not.toBeNull();
    expect(rows[0].querySelector('[data-testid="region-archive"]')).not.toBeNull();
    expect(rows[1].querySelector('[data-testid="region-platform"]')).not.toBeNull();
    expect(rows[1].querySelector('[data-testid="region-edit"]')).toBeNull();
    expect(rows[1].querySelector('[data-testid="region-archive"]')).toBeNull();
  });

  it('shows the region name in the operator’s own locale', async () => {
    await render({ list: vi.fn().mockResolvedValue([TENANT_REGION]) }, 'uz-Latn');
    expect(host().querySelector('[data-testid="region-name"]')?.textContent?.trim()).toBe(
      'Samarqand',
    );
  });

  it('creates a region with the SW/NE box the geocoder is constrained by', async () => {
    const create = vi.fn().mockResolvedValue({ regionId: 'region-new', code: 'BUKHARA' });
    await render({ list: vi.fn().mockResolvedValue([]), create });

    host().querySelector<HTMLButtonElement>('.regions__create')!.click();
    fixture.detectChanges();
    for (const [testid, value] of [
      ['region-code', 'bukhara'],
      ['region-sw-lat', '39.6'],
      ['region-ne-lat', '39.9'],
    ] as const) {
      const input = host().querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    for (const input of host().querySelectorAll<HTMLInputElement>('.dialog input[type="text"]')) {
      if (input.getAttribute('data-testid') !== 'region-code') {
        input.value = 'Bukhara';
        input.dispatchEvent(new Event('input'));
      }
    }
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
    await flushMicrotasks();

    expect(create).toHaveBeenCalledTimes(1);
    const [tenantId, body] = create.mock.calls[0];
    expect(tenantId).toBe('t1');
    // The code is upper-cased for the database's own `ck_region_code` shape.
    expect(body.code).toBe('BUKHARA');
    expect(body.bboxSwLat).toBe(39.6);
    expect(body.bboxNeLat).toBe(39.9);
  });

  it('names every refused corner at once instead of only the first', async () => {
    const problems = [
      'The north-east corner must be north of the south-west one',
      'The centre must sit inside the bounding box',
    ];
    const create = vi.fn().mockRejectedValue(
      new ApiError(
        'VALIDATION_FAILED',
        422,
        {
          type: 'about:blank',
          title: 'Validation failed',
          status: 422,
          detail: 'The region is not oriented',
          problems,
        },
        'corr-1',
      ),
    );
    await render({ list: vi.fn().mockResolvedValue([]), create });

    host().querySelector<HTMLButtonElement>('.regions__create')!.click();
    fixture.detectChanges();
    for (const input of host().querySelectorAll<HTMLInputElement>('.dialog input[type="text"]')) {
      input.value = 'X';
      input.dispatchEvent(new Event('input'));
    }
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    const listed = host().querySelectorAll('[data-testid="region-problems"] li');
    expect(listed).toHaveLength(2);
    expect(listed[0].textContent).toContain('north-east');
    expect(listed[1].textContent).toContain('centre');
  });

  it('archives a region rather than deleting it, and reloads', async () => {
    const archive = vi.fn().mockResolvedValue(undefined);
    const list = vi
      .fn()
      .mockResolvedValueOnce([TENANT_REGION])
      .mockResolvedValue([{ ...TENANT_REGION, status: 'ARCHIVED' }]);
    await render({ list, archive });

    host().querySelector<HTMLButtonElement>('[data-testid="region-archive"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(archive).toHaveBeenCalledWith('t1', TENANT_REGION.regionId);
    expect(host().querySelector('[data-testid="region-archive"]')).toBeNull();
    expect(host().textContent).toContain('Archived');
  });

  it('shows the denied state when the tenant grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [RegionsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal(null) } },
        { provide: RegionsApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RegionsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="regions-denied"]')).not.toBeNull();
  });
});
