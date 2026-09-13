import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CursorState } from '../../core/api/page';
import { CurrentBrand } from '../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi, ProductListFilters } from './catalog-api';
import { CatalogSummary, CategorySummary, ProductSummary } from './catalog-domain';
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
    shareSlug: 'plov-abc12345',
    ...overrides,
  };
}

/** See `order-queue.spec.ts`'s identical helper for why this is two chained zero-timeouts, not one. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/**
 * A `listProducts` stub that actually applies `query`/`status` the way the
 * real server-side endpoint does — the point of these tests is to prove the
 * page asks the server to filter, not that a dumb mock echoes back whatever
 * it is handed. Read `.mock.calls.at(-1)![3]` for the filters a call was
 * made with.
 */
function serverFilteredListProducts(all: readonly ProductSummary[]) {
  return vi.fn(
    (
      _scope: BrandScope,
      _catalogId: string,
      _page: CursorState,
      filters: ProductListFilters = {},
    ) => {
      let items = all;
      if (filters.status === 'NO_MXIK') {
        items = items.filter((p) => !p.hasMxik);
      } else if (filters.status) {
        items = items.filter((p) => p.status === filters.status);
      }
      if (filters.query) {
        const q = filters.query.toLowerCase();
        items = items.filter(
          (p) => p.name.toLowerCase().includes(q) || p.code.toLowerCase().includes(q),
        );
      }
      return of({ items, nextCursor: null });
    },
  );
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

  // ------------------------------------------------- P21: server-side search and status tabs

  it('switching tabs asks the server for that status, rather than filtering the page already in hand', async () => {
    const listProducts = serverFilteredListProducts([
      product({ productId: 'p1', name: 'Плов', status: 'ACTIVE' }),
      product({ productId: 'p2', name: 'Лагман', status: 'DRAFT' }),
    ]);
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    const tabs = () => [...host.querySelectorAll('.tab')] as HTMLButtonElement[];

    tabs()[2].click(); // "Draft"
    await flushMicrotasks();
    harness.detectChanges();

    expect(host.textContent).toContain('Лагман');
    expect(host.textContent).not.toContain('Плов');
    const lastCall = listProducts.mock.calls.at(-1)!;
    expect(lastCall[3]).toMatchObject({ status: 'DRAFT' });
  });

  it('a debounced keystroke in the search box becomes a server query, not a client-side filter', async () => {
    vi.useFakeTimers();
    const listProducts = serverFilteredListProducts([
      product({ productId: 'p1', name: 'Плов', code: 'PLOV' }),
      product({ productId: 'p2', name: 'Лагман', code: 'LAGMAN' }),
    ]);
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await vi.advanceTimersByTimeAsync(0);
    const host = harness.routeNativeElement!;
    const search = host.querySelector('[data-testid="products-search"]') as HTMLInputElement;

    search.value = 'лаг';
    search.dispatchEvent(new Event('input'));
    // The box itself updates immediately, before the debounce lands.
    harness.detectChanges();
    expect(search.value).toBe('лаг');

    await vi.advanceTimersByTimeAsync(400);
    harness.detectChanges();

    const lastCall = listProducts.mock.calls.at(-1)!;
    expect(lastCall[3]).toMatchObject({ query: 'лаг' });
    expect(host.textContent).toContain('Лагман');
    expect(host.textContent).not.toContain('Плов');
    vi.useRealTimers();
  });

  it('applying a saved view re-fetches under that view’s tab and search, and updates the search box', async () => {
    const listProducts = serverFilteredListProducts([
      product({ productId: 'p1', name: 'Плов', status: 'ACTIVE' }),
      product({ productId: 'p2', name: 'Лагман', status: 'DRAFT' }),
    ]);
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    const tabs = () => [...host.querySelectorAll('.tab')] as HTMLButtonElement[];

    tabs()[2].click(); // "Draft"
    await flushMicrotasks();
    harness.detectChanges();

    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();
    const nameInput = host.querySelector('[data-testid="dt-view-name-input"]') as HTMLInputElement;
    nameInput.value = 'Черновики';
    nameInput.dispatchEvent(new Event('input'));
    harness.detectChanges();
    (host.querySelector('[data-testid="dt-view-save"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();

    tabs()[0].click(); // back to "All"
    await flushMicrotasks();
    harness.detectChanges();
    expect(host.textContent).toContain('Плов');
    expect(host.textContent).toContain('Лагман');

    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (host.querySelector('.q-data-table__view-apply') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    const lastCall = listProducts.mock.calls.at(-1)!;
    expect(lastCall[3]).toMatchObject({ status: 'DRAFT' });
    expect(host.textContent).toContain('Лагман');
    expect(host.textContent).not.toContain('Плов');
  });

  it('the NO_MXIK tab counts product-level hasMxik — a product with one classified and one unclassified variant is not in it', async () => {
    // The count this test asserts comes straight from the loaded, filtered
    // page (product-level), never from the fiscal workbench's node-level
    // "N of M priceable nodes unclassified" figure — the two must read as
    // different numbers, never the same one relabelled.
    const listProducts = serverFilteredListProducts([
      product({ productId: 'p1', name: 'Плов', hasMxik: false }),
      product({ productId: 'p2', name: 'Лагман', hasMxik: true }),
    ]);
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;
    const tabs = () => [...host.querySelectorAll('.tab')] as HTMLButtonElement[];

    tabs()[3].click(); // "No ИКПУ"
    await flushMicrotasks();
    harness.detectChanges();

    const lastCall = listProducts.mock.calls.at(-1)!;
    expect(lastCall[3]).toMatchObject({ status: 'NO_MXIK' });
    expect(host.textContent).toContain('Плов');
    expect(host.textContent).not.toContain('Лагман');
    expect(tabs()[3].textContent).toContain('1');
  });

  // ----------------------------------------------------------------- row actions

  it('duplicates a product and navigates to the copy', async () => {
    const duplicateProduct = vi
      .fn()
      .mockReturnValue(of({ productId: 'p1-copy', defaultVariantId: 'v1' }));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [product({ productId: 'p1' })], nextCursor: null }),
      duplicateProduct,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (
      host.querySelector('[data-testid="q-action-menu-item-duplicate"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(duplicateProduct).toHaveBeenCalledWith(FAKE_SCOPE, 'p1');
    expect(TestBed.inject(Router).url).toBe('/catalog/products/p1-copy');
  });

  it('archives a product and re-fetches so a status-filtered tab reflects the change', async () => {
    const setProductStatus = vi.fn().mockReturnValue(of(undefined));
    const listProducts = vi
      .fn()
      .mockReturnValue(
        of({ items: [product({ productId: 'p1', status: 'ACTIVE' })], nextCursor: null }),
      );
    configure({ listCatalogs: () => of(FAKE_CATALOGS), listProducts, setProductStatus });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (host.querySelector('[data-testid="q-action-menu-item-archive"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setProductStatus).toHaveBeenCalledWith(FAKE_SCOPE, 'p1', 'ARCHIVED');
    expect(listProducts).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('Товар архивирован');
  });

  it('stops a product in all branches and reports how many branches changed', async () => {
    const stopInAllBranches = vi.fn().mockReturnValue(of({ locationsChanged: 3 }));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [product({ productId: 'p1' })], nextCursor: null }),
      stopInAllBranches,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (
      host.querySelector(
        '[data-testid="q-action-menu-item-stopInAllBranches"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(stopInAllBranches).toHaveBeenCalledWith(FAKE_SCOPE, 'p1');
    expect(host.textContent).toContain('Остановлено в 3 филиалах');
  });

  it('copies the product id to the clipboard and shows a confirmation', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [product({ productId: 'p1' })], nextCursor: null }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (host.querySelector('[data-testid="q-action-menu-item-copyId"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(writeText).toHaveBeenCalledWith('p1');
    expect(host.textContent).toContain('ID скопирован');
  });

  it('copies the computed public share slug, distinct from the id', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({ items: [product({ productId: 'p1', shareSlug: 'plov-deadbeef' })], nextCursor: null }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (
      host.querySelector('[data-testid="q-action-menu-item-copyShareSlug"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(writeText).toHaveBeenCalledWith('plov-deadbeef');
    expect(host.textContent).toContain('Слаг скопирован');
  });

  it('adds a product to a category, defaulting the sort order to that category’s current product count', async () => {
    const listCategories = vi
      .fn()
      .mockReturnValue(
        of([
          {
            categoryId: 'cat-1',
            code: 'HOT',
            name: 'Горячее',
            sortOrder: 0,
            status: 'ACTIVE',
            productCount: 4,
          },
        ] satisfies CategorySummary[]),
      );
    const placeInCategory = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [product({ productId: 'p1' })], nextCursor: null }),
      listCategories,
      placeInCategory,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="q-action-menu-trigger"]') as HTMLButtonElement).click();
    harness.detectChanges();
    (
      host.querySelector('[data-testid="q-action-menu-item-addToCategory"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    harness.detectChanges();

    expect(listCategories).toHaveBeenCalledWith(FAKE_SCOPE, 'catalog-1');
    const select = host.querySelector(
      '[data-testid="add-to-category-dialog-select"]',
    ) as HTMLSelectElement;
    select.value = 'cat-1';
    select.dispatchEvent(new Event('change'));
    harness.detectChanges();
    (
      host.querySelector('[data-testid="add-to-category-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(placeInCategory).toHaveBeenCalledWith(FAKE_SCOPE, 'cat-1', 'p1', 4);
  });

  // --------------------------------------------------------------- bulk actions

  it('bulk-archives every selected product, one audited call per row', async () => {
    const setProductStatus = vi.fn().mockReturnValue(of(undefined));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({
          items: [product({ productId: 'p1' }), product({ productId: 'p2' })],
          nextCursor: null,
        }),
      setProductStatus,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const checkboxes = [
      ...host.querySelectorAll('[data-testid="dt-row-select"]'),
    ] as HTMLInputElement[];
    checkboxes.forEach((box) => {
      box.checked = true;
      box.dispatchEvent(new Event('change'));
    });
    harness.detectChanges();

    (host.querySelector('[data-testid="dt-bulk-action-archive"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(setProductStatus).toHaveBeenCalledWith(FAKE_SCOPE, 'p1', 'ARCHIVED');
    expect(setProductStatus).toHaveBeenCalledWith(FAKE_SCOPE, 'p2', 'ARCHIVED');
    expect(host.textContent).toContain('Обновлено строк: 2');
  });

  it('reports a partial failure when one of the bulk-selected rows fails, without losing the successful ones', async () => {
    const stopInAllBranches = vi
      .fn()
      .mockReturnValueOnce(of({ locationsChanged: 1 }))
      .mockReturnValueOnce(
        throwError(() => new ApiError(ApiErrorCode.VALIDATION_FAILED, 400, null, null)),
      );
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({
          items: [product({ productId: 'p1' }), product({ productId: 'p2' })],
          nextCursor: null,
        }),
      stopInAllBranches,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const checkboxes = [
      ...host.querySelectorAll('[data-testid="dt-row-select"]'),
    ] as HTMLInputElement[];
    checkboxes.forEach((box) => {
      box.checked = true;
      box.dispatchEvent(new Event('change'));
    });
    harness.detectChanges();

    (
      host.querySelector('[data-testid="dt-bulk-action-stopInAllBranches"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(stopInAllBranches).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('из выбранных строк не обновлено');
  });

  // ------------------------------------------------------------- fiscal workbench

  it('opens the fiscal workbench and shows the node-level coverage — a different number from the product-level NO_MXIK tab', async () => {
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () =>
        of({
          items: [product({ productId: 'p1', hasMxik: false })],
          nextCursor: null,
        }),
      fiscalCoverage: () =>
        of({
          totalNodes: 12,
          unclassifiedCount: 7,
          nodes: [
            {
              nodeType: 'VARIANT',
              nodeId: 'v1',
              name: 'Плов',
              categoryName: null,
              locationCount: 2,
            },
          ],
        }),
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    // Product-level: exactly one product currently lacks an ИКПУ.
    expect(host.textContent).toContain('Плов');

    (host.querySelector('[data-testid="products-workbench"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    const summary = host.querySelector('[data-testid="fiscal-workbench-summary"]');
    // Node-level: 7 of 12 — a different figure from the product-level count above.
    expect(summary?.textContent).toContain('7');
    expect(summary?.textContent).toContain('12');
  });

  it('bulk-classifies through the fiscal workbench’s grid in one call and refreshes the coverage', async () => {
    const bulkClassify = vi
      .fn()
      .mockReturnValue(
        of({ outcomes: [{ nodeType: 'VARIANT', nodeId: 'v1', status: 'CLASSIFIED' }] }),
      );
    const fiscalCoverage = vi
      .fn()
      .mockReturnValueOnce(
        of({
          totalNodes: 2,
          unclassifiedCount: 1,
          nodes: [
            {
              nodeType: 'VARIANT',
              nodeId: 'v1',
              name: 'Плов',
              categoryName: null,
              locationCount: 1,
            },
          ],
        }),
      )
      .mockReturnValueOnce(of({ totalNodes: 2, unclassifiedCount: 0, nodes: [] }));
    configure({
      listCatalogs: () => of(FAKE_CATALOGS),
      listProducts: () => of({ items: [], nextCursor: null }),
      fiscalCoverage,
      bulkClassify,
    });

    const harness = await RouterTestingHarness.create('/catalog/products');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="products-workbench"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    const cell = host.querySelector('[data-col="2"]') as HTMLElement; // mxikCode column
    cell.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    harness.detectChanges();
    cell.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    harness.detectChanges();
    const input = host.querySelector('[data-testid="dg-input"]') as HTMLInputElement;
    input.value = '10101001001000000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    harness.detectChanges();

    (host.querySelector('[data-testid="dg-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    harness.detectChanges();

    expect(bulkClassify).toHaveBeenCalledWith(
      FAKE_SCOPE,
      expect.arrayContaining([
        expect.objectContaining({
          nodeType: 'VARIANT',
          nodeId: 'v1',
          fiscal: expect.objectContaining({ mxikCode: '10101001001000000' }),
        }),
      ]),
    );
    expect(fiscalCoverage).toHaveBeenCalledTimes(2);
  });
});
