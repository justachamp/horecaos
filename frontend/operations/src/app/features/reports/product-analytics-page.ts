import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { AbcCurveChart, AbcCurvePoint } from '../../shared/ui/charts/abc-curve-chart';
import { CatalogApi, fetchAllVariantsAtLocation } from '../catalog/catalog-api';
import { ProvenanceBanner } from './provenance-banner';
import { ddmm, formatCount } from './report-formatting';
import { ReportsFilterState } from './reports-filter-state';
import {
  AbcCurveListResponse,
  ClassificationRowResponse,
  ClassificationRunResponse,
  ProvenanceResponse,
  ReportingApi,
  VariantSalesCursor,
  VariantSalesRowResponse,
  VariantSalesSort,
} from './reporting-api';

/** The row-quantity page pages 200 at a time — same width the previous hard-coded `limit: 200` read in one shot. */
const SALES_PAGE_SIZE = 200;

/** X.19 (w6-reporting-facts, batch 11): the q-abc-curve chart's own bounded read — same width as `SALES_PAGE_SIZE`. */
const ABC_CURVE_LIMIT = 200;

/** {@code JdbcReportingStore#NIL_VARIANT_ID} — a line with no catalogue reference, mirrored here so the cursor's tiebreak matches the server's exactly. */
const NIL_VARIANT_ID = '00000000-0000-0000-0000-000000000000';

interface ProductRow {
  readonly key: string;
  readonly variantId: string | null;
  readonly name: string;
  readonly categoryId: string | null;
  readonly quantity: number;
  readonly grossSom: number;
  readonly netSom: number;
  readonly deliveryQuantity: number | null;
  readonly deliveryNetSom: number | null;
  readonly pickupQuantity: number | null;
  readonly pickupNetSom: number | null;
  readonly revenueSharePercent: number;
}

