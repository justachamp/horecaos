import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSetView } from '../../core/i18n/locale-labels';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
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

/** The tenant whose brands have not chosen a set: the platform triple, ru first. */
const PLATFORM_SET: LocaleSetView = {
  locales: ['ru', 'uz-Latn', 'en'],
  defaultLocale: 'ru',
  configured: false,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RegionsPage', () => {
  let fixture: ComponentFixture<RegionsPage>;
  let mapProvider: NullMapProvider;

  async function render(api: Partial<RegionsApi>, locale: Locale = 'en'): Promise<void> {
    const withLocaleSet: Partial<RegionsApi> = {
      localeSet: () => Promise.resolve(PLATFORM_SET),
      ...api,
    };
    mapProvider = new NullMapProvider();
    await TestBed.configureTestingModule({
      imports: [RegionsPage],
      providers: [
        provideNullMapProvider(mapProvider),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal('actor-1') } },
        { provide: RegionsApi, useValue: withLocaleSet },
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
    // Six distinct, recognisable numbers: a template binding swapped between
    // any two of them (or submit() dropping one from the outgoing request)
    // must be visible in the assertions below, not just the two that used to
    // be checked.
    for (const [testid, value] of [
      ['region-code', 'bukhara'],
      ['q-bbox-sw-lat', '39.6'],
      ['q-bbox-sw-lon', '64.1'],
      ['q-bbox-ne-lat', '39.9'],
      ['q-bbox-ne-lon', '64.5'],
      ['region-centre-lat', '39.75'],
      ['region-centre-lon', '64.3'],
    ] as const) {
      const input = host().querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    for (const input of host().querySelectorAll<HTMLInputElement>(
      '[data-testid^="region-name-"]',
    )) {
      input.value = 'Bukhara';
      input.dispatchEvent(new Event('input'));
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
    expect(body.bboxSwLon).toBe(64.1);
    expect(body.bboxNeLat).toBe(39.9);
    expect(body.bboxNeLon).toBe(64.5);
    expect(body.centreLat).toBe(39.75);
    expect(body.centreLon).toBe(64.3);
  });

  it('rewrites a region carrying the version the edit form was opened with', async () => {
    const update = vi.fn().mockResolvedValue(undefined);
    await render({ list: vi.fn().mockResolvedValue([TENANT_REGION]), update });

    host().querySelector<HTMLButtonElement>('[data-testid="region-edit"]')!.click();
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
    await flushMicrotasks();

    expect(update).toHaveBeenCalledTimes(1);
    const [tenantId, regionId, body] = update.mock.calls[0];
    expect(tenantId).toBe('t1');
    expect(regionId).toBe(TENANT_REGION.regionId);
    // The version the row carried when the form was opened (openEditForm),
    // not some later or hard-coded number — a stale one is what STALE_VERSION
    // exists to refuse.
    expect(body.expectedVersion).toBe(TENANT_REGION.version);
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
    for (const input of host().querySelectorAll<HTMLInputElement>(
      '[data-testid="region-code"], [data-testid^="region-name-"]',
    )) {
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

  it('shows a region named only in the per-locale map, and falls back through the tenant’s languages', async () => {
    // The platform columns are blank (a hand-written row); the map carries the
    // names. A row must not render as an empty cell.
    const named: RegionResponse = {
      ...TENANT_REGION,
      displayNameRu: '',
      displayNameUz: '',
      displayNameEn: '',
      displayNames: { kaa: 'Samarqand-kaa', en: 'Samarkand' },
    };
    await render({ list: vi.fn().mockResolvedValue([named]) }, 'ru');

    expect(host().querySelector('[data-testid="region-name"]')?.textContent?.trim()).toBe(
      'Samarkand',
    );
  });

  it('offers one name field per language of the tenant’s set, default first, and requires the default', async () => {
    const create = vi.fn().mockResolvedValue({ regionId: 'region-new', code: 'BUKHARA' });
    await render({
      list: vi.fn().mockResolvedValue([]),
      localeSet: () =>
        Promise.resolve({
          locales: ['uz-Latn', 'en'],
          defaultLocale: 'uz-Latn',
          configured: true,
        }),
      create,
    });

    host().querySelector<HTMLButtonElement>('.regions__create')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="region-name-uz-Latn"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="region-name-en"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="region-name-ru"]')).toBeNull();

    const type = (testid: string, value: string): void => {
      const input = host().querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };
    type('region-code', 'bukhara');
    // English alone is not enough: the default language (uz-Latn) is the one name required.
    type('region-name-en', 'Bukhara');
    expect(host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.disabled).toBe(
      true,
    );

    type('region-name-uz-Latn', 'Buxoro');
    host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
    await flushMicrotasks();

    expect(create).toHaveBeenCalledTimes(1);
    const body = create.mock.calls[0][1];
    expect(body.displayNames).toEqual({ 'uz-Latn': 'Buxoro', en: 'Bukhara' });
    // The contract keeps the platform triple required: ru is not offered, so it takes the default's name.
    expect(body.displayNameRu).toBe('Buxoro');
    expect(body.displayNameUz).toBe('Buxoro');
    expect(body.displayNameEn).toBe('Bukhara');
  });

  it('never sends, blanks or deletes a language the tenant does not offer when it rewrites the region', async () => {
    const carrying: RegionResponse = {
      ...TENANT_REGION,
      displayNames: {
        ru: 'Самарканд',
        'uz-Latn': 'Samarqand',
        en: 'Samarkand',
        kaa: 'Samarqand-kaa',
      },
    };
    const update = vi.fn().mockResolvedValue(undefined);
    await render({
      list: vi.fn().mockResolvedValue([carrying]),
      localeSet: () =>
        Promise.resolve({ locales: ['ru', 'en'], defaultLocale: 'ru', configured: true }),
      update,
    });

    host().querySelector<HTMLButtonElement>('[data-testid="region-edit"]')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="region-name-uz-Latn"]')).toBeNull();
    expect(host().querySelector('[data-testid="region-name-kaa"]')).toBeNull();
    expect(host().querySelector('[data-testid="region-hidden-kept"]')).not.toBeNull();

    // Clearing an offered, non-default field is "leave it", not "delete it".
    const en = host().querySelector<HTMLInputElement>('[data-testid="region-name-en"]')!;
    en.value = '';
    en.dispatchEvent(new Event('input'));
    const ru = host().querySelector<HTMLInputElement>('[data-testid="region-name-ru"]')!;
    ru.value = 'Самарканд (город)';
    ru.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
    await flushMicrotasks();

    expect(update).toHaveBeenCalledTimes(1);
    const body = update.mock.calls[0][2];
    expect(body.displayNames).toEqual({ ru: 'Самарканд (город)' });
    expect(Object.keys(body.displayNames)).not.toContain('uz-Latn');
    expect(Object.keys(body.displayNames)).not.toContain('kaa');
    expect(Object.values(body.displayNames)).not.toContain('');
    // The contract keeps the platform triple required, so the columns still go back: the
    // edited one changed, the hidden uz-Latn and the cleared en exactly as the region has them.
    expect(body.displayNameRu).toBe('Самарканд (город)');
    expect(body.displayNameUz).toBe('Samarqand');
    expect(body.displayNameEn).toBe('Samarkand');
  });

  it('falls back to the platform triple when the tenant’s language set cannot be read', async () => {
    await render({
      list: vi.fn().mockResolvedValue([]),
      localeSet: () => Promise.reject(new Error('unreachable')),
    });

    host().querySelector<HTMLButtonElement>('.regions__create')!.click();
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="region-name-ru"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="region-name-uz-Latn"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="region-name-en"]')).not.toBeNull();
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

  // ------------------------------- row X.4: the bounding-box editor on Delivery -> Regions

  describe('the bounding-box editor (ADR 0145, row X.4)', () => {
    const settle = async (): Promise<void> => {
      await flushMicrotasks();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
    };

    const open = async (api: Partial<RegionsApi>): Promise<void> => {
      await render(api);
      host().querySelector<HTMLButtonElement>('[data-testid="region-edit"]')!.click();
      fixture.detectChanges();
      await settle();
    };

    const type = (testid: string, value: string): void => {
      const input = host().querySelector<HTMLInputElement>(`[data-testid="${testid}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };

    it('replaces the four typed numbers with the editor: the region’s box is on the map and in the editor’s own fields', async () => {
      await open({ list: vi.fn().mockResolvedValue([TENANT_REGION]) });

      expect(host().querySelector('[data-testid="q-bbox-editor"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="region-sw-lat"]')).toBeNull();
      expect(host().querySelector<HTMLInputElement>('[data-testid="q-bbox-sw-lat"]')!.value).toBe(
        '39.4',
      );
      expect(host().querySelector<HTMLInputElement>('[data-testid="q-bbox-ne-lon"]')!.value).toBe(
        '67.2',
      );
      expect(mapProvider.map.liveRectangles).toHaveLength(1);
      expect(mapProvider.map.liveRectangles[0].bounds).toEqual({
        southWest: { latitude: 39.4, longitude: 66.7 },
        northEast: { latitude: 39.9, longitude: 67.2 },
      });
      // The centre stays a pair of plain inputs.
      expect(host().querySelector('[data-testid="region-centre-lat"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="region-centre-lon"]')).not.toBeNull();
    });

    it('sends the box a person dragged on the map, not the one the form opened with', async () => {
      const update = vi.fn().mockResolvedValue(undefined);
      await open({ list: vi.fn().mockResolvedValue([TENANT_REGION]), update });

      mapProvider.map.liveRectangles[0].simulateResize({
        southWest: { latitude: 39.3, longitude: 66.6 },
        northEast: { latitude: 40.0, longitude: 67.3 },
      });
      fixture.detectChanges();
      host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
      await flushMicrotasks();

      const body = update.mock.calls[0][2];
      expect([body.bboxSwLat, body.bboxSwLon, body.bboxNeLat, body.bboxNeLon]).toEqual([
        39.3, 66.6, 40.0, 67.3,
      ]);
      // Dragging the box does not move the centre the operator typed.
      expect([body.centreLat, body.centreLon]).toEqual([39.65, 66.96]);
    });

    it('moves the rectangle when a number is typed, and keeps the last good box when the typed one is upside down', async () => {
      const update = vi.fn().mockResolvedValue(undefined);
      await open({ list: vi.fn().mockResolvedValue([TENANT_REGION]), update });

      type('q-bbox-ne-lat', '39.95');
      expect(mapProvider.map.liveRectangles[0].bounds.northEast.latitude).toBe(39.95);

      // North-east south of the south-west corner: the editor says so and emits nothing.
      type('q-bbox-ne-lat', '39.0');
      expect(host().querySelector('[data-testid="q-bbox-problems"]')).not.toBeNull();
      host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
      await flushMicrotasks();

      expect(update.mock.calls[0][2].bboxNeLat).toBe(39.95);
    });

    it('still lets a box be typed when the map cannot be drawn', async () => {
      const create = vi.fn().mockResolvedValue({ regionId: 'region-new', code: 'KHIVA' });
      await render({ list: vi.fn().mockResolvedValue([]), create });
      mapProvider.loadError = new Error('network');
      host().querySelector<HTMLButtonElement>('.regions__create')!.click();
      fixture.detectChanges();
      await settle();

      type('region-code', 'khiva');
      type('region-name-ru', 'Хива');
      type('q-bbox-sw-lat', '41.2');
      type('q-bbox-sw-lon', '60.2');
      type('q-bbox-ne-lat', '41.5');
      type('q-bbox-ne-lon', '60.6');
      type('region-centre-lat', '41.38');
      type('region-centre-lon', '60.36');
      host().querySelector<HTMLButtonElement>('[data-testid="region-submit"]')!.click();
      await flushMicrotasks();

      const body = create.mock.calls[0][1];
      expect([body.bboxSwLat, body.bboxSwLon, body.bboxNeLat, body.bboxNeLon]).toEqual([
        41.2, 60.2, 41.5, 60.6,
      ]);
    });
  });
});
