import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CurrentBrand } from '../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { CatalogSummary, ProductSummary } from './catalog-domain';
import { ProductsPage } from './products-page';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1' };

const FAKE_CATALOGS: readonly CatalogSummary[] = [
  { catalogId: 'catalog-1', code: 'MAIN', name: 'Основной каталог', status: 'ACTIVE' },
];

function product(overrides: Partial<ProductSummary>): ProductSummary {
  return {
    productId: 'product-1',
    code: 'PLOV',
    status: 'ACTIVE',
    name: 'Плов',
    variantCount: 1,
    categoryNames: [],
    hasMxik: true,
    version: 1,
    ...overrides,
  };
}

/** See `order-queue.spec.ts`'s identical helper for why this is two chained zero-timeouts, not one. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(catalogApi: Partial<CatalogApi>): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'catalog/products', component: ProductsPage },
        // A destination for `submitCreate`'s post-creation navigate — without
        // this the router rejects unhandled once the test's own assertions
        // have already run, and vitest reports it as a stray failure.
        { path: 'catalog/products/:productId', component: ProductsPage },
      ]),
      {
        provide: CurrentBrand,
        useValue: {
          scope: signal(FAKE_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: CatalogApi, useValue: catalogApi },
    ],
  });
  TestBed.inject(I18n).setLocale('ru');
}

describe('ProductsPage', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('lists the brand’s products once the catalog and its products load', async () => {
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({
          items: [
            product({ productId: 'p1', name: 'Плов' }),
            product({ productId: 'p2', name: 'Лагман' }),
          ],
          nextCursor: null,
        }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();

    const rows = harness.routeNativeElement!.querySelectorAll('[data-testid="product-row"]');
    expect(rows.length).toBe(2);
  });

  it('renders the empty state naming the missing catalog when the brand has none yet', async () => {
    configure({ listCatalogs: () => of([]) });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();

    const empty = harness.routeNativeElement!.querySelector('[data-testid="products-empty"]');
    expect(empty?.textContent).toContain('У этого бренда пока нет каталога');
  });

  it('renders the denied state on a 403, not the empty table', async () => {
    configure({
      listCatalogs: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="products-denied"]'),
    ).toBeTruthy();
  });

  it('creates a product and reflects it going into the dialog’s request', async () => {
    const createProduct = vi
      .fn()
      .mockReturnValue(of({ productId: 'new-product', defaultVariantId: 'variant-1' }));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [], nextCursor: null }),
      createProduct,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="products-create"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const nameInput = host.querySelector(
      '[data-testid="create-product-dialog-name"]',
    ) as HTMLInputElement;
    const codeInput = host.querySelector(
      '[data-testid="create-product-dialog-code"]',
    ) as HTMLInputElement;
    nameInput.value = 'Плов';
    nameInput.dispatchEvent(new Event('input'));
    codeInput.value = 'PLOV';
    codeInput.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    (
      host.querySelector('[data-testid="create-product-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createProduct).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'catalog-1',
      expect.objectContaining({ code: 'PLOV', name: 'Плов' }),
    );
  });

  it('does not submit the create dialog while a required field is still empty', async () => {
    const createProduct = vi.fn().mockReturnValue(of({ productId: 'x', defaultVariantId: 'y' }));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [], nextCursor: null }),
      createProduct,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="products-create"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    (
      host.querySelector('[data-testid="create-product-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(createProduct).not.toHaveBeenCalled();
  });
});
