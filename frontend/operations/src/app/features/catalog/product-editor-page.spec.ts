import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { ActivityLogApi } from '../staff/activity-log-api';
import { CatalogApi } from './catalog-api';
import { InventoryApi } from './inventory-api';
import { MediaApi } from './media-api';
import { PricingApi } from './pricing-api';
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

function configure(
  catalogApi: Partial<CatalogApi>,
  activityLogApi: Partial<ActivityLogApi> = {},
): void {
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
      {
        provide: CatalogApi,
        useValue: {
          validate: () => of(CLEAN_REPORT),
          variantsAtLocation: () => of([]),
          ...catalogApi,
        },
      },
      {
        provide: PricingApi,
        useValue: {
          resolvedVariantPrices: () => of({ priceBookId: null, currency: null, amountsMinor: {} }),
        },
      },
      { provide: MediaApi, useValue: {} },
      { provide: InventoryApi, useValue: {} },
      {
        provide: ActivityLogApi,
        useValue: {
          search: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
          ...activityLogApi,
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
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

    expect(host.querySelector('.editor__table')?.textContent).toContain('PLOV-1');
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
});
