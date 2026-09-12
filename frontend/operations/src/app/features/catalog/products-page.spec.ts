import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
  // `q-data-table`'s saved views and persisted filters live in real
  // `localStorage`, keyed by the static `viewId` — clear it so one test's
  // saved view or persisted tab never leaks into the next.
  beforeEach(() => {
    window.localStorage.clear();
  });

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

    const rows = harness.routeNativeElement!.querySelectorAll('[data-testid="dt-row"]');
    expect(rows.length).toBe(2);
  });

  it('pages through the cursor via q-data-table’s load-more, appending to the loaded rows', async () => {
    const listProducts = vi
      .fn()
      .mockReturnValueOnce(
        of({ items: [product({ productId: 'p1', name: 'Плов' })], nextCursor: 'cursor-1' }),
      )
      .mockReturnValueOnce(
        of({ items: [product({ productId: 'p2', name: 'Лагман' })], nextCursor: null }),
      );
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    expect(host.querySelectorAll('[data-testid="dt-row"]').length).toBe(1);
    const loadMore = host.querySelector('[data-testid="dt-load-more"]') as HTMLButtonElement;
    expect(loadMore).toBeTruthy();

    loadMore.click();
    await flushMicrotasks();

    expect(listProducts).toHaveBeenCalledTimes(2);
    expect(host.querySelectorAll('[data-testid="dt-row"]').length).toBe(2);
    expect(host.textContent).toContain('Лагман');
    expect(host.querySelector('[data-testid="dt-load-more"]')).toBeFalsy();
  });

  it('opens a product on a row click', async () => {
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({ items: [product({ productId: 'p1', name: 'Плов' })], nextCursor: null }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="dt-row"]') as HTMLElement).click();
    await flushMicrotasks();

    expect(TestBed.inject(Router).url).toBe('/catalog/products/p1');
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

  // --------------------------------------------------- q-data-table migration: saved views (X.18)

  it('binds the tab and search to q-data-table’s filters, so saving and re-applying a view actually changes the visible rows', async () => {
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({
          items: [
            product({ productId: 'p1', name: 'Плов', status: 'ACTIVE' }),
            product({ productId: 'p2', name: 'Лагман', status: 'DRAFT' }),
          ],
          nextCursor: null,
        }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    const tabs = () => [...host.querySelectorAll('.tab')] as HTMLButtonElement[];

    // Switch to "Draft" and save that as a view.
    tabs()[2].click();
    harness.detectChanges();
    expect(host.textContent).toContain('Лагман');
    expect(host.textContent).not.toContain('Плов');

    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();
    const nameInput = host.querySelector('[data-testid="dt-view-name-input"]') as HTMLInputElement;
    nameInput.value = 'Черновики';
    nameInput.dispatchEvent(new Event('input'));
    harness.detectChanges();
    (host.querySelector('[data-testid="dt-view-save"]') as HTMLButtonElement).click();
    harness.detectChanges();
    // Close the views menu, the way an operator would before moving on.
    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();

    // Back to "All" — both products are visible again.
    tabs()[0].click();
    harness.detectChanges();
    expect(host.textContent).toContain('Плов');
    expect(host.textContent).toContain('Лагман');

    // Re-open the views menu and apply the saved view: this must change the
    // actual rendered rows, not just flip a signal nothing reads.
    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (host.querySelector('.q-data-table__view-apply') as HTMLButtonElement).click();
    harness.detectChanges();

    expect(host.textContent).toContain('Лагман');
    expect(host.textContent).not.toContain('Плов');
  });
});