interface ClassificationRow {
  readonly key: string;
  readonly variantId: string;
  readonly name: string;
  readonly categoryId: string | null;
  readonly revenueGrossSom: number;
  readonly revenueSharePercent: number;
  readonly cumulativeSharePercent: number;
  readonly abcClass: 'A' | 'B' | 'C';
  readonly quantity: number;
  readonly mean: number;
  readonly stddev: number;
  readonly coefficientOfVariationPercent: number;
  readonly xyzClass: 'X' | 'Y' | 'Z';
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * `idle` before a manager has ever opened the ABC or XYZ tab (no fetch yet);
 * `refused` for a window under the 28-day floor; `needsRun` when reporting.read
 * found no prior run over this exact window; `denied` when a run was
 * attempted without `reporting.classification.run`.
 */
type ClassificationState =
  'idle' | 'loading' | 'ready' | 'refused' | 'needsRun' | 'running' | 'denied' | 'error';

type Tab = 'sales' | 'abc' | 'xyz';

/** One of the matrix's nine cells, or `null` for "no filter applied". */
type MatrixCell = string | null;

const MINIMUM_CLASSIFICATION_DAYS = 28;

/**
 * 7.7 Product analytics (`frontend-information-architecture.md` §7.7,
 * `statistics.md` §2.7) — tier 2.
 *
 * **Wave T14.** Three tabs now, all three real. «Продажи» gains the
 * `Категория` column and the `СТОП` row marker this wave adds, and — the
 * defect worth more than either — actually reacts to the shared filter bar:
 * `load()` used to run only once, from the constructor, so a period pill or
 * the fulfilment control changed nothing on screen while the table silently
 * kept the previous range. It is now an `effect()` over `filters.range()`
 * and `filters.fulfilmentType()`, the same shape `order-reports-page.ts`
 * already establishes for the rest of `/statistics`.
 *
 * «ABC» and «XYZ» read a persisted `reporting.classification_run` (ADR
 * 0134): `GET .../classification-runs/latest` behind plain `reporting.read`
 * for a repeat view, `POST .../classification-runs` behind the new
 * `reporting.classification.run` capability to compute a fresh one. Neither
 * tab auto-triggers the write on open — a run is a deliberate act, not a
 * side effect of looking, which is exactly why the capability is separate
 * from the read in the first place.
 *
 * **DINE_IN is disclosed, not split out.** `deliveryQuantity`/`pickupQuantity`
 * are the only two split columns the source ever had; a DINE_IN line sums
 * into `quantity`/`netSom` and into neither split, so delivery plus pickup
 * need not equal the total whenever the tenant serves any dine-in at all —
 * stated on screen rather than left for a manager to notice as a discrepancy.
 *
 * **Wave 10 w5-reports-exports (7.7).** «Продажи» had no sort control at all
 * (`GET .../variant-sales` only ever answered revenue order) and its
 * `limit: 200` had no way past it. `sort` now reaches the query
 * (QUANTITY_DESC/REVENUE_DESC/NAME_ASC) and a "Load more" button pages past
 * the first 200 with the server's own keyset cursor — the same
 * fetch-then-append shape `order-reports-page.ts`'s «Заказы» tab already
 * uses for its own cursor, not a second, silently-incomplete fetch. Changing
 * the sort discards whatever was paged in and starts over from page one,
 * since a cursor is only meaningful under the sort it was taken from.
 */
@Component({
  selector: 'q-product-analytics-page',
  imports: [TPipe, ProvenanceBanner, AbcCurveChart],
  templateUrl: './product-analytics-page.html',
  styleUrl: './product-analytics-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductAnalyticsPage {
  private readonly api = inject(ReportingApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly location = inject(CurrentLocation);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly activeTab = signal<Tab>('sales');
  protected readonly minimumClassificationDays = MINIMUM_CLASSIFICATION_DAYS;

  // ---------------------------------------------------------- «Продажи»
  protected readonly state = signal<LoadState>('loading');
  protected readonly rows = signal<readonly ProductRow[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly maybeMore = signal(false);
  protected readonly requestedTo = computed(() => this.filters.range().to);

  /** Row 7.7: the sort control the page previously had none of. */
  protected readonly sort = signal<VariantSalesSort>('REVENUE_DESC');
  protected readonly sortOptions: readonly {
    readonly id: VariantSalesSort;
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'REVENUE_DESC', labelKey: 'reports.products.sort.revenue' },
    { id: 'QUANTITY_DESC', labelKey: 'reports.products.sort.quantity' },
    { id: 'NAME_ASC', labelKey: 'reports.products.sort.name' },
  ];
  protected readonly loadingMore = signal(false);
  /** Row 7.7: keyset cursor paging past the 200-row cap — mirrors `order-reports-page.ts`'s own `commercialCursor`. */
  private salesCursor: VariantSalesCursor | null = null;
  private allSalesRows: readonly VariantSalesRowResponse[] = [];

  /** `categoryId -> name`, best-effort (CATALOG_READ may be absent for a finance-only viewer — see `loadCategoryNames`). */
  protected readonly categoryNames = signal<ReadonlyMap<string, string>>(new Map());
  /** Every variant id currently unavailable (86'd) at this location — statistics.md's «СТОП» marker. */
  protected readonly stoppedVariantIds = signal<ReadonlySet<string>>(new Set());

  // ---------------------------------------------------------- ABC / XYZ
  protected readonly classificationState = signal<ClassificationState>('idle');
  protected readonly classificationRun = signal<ClassificationRunResponse | null>(null);
  protected readonly matrixCell = signal<MatrixCell>(null);

  // ---------------------------------------------------------- X.19 q-abc-curve
  /**
   * The chart's own load, independent of `classificationState`: {@link
   * ReportingApi.abcCurve} has no 28-day floor and no write capability
   * (unlike the persisted classification run above), so it loads on every
   * visit to the ABC tab rather than waiting on a manager to press "Run
   * classification".
   */
  protected readonly abcCurveState = signal<LoadState>('loading');
  protected readonly abcCurveResult = signal<AbcCurveListResponse | null>(null);
  protected readonly abcCurveLimit = ABC_CURVE_LIMIT;

  protected readonly abcCurvePoints = computed<readonly AbcCurvePoint[]>(() => {
    const result = this.abcCurveResult();
    if (!result) {
      return [];
    }
    return result.rows.map((row) => ({
      key: row.variantId ?? row.productName,
      label: row.productName,
      sharePercent: row.sharePercent,
      cumulativeSharePercent: row.cumulativeSharePercent,
      abcClass: row.abcClass,
    }));
  });

  protected readonly abcCurveThresholdA = computed(() => this.abcCurveResult()?.abcThresholdAPercent ?? 80);
  protected readonly abcCurveThresholdB = computed(() => this.abcCurveResult()?.abcThresholdBPercent ?? 95);
  protected readonly abcCurveMaybeMore = computed(() => this.abcCurveResult()?.maybeMore ?? false);

  protected readonly classificationRangeDays = computed(() => daysInRange(this.filters.range()));

  protected readonly classificationRows = computed<readonly ClassificationRow[]>(() => {
    const run = this.classificationRun();
    if (!run) {
      return [];
    }
    return run.rows.map(toClassificationRow);
  });

  protected readonly matrixCounts = computed<ReadonlyMap<string, number>>(() => {
    const counts = new Map<string, number>();
    for (const row of this.classificationRows()) {
      const cell = row.abcClass + row.xyzClass;
      counts.set(cell, (counts.get(cell) ?? 0) + 1);
    }
    return counts;
  });

  protected readonly filteredClassificationRows = computed<readonly ClassificationRow[]>(() => {
    const cell = this.matrixCell();
    const rows = this.classificationRows();
    return cell === null ? rows : rows.filter((row) => row.abcClass + row.xyzClass === cell);
  });

  protected readonly classificationCaption = computed<{
    readonly window: string;
    readonly abcThresholds: string;
    readonly xyzThresholds: string;
    readonly metric: string;
    readonly computedAt: string;
  } | null>(() => {
    const run = this.classificationRun();
    if (!run) {
      return null;
    }
    return {
      window: `${ddmm(run.from)}–${ddmm(run.to)}`,
      abcThresholds: `${run.abcThresholdABasisPoints / 100}/${run.abcThresholdBBasisPoints / 100}%`,
      xyzThresholds: `${run.xyzThresholdXBasisPoints / 100}/${run.xyzThresholdYBasisPoints / 100}%`,
      metric: run.metricCode,
      computedAt: formatDateTime(new Date(run.computedAt), run.provenance.timezone),
    };
  });

  constructor() {
    // The defect this wave fixes: previously `load()` ran once, from here,
    // with no dependency on the filter bar at all.
    effect(() => {
      const range = this.filters.range();
      const fulfilment = this.filters.fulfilmentType();
      const sort = this.sort();
      void this.load(range, fulfilment, sort);
    });

    // ABC/XYZ read whatever run is on file for the active window whenever
    // either the tab or the range changes — never on the «Продажи» tab,
    // which has no use for a classification run at all.
    effect(() => {
      const tab = this.activeTab();
      const range = this.filters.range();
      if (tab === 'sales') {
        return;
      }
      this.matrixCell.set(null);
      void this.loadClassification(range);
    });

    // X.19: the curve loads whenever the ABC tab is open and the range
    // changes -- never on «Продажи» or «XYZ», neither of which has any use
    // for it.
    effect(() => {
      const tab = this.activeTab();
      const range = this.filters.range();
      if (tab !== 'abc') {
        return;
      }
      void this.loadAbcCurve(range);
    });
  }

  protected selectTab(tab: Tab): void {
    this.activeTab.set(tab);
  }

  protected retry(): void {
    void this.load(this.filters.range(), this.filters.fulfilmentType(), this.sort());
  }

  /** Row 7.7: changing the sort has no cursor to page from yet, so it starts over from page one. */
  protected selectSort(sort: VariantSalesSort): void {
    this.sort.set(sort);
  }

  /** Row 7.7: pages past the first 200 rows with the server's own keyset cursor. */
  protected async loadMoreSales(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.salesCursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const range = this.filters.range();
      const fulfilment = this.filters.fulfilmentType();
      const result = await this.api.variantSales(scope.tenantId, {
        from: range.from,
        to: range.to,
        fulfilmentType: fulfilment === 'ALL' ? undefined : [fulfilment],
        limit: SALES_PAGE_SIZE,
        sort: this.sort(),
        cursor: this.salesCursor,
      });
      this.allSalesRows = [...this.allSalesRows, ...result.rows];
      this.maybeMore.set(result.maybeMore);
      this.salesCursor = cursorAfter(result.rows, this.sort());
      this.applyLoadedSalesRows();
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected retryClassification(): void {
    void this.loadClassification(this.filters.range());
  }

  protected retryAbcCurve(): void {
    void this.loadAbcCurve(this.filters.range());
  }

  protected toggleMatrixCell(cell: string): void {
    this.matrixCell.set(this.matrixCell() === cell ? null : cell);
  }

  protected isStopped(variantId: string | null): boolean {
    return variantId !== null && this.stoppedVariantIds().has(variantId);
  }

  protected categoryLabel(categoryId: string | null): string {
    if (categoryId === null) {
      return '—';
    }
    return this.categoryNames().get(categoryId) ?? '—';
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  protected formatCountValue = formatCount;

  protected async runClassificationNow(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const range = this.filters.range();
    if (daysInRange(range) < MINIMUM_CLASSIFICATION_DAYS) {
      this.classificationState.set('refused');
      return;
    }
    this.classificationState.set('running');
    try {
      const run = await this.api.runClassification(scope.tenantId, {
        from: range.from,
        to: range.to,
      });
      this.classificationRun.set(run);
      this.classificationState.set('ready');
    } catch (error) {
      const denied = error instanceof ApiError && error.status === 403;
      this.classificationState.set(denied ? 'denied' : 'error');
    }
  }

  // ------------------------------------------------------------- loading

  private async load(
    range: { from: string; to: string },
    fulfilment: string,
    sort: VariantSalesSort,
  ): Promise<void> {
    this.state.set('loading');
    this.salesCursor = null;
    this.allSalesRows = [];
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const result = await this.api.variantSales(scope.tenantId, {
        from: range.from,
        to: range.to,
        fulfilmentType: fulfilment === 'ALL' ? undefined : [fulfilment],
        limit: SALES_PAGE_SIZE,
        sort,
      });
      this.provenance.set(result.provenance);
      this.maybeMore.set(result.maybeMore);
      this.allSalesRows = result.rows;
      this.salesCursor = cursorAfter(result.rows, sort);
      this.applyLoadedSalesRows();
      this.state.set('ready');
      void this.loadCategoryNames(scope.tenantId, scope.brandId);
      void this.loadStoppedVariants(scope);
    } catch {
      this.state.set('error');
    }
  }

  /**
   * `revenueSharePercent` is a share of every row currently on screen, not
   * of the tenant's whole catalogue in range — the same bounded-read caveat
   * `reports.products.maybeMore` already discloses. Recomputed over the
   * full accumulated set on every page, "Load more" included, so a share
   * printed against page one is not silently stale once page two arrives.
   */
  private applyLoadedSalesRows(): void {
    const totalNet = this.allSalesRows.reduce((sum, row) => sum + row.totalNetSom, 0);
    this.rows.set(this.allSalesRows.map((row) => toProductRow(row, totalNet)));
  }

  /**
   * Best-effort category-name resolution, off `CatalogApi` (control-plane's
   * own authoring surface — batch 1/3's P02/P21, reused rather than
   * rebuilt). Never blocks or fails the report: a viewer without
   * `CATALOG_READ` (a finance-scoped role, say) still sees the column, with
   * the category id rendered as `—` instead of a name.
   */
  private async loadCategoryNames(tenantId: string, brandId: string): Promise<void> {
    try {
      const scope = { tenantId, brandId };
      const catalogs = await firstValueFrom(this.catalogApi.listCatalogs(scope));
      const names = new Map<string, string>();
      for (const catalog of catalogs) {
        const categories = await firstValueFrom(
          this.catalogApi.listCategories(scope, catalog.catalogId),
        );
        for (const category of categories) {
          names.set(category.categoryId, category.name);
        }
      }
      this.categoryNames.set(names);
    } catch {
      // Degrade to blank category names rather than failing the whole report.
    }
  }

  /**
   * Best-effort stop-list resolution: every variant 86'd at this location,
   * off `catalog.location_offerings` via `CatalogApi.variantsAtLocation`
   * (P21) — the same read `menus-page.ts` already drives its own stop toggle
   * from, reused rather than a second query.
   */
  private async loadStoppedVariants(scope: {
    tenantId: string;
    brandId: string;
    locationId: string;
  }): Promise<void> {
    try {
      const variants = await fetchAllVariantsAtLocation(this.catalogApi, scope, scope.locationId);
      this.stoppedVariantIds.set(
        new Set(variants.filter((v) => !v.available).map((v) => v.variantId)),
      );
    } catch {
      // Degrade to no rows marked stopped rather than failing the report.
    }
  }

  private async loadClassification(range: { from: string; to: string }): Promise<void> {
    if (daysInRange(range) < MINIMUM_CLASSIFICATION_DAYS) {
      this.classificationRun.set(null);
      this.classificationState.set('refused');
      return;
    }
    this.classificationState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.classificationState.set('error');
      return;
    }
    try {
      const latest = await this.api.latestClassification(scope.tenantId, {
        from: range.from,
        to: range.to,
      });
      this.classificationRun.set(latest);
      this.classificationState.set(latest ? 'ready' : 'needsRun');
    } catch {
      this.classificationState.set('error');
    }
  }

  /** X.19: {@link ReportingApi.abcCurve}'s own load — see {@link abcCurveState}'s own doc for why it never waits on {@link loadClassification}. */
  private async loadAbcCurve(range: { from: string; to: string }): Promise<void> {
    this.abcCurveState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.abcCurveState.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const result = await this.api.abcCurve(scope.tenantId, {
        from: range.from,
        to: range.to,
        limit: this.abcCurveLimit,
      });
      this.abcCurveResult.set(result);
      this.abcCurveState.set('ready');
    } catch (error) {
      const denied = error instanceof ApiError && error.status === 403;
      this.abcCurveState.set(denied ? 'denied' : 'error');
    }
  }
}

/**
 * Row 7.7: the keyset cursor for the next «Продажи» page — whichever field
 * matches `sort`, off the last row. `variantId` falls back to {@link
 * NIL_VARIANT_ID} when null (a line with no catalogue reference), mirroring
 * `JdbcReportingStore#NIL_VARIANT_ID`'s own substitution so the tuple
 * comparison server-side is never evaluated against a real `NULL`.
 */
function cursorAfter(
  rows: readonly VariantSalesRowResponse[],
  sort: VariantSalesSort,
): VariantSalesCursor | null {
  const last = rows.at(-1);
  if (!last) {
    return null;
  }
  const afterVariantId = last.variantId ?? NIL_VARIANT_ID;
  switch (sort) {
    case 'QUANTITY_DESC':
      return { afterQuantity: last.totalQuantity, afterVariantId };
    case 'REVENUE_DESC':
      return { afterRevenueSom: last.totalNetSom, afterVariantId };
    case 'NAME_ASC':
      return { afterProductName: last.productName, afterVariantId };
  }
}

function toProductRow(row: VariantSalesRowResponse, totalNet: number): ProductRow {
  return {
    key: row.variantId ?? row.productName,
    variantId: row.variantId,
    name: row.productName,
    categoryId: row.categoryId,
    quantity: row.totalQuantity,
    grossSom: row.totalGrossSom,
    netSom: row.totalNetSom,
    deliveryQuantity: row.deliveryQuantity,
    deliveryNetSom: row.deliveryNetSom,
    pickupQuantity: row.pickupQuantity,
    pickupNetSom: row.pickupNetSom,
    revenueSharePercent: totalNet <= 0 ? 0 : Math.round((row.totalNetSom / totalNet) * 100),
  };
}

/**
 * A plain projection of the response — no category name baked in. The
 * template resolves it through `categoryLabel()` instead, so a name that
 * arrives after the run itself (the two loads are independent) updates the
 * column without a second pass over every row.
 */
function toClassificationRow(row: ClassificationRowResponse): ClassificationRow {
  return {
    key: row.variantId,
    variantId: row.variantId,
    name: row.productName,
    categoryId: row.categoryId,
    revenueGrossSom: row.revenueGrossSom,
    revenueSharePercent: row.revenueShareBasisPoints / 100,
    cumulativeSharePercent: row.cumulativeShareBasisPoints / 100,
    abcClass: row.abcClass,
    quantity: row.quantityTotal,
    mean: row.meanQuantityPerBucket,
    stddev: row.stddevQuantityPerBucket,
    coefficientOfVariationPercent: row.coefficientOfVariationBasisPoints / 100,
    xyzClass: row.xyzClass,
  };
}

/** Calendar-day count, inclusive of both ends — the same arithmetic `ReportsFilterState` keeps private to itself. */
function daysInRange(range: { readonly from: string; readonly to: string }): number {
  const [fy, fm, fd] = range.from.split('-').map(Number);
  const [ty, tm, td] = range.to.split('-').map(Number);
  const fromMs = Date.UTC(fy, fm - 1, fd);
  const toMs = Date.UTC(ty, tm - 1, td);
  return Math.round((toMs - fromMs) / 86_400_000) + 1;
}
