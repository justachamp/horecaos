import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { MediaUploader } from '../../shared/ui/media-uploader';
import { CapacityApi } from '../kitchen/capacity-api';
import { ActivityLogApi } from '../staff/activity-log-api';
import { CatalogApi } from './catalog-api';
import { InventoryApi } from './inventory-api';
import { MediaApi } from './media-api';
import { PricingApi } from './pricing-api';
import { ProductCommentPresetsApi } from './product-comment-presets-api';
import { CommentPresetsApi } from '../settings/comment-presets/comment-presets-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { ProductDetail, ValidationReport } from './catalog-domain';
import { ProductEditorPage } from './product-editor-page';

const BRAND_SCOPE = { tenantId: 't1', brandId: 'b1' };
const LOCATION_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const CLEAN_REPORT: ValidationReport = { publishable: true, findings: [] };

function productDetail(overrides: Partial<ProductDetail> = {}): ProductDetail {
  return {
    productId: 'product-1',
    code: 'PLOV',
    status: 'ACTIVE',
    version: 1,
    translations: { ru: { name: 'Плов', description: null } },
    catalogIds: ['catalog-1'],
    categoryIds: [],
    variants: [
      {
        variantId: 'variant-1',
        sku: 'PLOV-1',
        unitCode: 'PIECE',
        isDefault: true,
        sortOrder: 0,
        status: 'ACTIVE',
        version: 1,
        translations: { ru: { name: 'Плов, порция' } },
        fiscal: null,
      },
    ],
    modifierGroups: [],
    media: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** Row 10.12: a brand's resolved locale set, defaulting to the platform's own fallback triple — same fake `location-detail-pane.spec.ts`/`categories-page.spec.ts` use. */
class FakeLocaleSet {
  readonly locales = signal<readonly Locale[]>(['ru', 'uz-Latn', 'en']);
  readonly defaultLocale = signal<Locale>('ru');
  readonly isConfigured = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
  supports(locale: Locale): boolean {
    return this.locales().includes(locale);
  }
}

function configure(
  catalogApi: Partial<CatalogApi>,
  activityLogApi: Partial<ActivityLogApi> = {},
  mediaApi: Partial<MediaApi> = {},
  capacityApi: Partial<CapacityApi> = {},
  productCommentPresetsApi: Partial<ProductCommentPresetsApi> = {},
  commentPresetsApi: Partial<CommentPresetsApi> = {},
  salesChannelsApi: Partial<SalesChannelsApi> = {},
  inventoryApi: Partial<InventoryApi> = {},
  localeSet: FakeLocaleSet = new FakeLocaleSet(),
): FakeLocaleSet {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'catalog/products/:productId', component: ProductEditorPage }]),
      {
        provide: CurrentBrand,
        useValue: {
          scope: signal(BRAND_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(LOCATION_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: LocaleSet, useValue: localeSet },
      {
        provide: CatalogApi,
        useValue: {
          validate: () => of(CLEAN_REPORT),
          variantsAtLocation: () => of({ items: [], nextCursor: null }),
          itemSaleSchedule: () => of({ windows: [] }),
          listRecommendations: () => of({ items: [] }),
          effectiveRecommendations: () => of({ items: [] }),
          ...catalogApi,
        },
      },
      {
        provide: PricingApi,
        useValue: {
          resolvedVariantPrices: () => of({ priceBookId: null, currency: null, amountsMinor: {} }),
        },
      },
      { provide: MediaApi, useValue: mediaApi },
      { provide: InventoryApi, useValue: inventoryApi },
      {
        provide: ActivityLogApi,
        useValue: {
          search: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
          ...activityLogApi,
        },
      },
      {
        provide: CapacityApi,
        useValue: {
          route: () => of({ ruleId: 'rule-1', layer: 'BRAND', version: 1 }),
          findRouting: () => Promise.resolve({ brandRule: null, locationRule: null }),
          ...capacityApi,
        },
      },
      {
        provide: ProductCommentPresetsApi,
        useValue: {
          list: () => of([]),
          ...productCommentPresetsApi,
        },
      },
      {
        provide: CommentPresetsApi,
        useValue: {
          list: () => Promise.resolve([]),
          ...commentPresetsApi,
        },
      },
      {
        provide: SalesChannelsApi,
        useValue: {
          list: () => Promise.resolve([]),
          ...salesChannelsApi,
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
  return localeSet;
}

describe('ProductEditorPage', () => {
  it('renders the product’s name in the editing locale once it loads', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('.editor__name')?.textContent).toContain(
      'Плов',
    );
  });

  it('marks uz — the server’s own default — for a brand with no locale set, even while the UI runs in ru', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    const localeGroup = harness.routeNativeElement!.querySelector(
      '[data-testid="editor-locale-group"]',
    )!;
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-uz"]')
        ?.querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).not.toBeNull();
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-ru"]')
        ?.querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).toBeNull();
  });

  it('marks the brand’s own default locale once it has chosen one — the locale the list screens now resolve', async () => {
    // Batch 14: CatalogQueryService resolves the products list in the brand's
    // default language, so the marker follows it (it used to stay on `uz`).
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['ru', 'uz-Latn', 'en']);
    localeSet.defaultLocale.set('ru');
    configure({ productDetail: () => of(productDetail()) }, {}, {}, {}, {}, {}, {}, {}, localeSet);

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    const localeGroup = harness.routeNativeElement!.querySelector(
      '[data-testid="editor-locale-group"]',
    )!;
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-ru"]')
        ?.querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).not.toBeNull();
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-uz"]')
        ?.querySelector('[data-testid="q-localized-field-group-default-marker"]'),
    ).toBeNull();
  });

  it('shows ru complete and uz/en incomplete, from the loaded translations', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    const localeGroup = harness.routeNativeElement!.querySelector(
      '[data-testid="editor-locale-group"]',
    )!;
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-ru"]')
        ?.querySelector('[data-testid="q-localized-field-group-complete"]'),
    ).not.toBeNull();
    expect(
      localeGroup
        .querySelector('[data-testid="q-localized-field-group-tab-uz"]')
        ?.querySelector('[data-testid="q-localized-field-group-incomplete"]'),
    ).not.toBeNull();
  });

  it('switches the editing locale from the locale group, updating the shown name', async () => {
    configure({
      productDetail: () =>
        of(
          productDetail({
            translations: {
              ru: { name: 'Плов', description: null },
              uz: { name: 'Osh', description: null },
            },
          }),
        ),
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="q-localized-field-group-tab-uz"]',
      ) as HTMLButtonElement
    ).click();
    harness.detectChanges();

    expect(harness.routeNativeElement!.querySelector('.editor__name')?.textContent).toContain(
      'Osh',
    );
  });

  it('opens on the brand’s own default locale, not the operator’s own console language', async () => {
    // Row 10.12's fix: the strip used to open on toCatalogLocale(i18n.locale())
    // — here, English — regardless of what the brand itself configured.
    const localeSet = new FakeLocaleSet();
    localeSet.locales.set(['en', 'ru']);
    localeSet.defaultLocale.set('en');
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              translations: {
                ru: { name: 'Плов', description: null },
                en: { name: 'Plov', description: null },
              },
            }),
          ),
      },
      {},
      {},
      {},
      {},
      {},
      {},
      {},
      localeSet,
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('.editor__name')?.textContent).toContain(
      'Plov',
    );
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-localized-field-group-tab-en"]'),
    ).not.toBeNull();
  });

  it('narrows the locale strip to the brand’s own supported set — nothing is forced in any more, the list screens read the brand’s default', async () => {
    const localeSet = new FakeLocaleSet();
    localeSet.isConfigured.set(true);
    localeSet.locales.set(['ru']);
    localeSet.defaultLocale.set('ru');
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              translations: {
                ru: { name: 'Плов', description: null },
                // Stored before the brand narrowed its set — no longer
                // shown as a tab, never touched by a save in another locale.
                en: { name: 'Plov', description: 'Uzbek pilaf' },
              },
            }),
          ),
      },
      {},
      {},
      {},
      {},
      {},
      {},
      {},
      localeSet,
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-localized-field-group-tab-ru"]'),
    ).not.toBeNull();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-localized-field-group-tab-uz"]'),
    ).toBeNull();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-localized-field-group-tab-en"]'),
    ).toBeNull();
  });

  it('never deletes a hidden locale’s stored translation when saving a locale the strip still shows', async () => {
    const localeSet = new FakeLocaleSet();
    localeSet.locales.set(['ru']);
    localeSet.defaultLocale.set('ru');
    const setTranslation = vi.fn().mockReturnValue(of(undefined));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              translations: {
                ru: { name: 'Плов', description: null },
                en: { name: 'Plov', description: 'Uzbek pilaf' },
              },
            }),
          ),
        setTranslation,
      },
      {},
      {},
      {},
      {},
      {},
      {},
      {},
      localeSet,
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const nameInput = host.querySelector('[data-testid="editor-name"]') as HTMLInputElement;
    nameInput.value = 'Плов узбекский';
    nameInput.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="editor-save-basic"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setTranslation).toHaveBeenCalledTimes(1);
    expect(setTranslation).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ locale: 'ru', name: 'Плов узбекский' }),
    );
    expect(setTranslation).not.toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ locale: 'en' }),
    );
  });

  it('resolves LocaleSet on load, so the locale strip never sticks on the platform fallback for a configured brand', async () => {
    const localeSet = new FakeLocaleSet();
    configure({ productDetail: () => of(productDetail()) }, {}, {}, {}, {}, {}, {}, {}, localeSet);

    await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(localeSet.ensureLoaded).toHaveBeenCalled();
  });

  it('renders the not-found panel on a 404 rather than a blank editor', async () => {
    configure({
      productDetail: () =>
        throwError(() => new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/products/missing');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.textContent).toContain('Этого товара больше нет');
  });

  it('renders the denied panel on a 403', async () => {
    configure({
      productDetail: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('[data-testid="editor-denied"]')).toBeTruthy();
  });

  it('shows the live readiness rail as clean when the catalog has no findings for this product', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.querySelector('.editor__rail-clean')).toBeTruthy();
  });

  it('switches tabs on click', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-tab-VARIANTS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(host.querySelector<HTMLInputElement>('[data-testid="editor-variant-name"]')?.value).toBe(
      'Плов, порция',
    );
  });

  it('renders the never-blur-authoring-and-availability publish result inline, not as a thrown error', async () => {
    configure({
      productDetail: () => of(productDetail()),
      publish: () =>
        of({
          publicationId: 'pub-1',
          status: 'REJECTED' as const,
          contentHash: 'abc',
          validation: {
            publishable: false,
            findings: [{ severity: 'BLOCKER' as const, code: 'VARIANT_HAS_NO_ACTIVE_PRICE' }],
          },
        }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-publish"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="publish-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="publish-dialog"]')?.textContent).toContain(
      'Публикация отклонена',
    );
    expect(host.querySelector('[data-testid="publish-dialog"]')?.textContent).toContain(
      'Нет активной цены',
    );
  });

  it('creates a modifier group with its first required option, then refreshes the library', async () => {
    const createModifierGroup = vi.fn().mockReturnValue(of({ id: 'group-1' }));
    const addModifierOption = vi.fn().mockReturnValue(of({ id: 'option-1' }));
    configure({
      productDetail: () => of(productDetail()),
      listModifierGroups: vi
        .fn()
        .mockReturnValueOnce(of([]))
        .mockReturnValueOnce(
          of([
            {
              groupId: 'group-1',
              code: 'SIZE',
              name: 'Размер',
              required: true,
              minimumSelections: 1,
              maximumSelections: 1,
              allowSameOptionMultipleTimes: false,
              optionCount: 1,
              status: 'ACTIVE',
            },
          ]),
        ),
      createModifierGroup,
      addModifierOption,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-tab-MODIFIERS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const set = (testId: string, value: string): void => {
      const input = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    set('editor-new-group-name', 'Размер');
    set('editor-new-group-code', 'SIZE');
    set('editor-new-group-option-name', 'Маленькая');
    set('editor-new-group-option-code', 'SMALL');
    await flushMicrotasks();

    (
      host.querySelector('[data-testid="editor-create-group-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createModifierGroup).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ code: 'SIZE', name: 'Размер', minimumSelections: 1 }),
    );
    expect(addModifierOption).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'group-1',
      expect.objectContaining({ code: 'SMALL', name: 'Маленькая' }),
    );
    expect(host.querySelector('.editor__modifiers')?.textContent).toContain('Размер');
  });

  it('shows this product’s own availability history, filtered from the location’s audit search', async () => {
    const search = vi.fn().mockResolvedValue({
      items: [
        {
          id: 'evt-1',
          recordedAt: '2026-09-01T10:00:00Z',
          tenantId: 't1',
          auditClass: 'BUSINESS',
          actionCode: 'catalog.offering.set',
          actorType: 'USER',
          actorSubject: 'manager-1',
          actorDisplay: null,
          scopeType: 'LOCATION',
          scopeId: 'l1',
          targetType: 'LocationOffering',
          targetId: 'variant-1',
          outcome: 'SUCCEEDED',
          reason: 'Set variant availability to UNAVAILABLE',
          capabilityUsed: 'offering.manage',
          approvalRequestId: null,
          correlationId: 'corr-1',
          occurredAt: '2026-09-01T10:00:00Z',
        },
        // A different product's variant at the same location — must not leak in.
        {
          id: 'evt-2',
          recordedAt: '2026-09-01T09:00:00Z',
          tenantId: 't1',
          auditClass: 'BUSINESS',
          actionCode: 'catalog.offering.set',
          actorType: 'USER',
          actorSubject: 'manager-1',
          actorDisplay: null,
          scopeType: 'LOCATION',
          scopeId: 'l1',
          targetType: 'LocationOffering',
          targetId: 'variant-of-another-product',
          outcome: 'SUCCEEDED',
          reason: 'Set variant availability to AVAILABLE',
          capabilityUsed: 'offering.manage',
          approvalRequestId: null,
          correlationId: 'corr-2',
          occurredAt: '2026-09-01T09:00:00Z',
        },
      ],
      nextCursor: null,
    });
    configure({ productDetail: () => of(productDetail()) }, { search });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-tab-HISTORY"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(search).toHaveBeenCalledWith('t1', {
      actionCode: 'catalog.offering.set',
      scopeType: 'LOCATION',
      scopeId: 'l1',
      limit: 200,
    });
    const text = host.querySelector('.editor__section')?.textContent ?? '';
    expect(text).toContain('Плов, порция');
    expect(text).toContain('Set variant availability to UNAVAILABLE');
    expect(text).not.toContain('variant-of-another-product');
  });

  it('shows "not listed at N branches" and lists everywhere on click (gap map row 4.1)', async () => {
    const unlistedLocations = vi.fn().mockReturnValue(of(['l2', 'l3']));
    const backfillVariantListing = vi
      .fn()
      .mockReturnValue(of({ candidateCount: 2, listedCount: 2 }));
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      {},
      {},
      {},
      {},
      {},
      { unlistedLocations, backfillVariantListing },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-tab-AVAILABILITY"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    expect(unlistedLocations).toHaveBeenCalledWith(LOCATION_SCOPE, 'variant-1');
    const banner = host.querySelector('[data-testid="editor-unlisted-branches-banner"]');
    expect(banner?.textContent).toContain('2');

    // A second call, with none left unlisted, hides the banner.
    unlistedLocations.mockReturnValue(of([]));
    (
      host.querySelector(
        '[data-testid="editor-list-missing-branches-variant-1"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    harness.detectChanges();

    expect(backfillVariantListing).toHaveBeenCalledWith(LOCATION_SCOPE, 'variant-1');
    expect(host.querySelector('[data-testid="editor-unlisted-branches-banner"]')).toBeFalsy();
  });

  describe('what "list at every branch" reports back', () => {
    async function clickListEverywhere(inventoryApi: Partial<InventoryApi>) {
      configure({ productDetail: () => of(productDetail()) }, {}, {}, {}, {}, {}, {}, inventoryApi);
      const harness = await RouterTestingHarness.create('/catalog/products/product-1');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;
      (host.querySelector('[data-testid="editor-tab-AVAILABILITY"]') as HTMLButtonElement).click();
      await flushMicrotasks();
      harness.detectChanges();
      (
        host.querySelector(
          '[data-testid="editor-list-missing-branches-variant-1"]',
        ) as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      harness.detectChanges();
      return host;
    }

    it('says so when only some branches were listed, and keeps what is still missing', async () => {
      const unlistedLocations = vi
        .fn()
        .mockReturnValueOnce(of(['l2', 'l3', 'l4']))
        .mockReturnValueOnce(of(['l3', 'l4']));
      const host = await clickListEverywhere({
        unlistedLocations,
        backfillVariantListing: vi.fn().mockReturnValue(of({ candidateCount: 3, listedCount: 1 })),
      });

      const row = host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]');
      expect(row?.textContent).toContain('2');
      const notice = host.querySelector('[data-testid="editor-listing-notice-variant-1"]');
      expect(notice?.textContent).toContain('1');
      expect(notice?.textContent).toContain('3');
    });

    it('keeps the row, with a notice, when the count cannot be re-read afterwards', async () => {
      const unlistedLocations = vi
        .fn()
        .mockReturnValueOnce(of(['l2', 'l3']))
        .mockReturnValueOnce(throwError(() => new Error('503')));
      const host = await clickListEverywhere({
        unlistedLocations,
        backfillVariantListing: vi.fn().mockReturnValue(of({ candidateCount: 2, listedCount: 2 })),
      });

      // A failed re-read is not "nothing left": the banner must not claim every branch is listed.
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]')).toBeTruthy();
      expect(host.querySelector('[data-testid="editor-listing-notice-variant-1"]')).toBeTruthy();
    });

    it('shows no notice when everything was listed and the recount agrees', async () => {
      const unlistedLocations = vi
        .fn()
        .mockReturnValueOnce(of(['l2']))
        .mockReturnValueOnce(of([]));
      const host = await clickListEverywhere({
        unlistedLocations,
        backfillVariantListing: vi.fn().mockReturnValue(of({ candidateCount: 1, listedCount: 1 })),
      });

      expect(host.querySelector('[data-testid="editor-unlisted-branches-banner"]')).toBeFalsy();
      expect(host.querySelector('[data-testid="editor-listing-notice-variant-1"]')).toBeFalsy();
    });
  });

  // Batch 13 follow-up: the banner read only the product's default variant, so
  // a second variant offered at branches that never listed it stayed silent.
  describe('every variant, not only the default one', () => {
    function twoVariants(): ProductDetail {
      const base = productDetail();
      return productDetail({
        variants: [
          ...base.variants,
          {
            variantId: 'variant-2',
            sku: 'PLOV-2',
            unitCode: 'PIECE',
            isDefault: false,
            sortOrder: 1,
            status: 'ACTIVE',
            version: 1,
            translations: { ru: { name: 'Плов, двойная порция' } },
            fiscal: null,
          },
        ],
      });
    }

    async function openAvailability(
      inventoryApi: Partial<InventoryApi>,
      detail: ProductDetail = twoVariants(),
    ) {
      configure({ productDetail: () => of(detail) }, {}, {}, {}, {}, {}, {}, inventoryApi);
      const harness = await RouterTestingHarness.create('/catalog/products/product-1');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;
      (host.querySelector('[data-testid="editor-tab-AVAILABILITY"]') as HTMLButtonElement).click();
      await flushMicrotasks();
      harness.detectChanges();
      return { harness, host };
    }

    it('shows the banner for a non-default variant even when the default one is fully listed', async () => {
      const unlistedLocations = vi.fn((_scope: unknown, variantId: string) =>
        of(variantId === 'variant-2' ? ['l2', 'l3', 'l4'] : []),
      );
      const { host } = await openAvailability({ unlistedLocations });

      expect(unlistedLocations).toHaveBeenCalledWith(LOCATION_SCOPE, 'variant-1');
      expect(unlistedLocations).toHaveBeenCalledWith(LOCATION_SCOPE, 'variant-2');
      expect(host.querySelector('[data-testid="editor-unlisted-branches-banner"]')).toBeTruthy();
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]')).toBeFalsy();
      const row = host.querySelector('[data-testid="editor-unlisted-variant-variant-2"]');
      expect(row?.textContent).toContain('Плов, двойная порция');
      expect(row?.textContent).toContain('3');
    });

    it('lists one variant only, and leaves the other variant’s row in place', async () => {
      const remaining: Record<string, string[]> = {
        'variant-1': ['l2', 'l3'],
        'variant-2': ['l2'],
      };
      const unlistedLocations = vi.fn((_scope: unknown, variantId: string) =>
        of(remaining[variantId] ?? []),
      );
      const backfillVariantListing = vi.fn((_scope: unknown, variantId: string) => {
        remaining[variantId] = [];
        return of({ candidateCount: 1, listedCount: 1 });
      });
      const { harness, host } = await openAvailability({
        unlistedLocations,
        backfillVariantListing,
      });

      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]')).toBeTruthy();
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-2"]')).toBeTruthy();

      (
        host.querySelector(
          '[data-testid="editor-list-missing-branches-variant-2"]',
        ) as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      harness.detectChanges();

      expect(backfillVariantListing).toHaveBeenCalledTimes(1);
      expect(backfillVariantListing).toHaveBeenCalledWith(LOCATION_SCOPE, 'variant-2');
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-2"]')).toBeFalsy();
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]')).toBeTruthy();
      expect(host.querySelector('[data-testid="editor-unlisted-branches-banner"]')).toBeTruthy();
    });

    it('keeps the other variants’ rows when one variant’s read fails', async () => {
      const unlistedLocations = vi.fn((_scope: unknown, variantId: string) =>
        variantId === 'variant-1' ? throwError(() => new Error('boom')) : of(['l2']),
      );
      const { host } = await openAvailability({ unlistedLocations });

      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-1"]')).toBeFalsy();
      expect(host.querySelector('[data-testid="editor-unlisted-variant-variant-2"]')).toBeTruthy();
    });

    it('disables only the variant being listed while its request is in flight', async () => {
      const pending = new Subject<{ candidateCount: number; listedCount: number }>();
      const unlistedLocations = vi.fn().mockReturnValue(of(['l2']));
      const backfillVariantListing = vi.fn().mockReturnValue(pending);
      const { harness, host } = await openAvailability({
        unlistedLocations,
        backfillVariantListing,
      });

      const first = host.querySelector(
        '[data-testid="editor-list-missing-branches-variant-1"]',
      ) as HTMLButtonElement;
      const second = host.querySelector(
        '[data-testid="editor-list-missing-branches-variant-2"]',
      ) as HTMLButtonElement;
      first.click();
      await flushMicrotasks();
      harness.detectChanges();

      expect(first.disabled).toBe(true);
      expect(second.disabled).toBe(false);

      // A second click on the in-flight variant is swallowed, not a second request.
      first.click();
      expect(backfillVariantListing).toHaveBeenCalledTimes(1);

      unlistedLocations.mockReturnValue(of([]));
      pending.next({ candidateCount: 1, listedCount: 1 });
      pending.complete();
      await flushMicrotasks();
    });
  });

  it('never shows the "not listed" banner once every branch is already listed', async () => {
    const unlistedLocations = vi.fn().mockReturnValue(of([]));
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      {},
      {},
      {},
      {},
      {},
      { unlistedLocations },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-tab-AVAILABILITY"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    expect(host.querySelector('[data-testid="editor-unlisted-branches-banner"]')).toBeFalsy();
  });

  it('sends sku, unitCode and name when adding a variant — AddVariantRequest always accepted them', async () => {
    const addVariant = vi.fn().mockReturnValue(of({ id: 'variant-2' }));
    const secondLoad = productDetail({
      variants: [
        ...productDetail().variants,
        {
          variantId: 'variant-2',
          sku: 'PLOV-2',
          unitCode: 'KG',
          isDefault: false,
          sortOrder: 1,
          status: 'ACTIVE',
          version: 1,
          translations: { ru: { name: 'Плов, кг' } },
          fiscal: null,
        },
      ],
    });
    configure({
      productDetail: vi
        .fn()
        .mockReturnValueOnce(of(productDetail()))
        .mockReturnValueOnce(of(secondLoad)),
      addVariant,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-VARIANTS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const set = (testId: string, value: string): void => {
      const input = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    set('editor-new-variant-name', 'Плов, кг');
    set('editor-new-variant-sku', 'PLOV-2');
    set('editor-new-variant-unit', 'KG');
    (host.querySelector('[data-testid="editor-add-variant"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(addVariant).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'product-1',
      expect.objectContaining({ sku: 'PLOV-2', unitCode: 'KG', name: 'Плов, кг' }),
    );
  });

  it('changes a product’s own status — read-only text until this wave', async () => {
    const setProductStatus = vi.fn().mockReturnValue(of(undefined));
    configure({ productDetail: () => of(productDetail()), setProductStatus });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const select = host.querySelector('[data-testid="editor-status-select"]') as HTMLSelectElement;
    select.value = 'ARCHIVED';
    select.dispatchEvent(new Event('change'));
    (host.querySelector('[data-testid="editor-save-status"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setProductStatus).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', 'ARCHIVED');
  });

  it('removes a product from a category — the undo placeInCategory never had', async () => {
    const removeProductFromCategory = vi.fn().mockReturnValue(of(undefined));
    configure({
      productDetail: () => of(productDetail({ categoryIds: ['cat-1'] })),
      removeProductFromCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-remove-category"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(removeProductFromCategory).toHaveBeenCalledWith(BRAND_SCOPE, 'cat-1', 'product-1');
    expect(host.querySelector('[data-testid="editor-remove-category"]')).toBeNull();
  });

  it('removes a product from a catalog', async () => {
    const removeProductFromCatalog = vi.fn().mockReturnValue(of(undefined));
    configure({ productDetail: () => of(productDetail()), removeProductFromCatalog });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="editor-remove-catalog"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(removeProductFromCatalog).toHaveBeenCalledWith(BRAND_SCOPE, 'catalog-1', 'product-1');
  });

  it('renders a real thumbnail on the photo grid — the trap: it used to show a role label and no <img> at all', async () => {
    const downloadUrl = vi.fn().mockReturnValue(of('https://cdn.example/thumb.jpg'));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                { mediaAssetId: 'asset-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'ALL' },
              ],
            }),
          ),
      },
      {},
      { downloadUrl },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(downloadUrl).toHaveBeenCalledWith('t1', 'asset-1', 'THUMBNAIL');
    const img = host.querySelector<HTMLImageElement>('.editor__photo-image');
    expect(img?.src).toBe('https://cdn.example/thumb.jpg');
  });

  it('detaches a photo — the undo attachMedia never had, at any layer', async () => {
    const detachMedia = vi.fn().mockReturnValue(of(undefined));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                { mediaAssetId: 'asset-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'ALL' },
              ],
            }),
          ),
        detachMedia,
      },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    (host.querySelector('[data-testid="editor-photo-detach"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(detachMedia).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'PRODUCT',
      'product-1',
      'asset-1',
      'PRIMARY',
      'ALL',
    );
    expect(host.querySelector('[data-testid="editor-photo-tile"]')).toBeNull();
  });

  function channel(overrides: Partial<ChannelView> = {}): ChannelView {
    return {
      id: 'channel-1',
      code: 'UZUM',
      systemType: 'AGGREGATOR',
      displayName: 'Uzum Tezkor',
      status: 'ACTIVE',
      pricePlaneChannelId: null,
      externallyPriced: true,
      guestOrdersAllowed: true,
      providerInstallationId: null,
      version: 1,
      locationCount: 1,
      enabledPaymentMethodCount: 1,
      enabledFulfillmentModes: ['DELIVERY'],
      ...overrides,
    };
  }

  it('lists the tenant’s active sales channels as the photo picker’s own options — gap map row 4.2f', async () => {
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
      {},
      {},
      {},
      {
        list: () =>
          Promise.resolve([
            channel(),
            channel({ id: 'c2', code: 'YANDEX', displayName: 'Yandex Eda' }),
          ]),
      },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const options = Array.from(
      host.querySelectorAll('[data-testid="editor-photo-channel-select"] option'),
    ).map((option) => option.textContent?.trim());
    expect(options).toEqual(['Универсальное (все каналы)', 'Uzum Tezkor', 'Yandex Eda']);
  });

  it('an archived or inactive channel is not offered on the photo picker', async () => {
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
      {},
      {},
      {},
      {
        list: () =>
          Promise.resolve([
            channel(),
            channel({ id: 'c2', code: 'GONE', displayName: 'Retired channel', status: 'ARCHIVED' }),
          ]),
      },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const options = Array.from(
      host.querySelectorAll('[data-testid="editor-photo-channel-select"] option'),
    ).map((option) => option.textContent?.trim());
    expect(options).toEqual(['Универсальное (все каналы)', 'Uzum Tezkor']);
  });

  it('choosing a channel scopes the grid to that channel’s own override gallery, gap map row 4.2f', async () => {
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                {
                  mediaAssetId: 'asset-universal',
                  role: 'PRIMARY',
                  sortOrder: 0,
                  channelCode: 'ALL',
                },
                { mediaAssetId: 'asset-uzum', role: 'PRIMARY', sortOrder: 0, channelCode: 'UZUM' },
              ],
            }),
          ),
      },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
      {},
      {},
      {},
      { list: () => Promise.resolve([channel()]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // The default selection (universal) shows only the universal photo.
    expect(host.querySelectorAll('[data-testid="editor-photo-tile"]').length).toBe(1);

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="editor-photo-channel-select"]',
    )!;
    select.value = 'UZUM';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    const tiles = host.querySelectorAll('[data-testid="editor-photo-tile"]');
    expect(tiles.length).toBe(1);
    expect(host.querySelector('.editor__photo-image')?.getAttribute('src')).toBe(
      'https://cdn.example/thumb.jpg',
    );
  });

  it('uploads to the channel selected above — not the universal gallery — when one is picked', async () => {
    const attachMedia = vi.fn().mockReturnValue(of(undefined));
    const upload = vi.fn().mockReturnValue(of({ assetId: 'asset-new', status: 'AVAILABLE' }));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                {
                  mediaAssetId: 'asset-universal',
                  role: 'PRIMARY',
                  sortOrder: 0,
                  channelCode: 'ALL',
                },
              ],
            }),
          ),
        attachMedia,
      },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg'), upload },
      {},
      {},
      {},
      { list: () => Promise.resolve([channel()]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="editor-photo-channel-select"]',
    )!;
    select.value = 'UZUM';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    const uploader = harness.routeDebugElement!.query(By.directive(MediaUploader))
      .componentInstance as MediaUploader;
    uploader.selected.emit(new File(['x'], 'a.jpg', { type: 'image/jpeg' }));
    await flushMicrotasks();

    expect(attachMedia).toHaveBeenCalledWith(BRAND_SCOPE, 'PRODUCT', 'product-1', 'asset-new', {
      role: 'PRIMARY',
      sortOrder: 0,
      channel: 'UZUM',
    });
    // UZUM's own gallery was empty, so its first photo is PRIMARY, independent
    // of the universal gallery already having one.
    expect(host.querySelectorAll('[data-testid="editor-photo-tile"]').length).toBe(1);
  });

  it('reordering one channel’s own gallery never re-attaches a different channel’s photo', async () => {
    const attachMedia = vi.fn().mockReturnValue(of(undefined));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                { mediaAssetId: 'universal-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'ALL' },
                { mediaAssetId: 'uzum-1', role: 'PRIMARY', sortOrder: 0, channelCode: 'UZUM' },
                { mediaAssetId: 'uzum-2', role: 'GALLERY', sortOrder: 1, channelCode: 'UZUM' },
              ],
            }),
          ),
        attachMedia,
      },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
      {},
      {},
      {},
      { list: () => Promise.resolve([channel()]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="editor-photo-channel-select"]',
    )!;
    select.value = 'UZUM';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    (host.querySelector('[data-testid="editor-photo-move-down"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // Only UZUM's two photos are re-attached — the universal photo, which
    // was never part of this gallery, is left completely untouched.
    expect(attachMedia).toHaveBeenCalledTimes(2);
    expect(attachMedia).not.toHaveBeenCalledWith(
      BRAND_SCOPE,
      'PRODUCT',
      'product-1',
      'universal-1',
      expect.anything(),
    );
  });

  it('detaching one channel’s override leaves another channel’s relation for the same asset and role alone', async () => {
    // The same photo re-uploaded verbatim under two different channels would
    // collide on (mediaAssetId, role) alone — exactly what catalog.media_relations'
    // own primary key since V0223 says is two distinct rows, not one. Detaching
    // the universal copy must not also remove UZUM's.
    const detachMedia = vi.fn().mockReturnValue(of(undefined));
    configure(
      {
        productDetail: () =>
          of(
            productDetail({
              media: [
                { mediaAssetId: 'shared-asset', role: 'PRIMARY', sortOrder: 0, channelCode: 'ALL' },
                {
                  mediaAssetId: 'shared-asset',
                  role: 'PRIMARY',
                  sortOrder: 0,
                  channelCode: 'UZUM',
                },
              ],
            }),
          ),
        detachMedia,
      },
      {},
      { downloadUrl: () => of('https://cdn.example/thumb.jpg') },
      {},
      {},
      {},
      { list: () => Promise.resolve([channel()]) },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-PHOTOS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // The default (universal) selection shows exactly the ALL relation.
    (host.querySelector('[data-testid="editor-photo-detach"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(detachMedia).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'PRODUCT',
      'product-1',
      'shared-asset',
      'PRIMARY',
      'ALL',
    );
    expect(detachMedia).toHaveBeenCalledTimes(1);

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="editor-photo-channel-select"]',
    )!;
    select.value = 'UZUM';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    // UZUM's own relation for the very same asset and role survived the
    // universal one's detach — the pre-fix code matched on (mediaAssetId,
    // role) alone and would have dropped both from local state at once.
    expect(host.querySelectorAll('[data-testid="editor-photo-tile"]').length).toBe(1);
  });

  it('saves marking, excise, alcohol % and age gate — fields the domain always carried with no control anywhere', async () => {
    const classifyVariant = vi.fn().mockReturnValue(of(undefined));
    configure({ productDetail: () => of(productDetail()), classifyVariant });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-FISCAL"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const setValue = (selector: string, value: string): void => {
      const input = host.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    setValue('[data-testid="editor-fiscal-card"] input[type="number"][min="0"]', '42.5');
    const ageInput = host.querySelectorAll<HTMLInputElement>(
      '[data-testid="editor-fiscal-card"] input[type="number"]',
    )[2];
    ageInput.value = '18';
    ageInput.dispatchEvent(new Event('input'));
    const excisableBox = host.querySelectorAll<HTMLInputElement>(
      '[data-testid="editor-fiscal-card"] input[type="checkbox"]',
    )[1];
    excisableBox.checked = true;
    excisableBox.dispatchEvent(new Event('change'));

    (host.querySelector('[data-testid="editor-save-fiscal"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(classifyVariant).toHaveBeenCalledWith(
      BRAND_SCOPE,
      'variant-1',
      expect.objectContaining({
        excisable: true,
        alcoholByVolumeBp: 4250,
        ageRestrictionYears: 18,
      }),
    );
  });

  it('searches the ИКПУ/MXIK reference from the fiscal tab’s combobox', async () => {
    const searchMxikReference = vi.fn().mockReturnValue(
      of([
        {
          code: '01234',
          labelRu: 'Плов',
          labelUz: 'Osh',
          defaultPackageCodes: [],
          validFrom: '2020-01-01',
        },
      ]),
    );
    configure({ productDetail: () => of(productDetail()), searchMxikReference });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-FISCAL"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const combobox = host.querySelector(
      '[data-testid="editor-fiscal-mxik"] input',
    ) as HTMLInputElement;
    combobox.value = '01234';
    combobox.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 300));
    await flushMicrotasks();

    expect(searchMxikReference).toHaveBeenCalledWith(BRAND_SCOPE, '01234');
  });

  it('writes a brand-layer kitchen routing rule for this product — pure wiring over the existing endpoint', async () => {
    const route = vi.fn().mockReturnValue(of({ ruleId: 'rule-1', layer: 'BRAND', version: 1 }));
    configure({ productDetail: () => of(productDetail()) }, {}, {}, { route });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const select = host.querySelector(
      '[data-testid="editor-kitchen-role-select"]',
    ) as HTMLSelectElement;
    select.value = 'GRILL';
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();
    (host.querySelector('[data-testid="editor-save-kitchen-role"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(route).toHaveBeenCalledWith(LOCATION_SCOPE, {
      productId: 'product-1',
      stationRole: 'GRILL',
    });
  });

  it('gap map row 4.2g: shows a product’s already-routed department instead of an always-blank picker', async () => {
    const findRouting = vi.fn().mockResolvedValue({
      brandRule: { ruleId: 'rule-1', layer: 'BRAND', stationRole: 'GRILL', version: 3 },
      locationRule: null,
    });
    configure({ productDetail: () => of(productDetail()) }, {}, {}, { findRouting });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    harness.detectChanges();

    expect(findRouting).toHaveBeenCalledWith(LOCATION_SCOPE, { productId: 'product-1' });
    // The picker's own bound signal, not the native <select>'s DOM `.value` —
    // this is the unit under test (loadKitchenRouting prefilling the picker
    // from the read this row adds), independent of whether a bare `[value]`
    // binding on a plain <select> happens to repaint in this test runner's
    // DOM implementation once the option list already exists.
    const instance = harness.routeDebugElement!.componentInstance as unknown as {
      kitchenRoleSelection: () => string;
    };
    expect(instance.kitchenRoleSelection()).toBe('GRILL');
  });

  it(
    'gap map row 4.2g: changing an already-routed product’s department calls the update, not ' +
      'the create that used to 409 on a second save',
    async () => {
      const findRouting = vi.fn().mockResolvedValue({
        brandRule: { ruleId: 'rule-1', layer: 'BRAND', stationRole: 'GRILL', version: 3 },
        locationRule: null,
      });
      const route = vi.fn();
      const updateRoute = vi
        .fn()
        .mockReturnValue(of({ ruleId: 'rule-1', layer: 'BRAND', version: 4 }));
      configure(
        { productDetail: () => of(productDetail()) },
        {},
        {},
        { findRouting, route, updateRoute },
      );

      const harness = await RouterTestingHarness.create('/catalog/products/product-1');
      await flushMicrotasks();
      const host = harness.routeNativeElement!;

      const select = host.querySelector(
        '[data-testid="editor-kitchen-role-select"]',
      ) as HTMLSelectElement;
      select.value = 'COLD';
      select.dispatchEvent(new Event('change'));
      harness.detectChanges();
      (host.querySelector('[data-testid="editor-save-kitchen-role"]') as HTMLButtonElement).click();
      await flushMicrotasks();

      expect(updateRoute).toHaveBeenCalledWith(LOCATION_SCOPE, 'rule-1', {
        stationRole: 'COLD',
        expectedVersion: 3,
      });
      expect(route).not.toHaveBeenCalled();
    },
  );

  it('no longer shows the stale "not built" caption over the kitchen department picker', async () => {
    configure({ productDetail: () => of(productDetail()) });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.textContent).not.toContain('открытый вопрос ADR 0016');
  });

  it('loads the per-item sale schedule and saves a whole-set replace through q-schedule-grid', async () => {
    const itemSaleSchedule = vi
      .fn()
      .mockReturnValue(of({ windows: [{ dayOfWeek: 1, opensAt: '06:00', closesAt: '11:00' }] }));
    const replaceItemSaleSchedule = vi.fn().mockReturnValue(
      of({
        windows: [
          { dayOfWeek: 1, opensAt: '06:00', closesAt: '11:00' },
          { dayOfWeek: 2, opensAt: '06:00', closesAt: '11:00' },
        ],
      }),
    );
    configure({
      productDetail: () => of(productDetail()),
      itemSaleSchedule,
      replaceItemSaleSchedule,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-SCHEDULE"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(itemSaleSchedule).toHaveBeenCalledWith(BRAND_SCOPE, 'variant-1', 'l1');
    expect(host.querySelector('[data-testid="q-schedule-grid-row-1"]')?.textContent).toBeTruthy();

    (
      host.querySelector('[data-testid="q-schedule-grid-add-window-2"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="editor-save-schedule"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(replaceItemSaleSchedule).toHaveBeenCalledWith(BRAND_SCOPE, 'variant-1', 'l1', {
      windows: [
        { dayOfWeek: 1, opensAt: '06:00', closesAt: '11:00' },
        { dayOfWeek: 2, opensAt: '09:00', closesAt: '18:00' },
      ],
    });
  });

  it('attaches a recommendation, directional — the target must never appear as this product’s own source', async () => {
    const attachRecommendation = vi.fn().mockReturnValue(
      of({
        recommendationId: 'rec-1',
        targetVariantId: 'variant-9',
        targetProductName: null,
        sortOrder: 0,
      }),
    );
    const listRecommendations = vi
      .fn()
      .mockReturnValueOnce(of({ items: [] }))
      .mockReturnValueOnce(
        of({
          items: [
            {
              recommendationId: 'rec-1',
              targetVariantId: 'variant-9',
              targetProductName: 'Fries',
              sortOrder: 0,
            },
          ],
        }),
      );
    configure({
      productDetail: () => of(productDetail()),
      listRecommendations,
      attachRecommendation,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-RECOMMENDATIONS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const input = host.querySelector(
      '[data-testid="editor-recommendation-target"]',
    ) as HTMLInputElement;
    input.value = 'variant-9';
    input.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="editor-recommendation-attach"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(attachRecommendation).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', {
      targetVariantId: 'variant-9',
      sortOrder: 0,
    });
    expect(host.querySelector('.editor__table')?.textContent).toContain('Fries');
  });

  it('detaches a recommendation — idempotent, the undo the trap named', async () => {
    const detachRecommendation = vi.fn().mockReturnValue(of(undefined));
    configure({
      productDetail: () => of(productDetail()),
      listRecommendations: () =>
        of({
          items: [
            {
              recommendationId: 'rec-1',
              targetVariantId: 'variant-9',
              targetProductName: 'Fries',
              sortOrder: 0,
            },
          ],
        }),
      detachRecommendation,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-RECOMMENDATIONS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    (
      host.querySelector('[data-testid="editor-recommendation-detach"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(detachRecommendation).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', 'variant-9');
    expect(host.querySelector('[data-testid="editor-recommendation-detach"]')).toBeNull();
  });

  it('marks an attached target eligible only once GET .../recommendations/effective confirms it (fix4 P47: a real caller)', async () => {
    const effectiveRecommendations = vi.fn().mockReturnValue(
      of({
        items: [
          {
            recommendationId: 'rec-1',
            targetVariantId: 'variant-9',
            targetProductName: 'Fries',
            sortOrder: 0,
          },
        ],
      }),
    );
    configure({
      productDetail: () => of(productDetail()),
      listRecommendations: () =>
        of({
          items: [
            {
              recommendationId: 'rec-1',
              targetVariantId: 'variant-9',
              targetProductName: 'Fries',
              sortOrder: 0,
            },
            {
              recommendationId: 'rec-2',
              targetVariantId: 'variant-10',
              targetProductName: 'Cola',
              sortOrder: 1,
            },
          ],
        }),
      effectiveRecommendations,
    });

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-RECOMMENDATIONS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(effectiveRecommendations).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', 'l1');
    const eligibleCells = [
      ...host.querySelectorAll('[data-testid="editor-recommendation-eligible"]'),
    ];
    expect(eligibleCells).toHaveLength(2);
    // variant-9 came back from the effective read: eligible. variant-10 did
    // not (stopped, unpublished, or never offered at this location): not.
    // This suite runs in 'ru' (see configure()'s TestBed.inject(I18n).setLocale('ru')).
    expect(eligibleCells[0].textContent).toContain('Да');
    expect(eligibleCells[1].textContent).toContain('Пока нет');
  });

  // ---------------------------------------------- Row 2.1b — Preset comments

  it('attaches a preset comment picked from the tenant vocabulary, and never offers an already-attached one', async () => {
    const attach = vi.fn().mockReturnValue(
      of({
        presetId: 'preset-1',
        code: 'NO_ONION',
        labelRu: 'Без лука',
        labelUz: 'Piyozsiz',
        labelEn: 'No onion',
        posModifierCode: null,
        sortOrder: 0,
        status: 'ACTIVE',
      }),
    );
    const list = vi
      .fn()
      .mockReturnValueOnce(of([]))
      .mockReturnValueOnce(
        of([
          {
            presetId: 'preset-1',
            code: 'NO_ONION',
            labelRu: 'Без лука',
            labelUz: 'Piyozsiz',
            labelEn: 'No onion',
            posModifierCode: null,
            sortOrder: 0,
            status: 'ACTIVE',
          },
        ]),
      );
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      {},
      {},
      { list, attach },
      {
        list: () =>
          Promise.resolve([
            {
              presetId: 'preset-1',
              code: 'NO_ONION',
              labelRu: 'Без лука',
              labelUz: 'Piyozsiz',
              labelEn: 'No onion',
              labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onion' },
              posModifierCode: null,
              sortOrder: 0,
              status: 'ACTIVE',
              version: 1,
            },
            {
              presetId: 'preset-2',
              code: 'EXTRA_SPICY',
              labelRu: 'Поострее',
              labelUz: 'Achchiqroq',
              labelEn: 'Extra spicy',
              labels: { ru: 'Поострее', 'uz-Latn': 'Achchiqroq', en: 'Extra spicy' },
              posModifierCode: null,
              sortOrder: 1,
              status: 'ACTIVE',
              version: 1,
            },
          ]),
      },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-COMMENT_PRESETS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // preset-1 is already attached in the (second) list() response the
    // reload below will see — but at the moment the picker is first
    // rendered, nothing is attached yet, so both presets are offered.
    const options = [
      ...host.querySelectorAll('[data-testid="editor-comment-preset-picker"] option'),
    ] as HTMLOptionElement[];
    expect(options.map((o) => o.value)).toEqual(['', 'preset-1', 'preset-2']);

    const select = host.querySelector(
      '[data-testid="editor-comment-preset-picker"]',
    ) as HTMLSelectElement;
    select.value = 'preset-1';
    select.dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="editor-comment-preset-attach"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(attach).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', {
      presetId: 'preset-1',
      sortOrder: 0,
    });
    // After the reload, preset-1 is attached, so the picker offers only preset-2.
    const optionsAfter = [
      ...host.querySelectorAll('[data-testid="editor-comment-preset-picker"] option'),
    ] as HTMLOptionElement[];
    expect(optionsAfter.map((o) => o.value)).toEqual(['', 'preset-2']);
    expect(host.querySelector('.editor__table')?.textContent).toContain('Без лука');
  });

  it('detaches a preset comment — idempotent, and disappears from the management list immediately', async () => {
    const detach = vi.fn().mockReturnValue(of(undefined));
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      {},
      {},
      {
        list: () =>
          of([
            {
              presetId: 'preset-1',
              code: 'NO_ONION',
              labelRu: 'Без лука',
              labelUz: 'Piyozsiz',
              labelEn: 'No onion',
              posModifierCode: 'MOD-9',
              sortOrder: 0,
              status: 'ACTIVE',
            },
          ]),
        detach,
      },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-COMMENT_PRESETS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(
      host.querySelector('[data-testid="editor-comment-preset-pos-modifier"]')?.textContent,
    ).toContain('MOD-9');

    (
      host.querySelector('[data-testid="editor-comment-preset-detach"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(detach).toHaveBeenCalledWith(BRAND_SCOPE, 'product-1', 'preset-1');
    expect(host.querySelector('[data-testid="editor-comment-preset-detach"]')).toBeNull();
  });

  it('a caller refused catalog.author on attach sees the same inline notice recommendations use, not a thrown error', async () => {
    const attach = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      );
    configure(
      { productDetail: () => of(productDetail()) },
      {},
      {},
      {},
      { attach },
      {
        list: () =>
          Promise.resolve([
            {
              presetId: 'preset-1',
              code: 'NO_ONION',
              labelRu: 'Без лука',
              labelUz: 'Piyozsiz',
              labelEn: 'No onion',
              labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onion' },
              posModifierCode: null,
              sortOrder: 0,
              status: 'ACTIVE',
              version: 1,
            },
          ]),
      },
    );

    const harness = await RouterTestingHarness.create('/catalog/products/product-1');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="editor-tab-COMMENT_PRESETS"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const select = host.querySelector(
      '[data-testid="editor-comment-preset-picker"]',
    ) as HTMLSelectElement;
    select.value = 'preset-1';
    select.dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="editor-comment-preset-attach"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(host.querySelector('.editor__section')?.textContent).toContain(
      'У этой учётной записи нет прав на это действие.',
    );
  });
});
