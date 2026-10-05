import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from '../catalog/catalog-api';
import { ProductAnalyticsPage } from './product-analytics-page';
import { ReportsFilterState } from './reports-filter-state';
import { ApiError } from '../../core/api/problem-details';
import {
  AbcCurveListResponse,
  AbcCurveRowResponse,
  ClassificationRowResponse,
  ClassificationRunResponse,
  ComboSalesListResponse,
  ComboSalesRowResponse,
  ReportingApi,
  VariantSalesListResponse,
  VariantSalesRowResponse,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Reaches past `protected` to drive the component the same way every other report-page spec does. */
type Internals = {
  selectTab(tab: 'sales' | 'abc' | 'xyz' | 'combos'): void;
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

function comboRow(overrides: Partial<ComboSalesRowResponse> = {}): ComboSalesRowResponse {
  return {
    comboContainerVariantId: 'combo-lunch',
    comboName: 'Бизнес-ланч',
    combosSold: 12,
    purchases: 9,
    orders: 8,
    totalGrossSom: 480_000,
    totalDiscountSom: 20_000,
    totalNetSom: 460_000,
    deliveryCombos: 7,
    pickupCombos: 5,
    ...overrides,
  };
}

function comboResponse(
  rows: readonly ComboSalesRowResponse[],
  maybeMore = false,
): ComboSalesListResponse {
  return { rows, maybeMore, provenance: provenance() };
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

function abcCurveRow(overrides: Partial<AbcCurveRowResponse> = {}): AbcCurveRowResponse {
  return {
    variantId: 'variant-pizza',
    categoryId: 'category-mains',
    productName: 'Пицца Маргарита',
    totalNetSom: 90_000,
    sharePercent: 80,
    cumulativeSharePercent: 80,
    abcClass: 'A',
    ...overrides,
  };
}

function abcCurveResponse(rows: readonly AbcCurveRowResponse[]): AbcCurveListResponse {
  return {
    rows,
    maybeMore: false,
    abcThresholdAPercent: 80,
    abcThresholdBPercent: 95,
    provenance: provenance(),
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
  let abcCurveSpy: ReturnType<typeof vi.fn>;
  let comboSalesSpy: ReturnType<typeof vi.fn>;

  async function render(options?: {
    readonly variantSalesMock?: ReturnType<typeof vi.fn>;
    readonly latestClassificationMock?: ReturnType<typeof vi.fn>;
    readonly abcCurveMock?: ReturnType<typeof vi.fn>;
    readonly comboSalesMock?: ReturnType<typeof vi.fn>;
    readonly configure?: (filters: ReportsFilterState) => void;
    readonly initialTab?: 'sales' | 'abc' | 'xyz' | 'combos';
  }): Promise<void> {
    TestBed.resetTestingModule();
    // ReportsFilterState reads its initial state from the URL on
    // construction — reset it so a prior test's filters never leak in.
    history.replaceState(null, '', '/statistics/products');

    variantSalesSpy =
      options?.variantSalesMock ?? vi.fn().mockResolvedValue(salesResponse([variantRow()]));
    latestClassificationSpy = options?.latestClassificationMock ?? vi.fn().mockResolvedValue(null);
    abcCurveSpy =
      options?.abcCurveMock ?? vi.fn().mockResolvedValue(abcCurveResponse([abcCurveRow()]));
    comboSalesSpy =
      options?.comboSalesMock ?? vi.fn().mockResolvedValue(comboResponse([comboRow()]));

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
            abcCurve: abcCurveSpy,
            comboSales: comboSalesSpy,
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
    (
      root.querySelector('[data-testid="products-sort-QUANTITY_DESC"]') as HTMLButtonElement
    ).click();
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

  it('keeps the portions a product sold: 3.5 is 3,5, not rounded up to 4 (ADR 0137)', async () => {
    await render({
      variantSalesMock: vi
        .fn()
        .mockResolvedValue(
          salesResponse([
            variantRow({ totalQuantity: 3.5, deliveryQuantity: 2.5, pickupQuantity: 1 }),
          ]),
        ),
    });

    const cells = [...(fixture.nativeElement as HTMLElement).querySelectorAll('td.q-tnum')].map(
      (cell) => cell.textContent?.trim(),
    );
    expect(cells).toContain('3,5');
    expect(cells).toContain('2,5');
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

  // ------------------------------------------------------- X.19 q-abc-curve

  it('renders the ABC tab’s cumulative-share curve, independent of any persisted classification run', async () => {
    await render({
      abcCurveMock: vi.fn().mockResolvedValue(
        abcCurveResponse([
          abcCurveRow({
            variantId: 'a',
            productName: 'Пицца',
            cumulativeSharePercent: 80,
            abcClass: 'A',
          }),
          abcCurveRow({
            variantId: 'b',
            productName: 'Салат',
            cumulativeSharePercent: 95,
            abcClass: 'B',
          }),
        ]),
      ),
      // Under the 28-day floor: the persisted classification refuses, but
      // the curve has no such floor and still renders.
      configure: (filters) => filters.setCustomRange({ from: '2026-08-01', to: '2026-08-10' }),
      initialTab: 'abc',
    });

    expect(abcCurveSpy).toHaveBeenCalled();
    const chart = fixture.nativeElement.querySelector('q-abc-curve');
    expect(chart).not.toBeNull();
    expect(chart?.querySelectorAll('.q-abc-curve__point').length).toBe(2);
  });

  it('discloses that the curve is a bounded read when the server reports more rows exist', async () => {
    await render({
      abcCurveMock: vi
        .fn()
        .mockResolvedValue({ ...abcCurveResponse([abcCurveRow()]), maybeMore: true }),
      initialTab: 'abc',
    });

    expect(text()).toContain('200');
  });

  // ------------------------------------------------------- ADR 0136: sales by combo

  describe('«Комбо» tab (ADR 0136)', () => {
    it('does not read combo sales while the Продажи tab is showing', async () => {
      await render();

      expect(comboSalesSpy).not.toHaveBeenCalled();
    });

    it('reads combo sales for the shared period and fulfilment, and lists each combo with what it sold', async () => {
      await render({
        configure: (filters) => {
          filters.setCustomRange({ from: '2026-08-01', to: '2026-08-28' });
          filters.setFulfilmentType('DELIVERY');
        },
        initialTab: 'combos',
      });

      expect(comboSalesSpy).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({
          from: '2026-08-01',
          to: '2026-08-28',
          fulfilmentType: ['DELIVERY'],
        }),
      );
      const row = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="combo-sales-row"]',
      );
      expect(row?.textContent).toContain('Бизнес-ланч');
      // 12 combos over 9 purchases on 8 orders: one purchase may be several combos.
      expect(row?.textContent).toMatch(/12/);
      expect(row?.textContent).toMatch(/9/);
      expect(row?.textContent).toMatch(/460\s?000/);
    });

    it('shows a dash where the server has no delivery or pickup count, not a zero', async () => {
      await render({
        comboSalesMock: vi
          .fn()
          .mockResolvedValue(
            comboResponse([comboRow({ deliveryCombos: null, pickupCombos: null })]),
          ),
        initialTab: 'combos',
      });

      const cells = (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="combo-sales-delivery"], [data-testid="combo-sales-pickup"]',
      );
      expect(cells).toHaveLength(2);
      cells.forEach((cell) => expect(cell.textContent?.trim()).toBe('—'));
    });

    it('re-reads when the shared period changes while the tab is open', async () => {
      await render({ initialTab: 'combos' });
      comboSalesSpy.mockClear();

      TestBed.inject(ReportsFilterState).setPeriod('7d');
      fixture.detectChanges();
      await flushMicrotasks();

      expect(comboSalesSpy).toHaveBeenCalledTimes(1);
    });

    it('says a component sold on its own is under Продажи, and discloses a bounded read', async () => {
      await render({
        comboSalesMock: vi.fn().mockResolvedValue(comboResponse([comboRow()], true)),
        initialTab: 'combos',
      });

      expect(text()).toContain('200');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="combo-sales-note"]'),
      ).not.toBeNull();
    });

    it('shows the empty state when no combo was sold in the period', async () => {
      await render({
        comboSalesMock: vi.fn().mockResolvedValue(comboResponse([])),
        initialTab: 'combos',
      });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="combo-sales-row"]'),
      ).toBeNull();
      expect(text()).toContain('Нет данных за выбранный период');
    });

    it('offers a retry on a failed read, and says so plainly on a refusal', async () => {
      await render({
        comboSalesMock: vi.fn().mockRejectedValueOnce(new Error('offline')),
        initialTab: 'combos',
      });
      expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).not.toBeNull();

      comboSalesSpy.mockResolvedValueOnce(comboResponse([comboRow()]));
      (
        (fixture.nativeElement as HTMLElement).querySelector('.secondary') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="combo-sales-row"]'),
      ).not.toBeNull();

      await render({
        comboSalesMock: vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, null)),
        initialTab: 'combos',
      });
      expect(text()).toContain('Нет доступа');
    });
  });
});
