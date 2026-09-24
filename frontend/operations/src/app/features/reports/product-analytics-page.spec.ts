import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from '../catalog/catalog-api';
import { ProductAnalyticsPage } from './product-analytics-page';
import { ReportsFilterState } from './reports-filter-state';
import {
  ClassificationRowResponse,
  ClassificationRunResponse,
  ReportingApi,
  VariantSalesListResponse,
  VariantSalesRowResponse,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Reaches past `protected` to drive the component the same way every other report-page spec does. */
type Internals = {
  selectTab(tab: 'sales' | 'abc' | 'xyz'): void;
};

function provenance() {
  return {
    asOf: '2026-09-12T04:00:00Z',
    closedThrough: '2026-09-11',
    lastCloseCompletedAt: '2026-09-11T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [] as readonly string[],
    openDivergences: 0,
  };
}

function variantRow(overrides: Partial<VariantSalesRowResponse> = {}): VariantSalesRowResponse {
  return {
    variantId: 'variant-pizza',
    categoryId: 'category-mains',
    productName: 'Пицца Маргарита',
    totalQuantity: 10,
    totalGrossSom: 100_000,
    totalNetSom: 90_000,
    deliveryQuantity: 6,
    deliveryNetSom: 54_000,
    pickupQuantity: 4,
    pickupNetSom: 36_000,
    ...overrides,
  };
}

function salesResponse(rows: readonly VariantSalesRowResponse[]): VariantSalesListResponse {
  return { rows, maybeMore: false, provenance: provenance() };
}

function classificationRow(
  overrides: Partial<ClassificationRowResponse> = {},
): ClassificationRowResponse {
  return {
    variantId: 'variant-pizza',
    categoryId: 'category-mains',
    productName: 'Пицца Маргарита',
    revenueGrossSom: 750_000,
    revenueShareBasisPoints: 7_500,
    cumulativeShareBasisPoints: 7_500,
    abcClass: 'A',
    quantityTotal: 80,
    meanQuantityPerBucket: 20,
    stddevQuantityPerBucket: 0,
    coefficientOfVariationBasisPoints: 0,
    xyzClass: 'X',
    ...overrides,
  };
}

function classificationRun(rows: readonly ClassificationRowResponse[]): ClassificationRunResponse {
  return {
    runId: 'run-1',
    from: '2026-08-01',
    to: '2026-08-28',
    locationIds: [],
    metricCode: 'revenue.gross.v1',
    abcThresholdABasisPoints: 8_000,
    abcThresholdBBasisPoints: 9_500,
    xyzThresholdXBasisPoints: 1_000,
    xyzThresholdYBasisPoints: 2_500,
    bucketDays: 7,
    bucketCount: 4,
    computedAt: '2026-08-29T04:00:00Z',
    rows,
    provenance: provenance(),
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ProductAnalyticsPage', () => {
  let fixture: ComponentFixture<ProductAnalyticsPage>;
  let variantSalesSpy: ReturnType<typeof vi.fn>;
  let latestClassificationSpy: ReturnType<typeof vi.fn>;

  async function render(options?: {
    readonly variantSalesMock?: ReturnType<typeof vi.fn>;
    readonly latestClassificationMock?: ReturnType<typeof vi.fn>;
    readonly configure?: (filters: ReportsFilterState) => void;
    readonly initialTab?: 'sales' | 'abc' | 'xyz';
  }): Promise<void> {
    TestBed.resetTestingModule();
    // ReportsFilterState reads its initial state from the URL on
    // construction — reset it so a prior test's filters never leak in.
    history.replaceState(null, '', '/statistics/products');

    variantSalesSpy =
      options?.variantSalesMock ?? vi.fn().mockResolvedValue(salesResponse([variantRow()]));
    latestClassificationSpy = options?.latestClassificationMock ?? vi.fn().mockResolvedValue(null);

    await TestBed.configureTestingModule({
      imports: [ProductAnalyticsPage],
      providers: [
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: () => SCOPE,
            denied: () => false,
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ReportingApi,
          useValue: {
            variantSales: variantSalesSpy,
            latestClassification: latestClassificationSpy,
            runClassification: vi.fn().mockResolvedValue(classificationRun([classificationRow()])),
          },
        },
        {
          provide: CatalogApi,
          useValue: {
            listCatalogs: vi
              .fn()
              .mockReturnValue(
                of([{ catalogId: 'catalog-1', code: 'main', name: 'Main', status: 'ACTIVE' }]),
              ),
            listCategories: vi.fn().mockReturnValue(
              of([
                {
                  categoryId: 'category-mains',
                  code: 'mains',
                  name: 'Основные блюда',
                  sortOrder: 0,
                  status: 'ACTIVE',
                  productCount: 1,
                },
              ]),
            ),
            variantsAtLocation: vi.fn().mockReturnValue(
              of({
                items: [
                  { variantId: 'variant-pizza', productName: 'Пицца Маргарита', available: false },
                ],
                nextCursor: null,
              }),
            ),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    options?.configure?.(TestBed.inject(ReportsFilterState));
    fixture = TestBed.createComponent(ProductAnalyticsPage);
    if (options?.initialTab) {
      internals().selectTab(options.initialTab);
    }
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function internals(): Internals {
    return fixture.componentInstance as unknown as Internals;
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  // ------------------------------------------------------- the defect fix

  it('re-fetches variant-sales when the shared period changes, not only on construction', async () => {
    await render();
    variantSalesSpy.mockClear();

    TestBed.inject(ReportsFilterState).setPeriod('7d');
    fixture.detectChanges();
    await flushMicrotasks();

    expect(variantSalesSpy).toHaveBeenCalled();
  });

  it('re-fetches variant-sales when the fulfilment control changes, and pushes it into the query', async () => {
    await render();
    variantSalesSpy.mockClear();

    TestBed.inject(ReportsFilterState).setFulfilmentType('DINE_IN');
    fixture.detectChanges();
    await flushMicrotasks();

    expect(variantSalesSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ fulfilmentType: ['DINE_IN'] }),
    );
  });

  it('omits the fulfilment filter entirely for the ALL preset, rather than sending a meaningless value', async () => {
    await render({ configure: (filters) => filters.setFulfilmentType('ALL') });

    expect(variantSalesSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ fulfilmentType: undefined }),
    );
  });

  // ------------------------------------------------------- wave 10 w5-reports-exports (7.7)

  it('defaults to REVENUE_DESC and sends the requested sort when a different one is picked', async () => {
    await render();

    expect(variantSalesSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ sort: 'REVENUE_DESC' }),
    );

    const root = fixture.nativeElement as HTMLElement;
    (root.querySelector('[data-testid="products-sort-QUANTITY_DESC"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();

    expect(variantSalesSpy).toHaveBeenLastCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ sort: 'QUANTITY_DESC' }),
    );
  });

  it('pages past the first response with a keyset cursor on "Load more", appending rather than replacing', async () => {
    const first = variantRow({ variantId: 'variant-a', productName: 'A', totalNetSom: 90_000 });
    const second = variantRow({ variantId: 'variant-b', productName: 'B', totalNetSom: 50_000 });
    const variantSalesMock = vi
      .fn()
      .mockResolvedValueOnce({ rows: [first], maybeMore: true, provenance: provenance() })
      .mockResolvedValueOnce({ rows: [second], maybeMore: false, provenance: provenance() });

    await render({ variantSalesMock });

    const root = fixture.nativeElement as HTMLElement;
    const loadMore = root.querySelector('[data-testid="products-load-more"]') as HTMLButtonElement;
    expect(loadMore).not.toBeNull();
    loadMore.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(variantSalesMock).toHaveBeenLastCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({
        cursor: { afterRevenueSom: 90_000, afterVariantId: 'variant-a' },
      }),
    );
    // Both pages are now on screen — the second fetch appended, it did not replace.
    expect(text()).toContain('A');
    expect(text()).toContain('B');
  });

  it('shows no "Load more" control once the server reports no further page', async () => {
    await render({ variantSalesMock: vi.fn().mockResolvedValue(salesResponse([variantRow()])) });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="products-load-more"]'),
    ).toBeNull();
  });

  // ------------------------------------------------------------- Категория

  it('renders the Категория column resolved from the catalog, not the bare id', async () => {
    await render();

    expect(text()).toContain('Основные блюда');
  });

  // ----------------------------------------------------------------- СТОП

  it('marks a stopped product with the СТОП badge', async () => {
    await render();

    expect(text()).toContain('СТОП');
  });

  it('does not mark a product that is not on stop', async () => {
    await render({
      variantSalesMock: vi
        .fn()
        .mockResolvedValue(salesResponse([variantRow({ variantId: 'variant-salad' })])),
    });

    expect(text()).not.toContain('СТОП');
  });

  // ------------------------------------------------------- cumulative column

  it('renders the ABC tab’s cumulative-share column from a persisted run', async () => {
    await render({
      configure: (filters) => filters.setCustomRange({ from: '2026-08-01', to: '2026-08-28' }),
      latestClassificationMock: vi
        .fn()
        .mockResolvedValue(
          classificationRun([
            classificationRow({ variantId: 'a', cumulativeShareBasisPoints: 7_500, abcClass: 'A' }),
            classificationRow({ variantId: 'b', cumulativeShareBasisPoints: 9_000, abcClass: 'B' }),
          ]),
        ),
      initialTab: 'abc',
    });

    expect(text()).toContain('75%');
    expect(text()).toContain('90%');
  });

  it('prompts to run a classification when no run exists for this window', async () => {
    await render({
      configure: (filters) => filters.setCustomRange({ from: '2026-08-01', to: '2026-08-28' }),
      initialTab: 'abc',
    });

    expect(text()).toContain('Запустить расчёт');
  });

  it('refuses a window under the 28-day floor without calling the classification read at all', async () => {
    await render({
      configure: (filters) => filters.setCustomRange({ from: '2026-08-01', to: '2026-08-10' }),
      initialTab: 'abc',
    });

    expect(latestClassificationSpy).not.toHaveBeenCalled();
    expect(text()).toContain('28');
  });
});
