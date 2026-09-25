import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { HistogramChart } from '../../shared/ui/charts/histogram-chart';
import { ChartCategory } from '../../shared/ui/charts/chart-model';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ProvenanceBanner } from './provenance-banner';
import { formatCount, formatShare } from './report-formatting';
import { deriveAverageCheck, sumAcrossDays } from './report-rollup';
import { ReportsFilterState } from './reports-filter-state';
import { BucketResponse, ProvenanceResponse, ReportingApi } from './reporting-api';

/**
 * `sla_bucket_set.v1` — the platform-fixed, versioned six (`SlaBucketSet.java`).
 * Codes mirror the Java constants verbatim: `UNDER_30`, `M30_35`, `M35_40`,
 * `M40_50`, `M50_60`, `OVER_60` — half-open minute intervals
 * `[0,30) [30,35) [35,40) [40,50) [50,60) [60,∞)`.
 */
const SLA_BUCKETS = ['UNDER_30', 'M30_35', 'M35_40', 'M40_50', 'M50_60', 'OVER_60'] as const;

type SlaBucketCode = (typeof SLA_BUCKETS)[number];

/** `TPipe` needs a literal `MessageKey`, never a concatenated string — see its own doc. */
const SLA_BUCKET_LABEL_KEYS: Readonly<Record<SlaBucketCode, MessageKey>> = {
  UNDER_30: 'reports.branches.sla.bucket.UNDER_30',
  M30_35: 'reports.branches.sla.bucket.M30_35',
  M35_40: 'reports.branches.sla.bucket.M35_40',
  M40_50: 'reports.branches.sla.bucket.M40_50',
  M50_60: 'reports.branches.sla.bucket.M50_60',
  OVER_60: 'reports.branches.sla.bucket.OVER_60',
};

/** Wave T06 (7.3): the secondary sort control's own button labels — reuses each column's own key. */
const SORT_LABEL_KEYS: Readonly<Record<SortKey, MessageKey>> = {
  revenue: 'reports.branches.column.revenue',
  orders: 'reports.branches.column.orders',
  averageCheck: 'reports.branches.column.averageCheck',
  cancelShare: 'reports.branches.column.cancelShare',
  onTime: 'reports.branches.column.onTime',
  prepTime: 'reports.branches.column.prepTime',
  deliveryTime: 'reports.branches.column.deliveryTime',
};

interface BranchRow {
  readonly locationId: string;
  readonly name: string;
  readonly orderCount: number;
  readonly grossSom: number;
  readonly averageCheckSom: number | null;
  readonly cancelledCount: number;
  readonly cancelShare: string;
  /** Numeric form of {@link cancelShare}, 0..100 — the secondary sort control's own sort key. */
  readonly cancelSharePercent: number;
  readonly prepMedianSeconds: number | null;
  /** Wave 11 w5-fulfillment-destination (7.3): `Ср. время доставки` — mean courier transit seconds, `delivery_transit_time.average.v1`. */
  readonly deliveryAverageSeconds: number | null;
  /** Wave T06 (7.3): `Доставка / Самовывоз / Агрегаторы` — the counts triple. */
  readonly deliveryCount: number;
  readonly pickupCount: number;
  readonly aggregatorCount: number;
  /**
   * Wave T06 (7.3): `В норме %` — `100 × (orders.promised.v1 − orders.late.v1)
   * / orders.promised.v1`. Null when nothing was promised at this branch in
   * range, never a 100% that reads as a perfect record nobody earned.
   */
  readonly onTimeSharePercent: number | null;
}

interface SlaRow {
  readonly locationId: string;
  readonly name: string;
  readonly buckets: Readonly<
    Record<string, { readonly count: number; readonly sharePercent: number }>
  >;
  readonly total: number;
  /** Wave T06 (7.3a): `handover_time.median.v1` — statistics.md §2.3's own «Медиана» column. */
  readonly medianSeconds: number | null;
}

/** Wave T06 (7.3b): one (branch, channel) cell of the per-channel count-and-average-check block. */
interface ChannelRow {
  readonly locationId: string;
  readonly locationName: string;
  readonly channelCode: string;
  readonly channelName: string;
  readonly orderCount: number;
  readonly averageCheckSom: number | null;
}

/** Wave T06 (7.3b): one (branch, payment method) cell of the cash-reconciliation split. */
interface PaymentSplitRow {
  readonly locationId: string;
  readonly locationName: string;
  readonly paymentMethodCode: string;
  readonly paymentMethodName: string;
  readonly tenderCount: number;
  readonly amountSom: number;
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error' | 'singleLocation';

/** Wave T06 (7.3): the branch leaderboard's persistent secondary sort control. */
type SortKey =
  | 'revenue'
  | 'orders'
  | 'averageCheck'
  | 'cancelShare'
  | 'onTime'
  | 'prepTime'
  | 'deliveryTime';

/**
 * 7.3 Branch & SLA reports (`frontend-information-architecture.md` §7.3,
 * `statistics.md` §2.3) — tier 2.
 *
 * **Table A — the leaderboard.** `GET .../reporting/queries` grouped by
 * `LOCATION` for revenue/orders/cancellations, same shape
 * `business-overview-page.ts`'s own top-five branch table already proves.
 * Wave T06 adds three more columns, all from data the platform already
 * writes:
 * - `Доставка / Самовывоз / Агрегаторы` — one more typed query grouped by
 *   `['LOCATION', 'FULFILMENT_TYPE', 'CHANNEL']`: `agg_branch_day` has
 *   carried both axes since V0031, so delivery/pickup fold out of
 *   `FULFILMENT_TYPE` and the aggregator count folds out of `CHANNEL`,
 *   narrowed to whichever channels `SalesChannelsApi` marks `AGGREGATOR` —
 *   the same channel-classification move `order-reports-page.ts`'s own
 *   «Посуточно» tab already makes for its per-3PL column.
 * - `В норме %` — the same query's `orders.promised.v1`/`orders.late.v1`,
 *   folded across fulfilment type and channel: `100 × (promised − late) /
 *   promised`, null (never 0%) when nothing was promised.
 * - `Ср. время приготовления` no longer costs one `/preparation-time` call
 *   per branch: `GET .../reporting/preparation-time-by-location` answers
 *   every branch from one request.
 *
 * **Wave 11 w5-fulfillment-destination adds one more column.**
 * `Ср. время доставки` — `delivery_transit_time.average.v1`, mean courier
 * transit seconds (acceptance to delivery) from `reporting.fact_delivery`,
 * which `T11` (V0337) started writing; `GET
 * .../reporting/delivery-transit-time-by-location` answers every branch
 * from one request, the same no-fan-out shape `preparationTimeByLocation`
 * already established. Courier-leg-only, never the door-to-door
 * `delivery_time.median.v1` the overview's own tile answers — the two are
 * never the same figure.
 *
 * **Table B — SLA time buckets.** `GET .../reporting/sla-buckets`, already
 * grouped by branch. Wave T06 fixes an arithmetic defect in
 * {@link buildSlaRows} (see its own doc) and adds the `Медиана` column from
 * the same endpoint's new `medians` field — one extra grouped query on the
 * server, still the one request this screen always made. The green→red tint
 * ramp (`branch-sla-report-page.css`'s own `.sla-cell--*` rules) and the
 * printed bucket-set version below the table already existed and are
 * untouched.
 *
 * **Table C — per-channel counts and average check.** Wave T06's one new
 * query: `orders.count.v1`/`revenue.gross.v1` grouped by `['LOCATION',
 * 'CHANNEL', 'LEGAL_ENTITY']` (money, so `LEGAL_ENTITY` is always named —
 * ADR 0038 — and folded back out client-side, the same move every other
 * money-plus-axis query on this console makes). A flat (branch, channel)
 * table rather than the 2D matrix statistics.md §2.3 draws —
 * `order-reports-page.ts`'s own «Сводка» tab already makes this exact
 * simplification for the same reason: the data is real and correctly
 * summed, only the grid layout is deferred.
 *
 * **Table D — payment-method split.** Wave T06 reuses `P39`'s
 * `GET .../reporting/payment-mix` `byLocation` rows unchanged — no new
 * backend, this screen is simply another reader of an endpoint
 * `business-overview-page.ts`'s own payment-mix card already proves.
 *
 * Every table below is hidden for a single-location tenant, per the spec's
 * own instruction — the bucket distribution alone would be meaningful for
 * one branch, but is not worth a whole screen for it.
 */
@Component({
  selector: 'q-branch-sla-report-page',
  imports: [TPipe, ProvenanceBanner, HistogramChart],
  templateUrl: './branch-sla-report-page.html',
  styleUrl: './branch-sla-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchSlaReportPage {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  private readonly branchRowsRaw = signal<readonly BranchRow[]>([]);
  protected readonly slaRows = signal<readonly SlaRow[]>([]);
  protected readonly channelRows = signal<readonly ChannelRow[]>([]);
  protected readonly paymentSplitRows = signal<readonly PaymentSplitRow[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  /** `sla_bucket_set.v1` is the only version this build defines — see `SLA_BUCKETS`'s own doc. */
  protected readonly slaBucketSetVersion = 1;

  protected readonly slaBucketCodes = SLA_BUCKETS;
  protected readonly requestedTo = computed(() => this.filters.range().to);

  /** Wave T06 (7.3): the leaderboard's persistent secondary sort control — `Выручка` desc is the default. */
  protected readonly sortBy = signal<SortKey>('revenue');
  protected readonly sortKeys: readonly SortKey[] = [
    'revenue',
    'orders',
    'averageCheck',
    'cancelShare',
    'onTime',
    'prepTime',
    'deliveryTime',
  ];

  protected readonly branchRows = computed<readonly BranchRow[]>(() =>
    sortBranchRows(this.branchRowsRaw(), this.sortBy()),
  );

  /**
   * The tenant-wide handover-time distribution across every branch's own SLA
   * row — a histogram over `sla_bucket_set.v1`'s six fixed buckets (IA X.19),
   * this screen's first chart. The per-branch table beside it keeps the exact
   * counts; this answers "is the whole tenant's handover time skewed slow"
   * at a glance, which a six-column-per-branch table cannot.
   */
  protected readonly slaHistogram = computed<readonly ChartCategory[]>(() => {
    const totals = new Map<SlaBucketCode, number>(SLA_BUCKETS.map((code) => [code, 0]));
    for (const branchRow of this.slaRows()) {
      for (const code of SLA_BUCKETS) {
        totals.set(code, (totals.get(code) ?? 0) + branchRow.buckets[code].count);
      }
    }
    return SLA_BUCKETS.map((code) => ({
      key: code,
      label: this.bucketLabel(code),
      value: totals.get(code) ?? 0,
    }));
  });

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected selectSort(key: SortKey): void {
    this.sortBy.set(key);
  }

  protected sortLabel(key: SortKey): string {
    return this.i18n.t(SORT_LABEL_KEYS[key]);
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  protected formatCountValue = formatCount;

  protected bucketLabel(bucket: SlaBucketCode): string {
    return this.i18n.t(SLA_BUCKET_LABEL_KEYS[bucket]);
  }

  protected formatPrep(seconds: number | null): string {
    if (seconds === null) {
      return '—';
    }
    const minutes = Math.round(seconds / 60);
    return this.i18n.t('reports.branches.minutesShort', { minutes });
  }

  protected formatOnTime(sharePercent: number | null): string {
    return sharePercent === null ? '—' : `${sharePercent}%`;
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const locations = await this.locationsApi.list(scope);
      if (locations.length <= 1) {
        this.state.set('singleLocation');
        return;
      }
      const nameById = new Map<string, string>(
        locations.map((loc: LocationView) => [loc.id, loc.displayName]),
      );

      const [channels, paymentMethods] = await Promise.all([
        this.channelsApi.list(scope).catch(() => [] as readonly ChannelView[]),
        this.paymentMethodsApi.list(scope).catch(() => [] as readonly PaymentMethodView[]),
      ]);
      const nameByChannel = new Map(channels.map((channel) => [channel.code, channel.displayName]));
      // The same channel-classification move order-reports-page.ts's «Посуточно» tab already
      // makes for its per-3PL column: AGGREGATOR is one of ADR 0036's closed system types.
      const aggregatorCodes = new Set(
        channels
          .filter((channel) => channel.systemType.toUpperCase().includes('AGGREGATOR'))
          .map((channel) => channel.code),
      );
      const nameByPaymentMethod = new Map(
        paymentMethods.map((method) => [
          method.code,
          method.localizedNames[this.i18n.locale()] ?? method.displayName,
        ]),
      );

      const range = this.filters.range();

      const [query, sla, prepByLocation, deliveryTimeByLocation, fulfilmentAndOnTime, channelMoney, mix] =
        await Promise.all([
          this.api.query(scope.tenantId, {
            from: range.from,
            to: range.to,
            metric: ['revenue.gross.v1', 'orders.count.v1', 'orders.cancelled.v1'],
            groupBy: ['LOCATION'],
          }),
          this.api.slaBuckets(scope.tenantId, { from: range.from, to: range.to }),
          this.api.preparationTimeByLocation(scope.tenantId, { from: range.from, to: range.to }),
          this.api.deliveryTransitTimeByLocation(scope.tenantId, { from: range.from, to: range.to }),
          this.api.query(scope.tenantId, {
            from: range.from,
            to: range.to,
            metric: ['orders.count.v1', 'orders.promised.v1', 'orders.late.v1'],
            groupBy: ['LOCATION', 'FULFILMENT_TYPE', 'CHANNEL'],
          }),
          this.api.query(scope.tenantId, {
            from: range.from,
            to: range.to,
            metric: ['orders.count.v1', 'revenue.gross.v1'],
            // Money, so LEGAL_ENTITY is always named (ADR 0038) and folded back
            // out below — the same move every other money-plus-axis query on
            // this console makes.
            groupBy: ['LOCATION', 'CHANNEL', 'LEGAL_ENTITY'],
          }),
          this.api.paymentMix(scope.tenantId, { from: range.from, to: range.to }),
        ]);

      this.provenance.set(query.provenance);
      const prepById = new Map(
        prepByLocation.rows.map((row) => [row.locationId, row.medianSeconds]),
      );
      const deliveryAverageById = new Map(
        deliveryTimeByLocation.rows.map((row) => [row.locationId, row.averageSeconds]),
      );

      const buckets = sumAcrossDays(query.rows, (row) => row.locationId ?? '', [
        'revenue.gross.v1',
        'orders.count.v1',
        'orders.cancelled.v1',
      ]);

      const byLocationAndFulfilment = sumAcrossDays(
        fulfilmentAndOnTime.rows,
        (row) => `${row.locationId}|${row.fulfilmentType}`,
        ['orders.count.v1'],
      );
      const aggregatorRows = fulfilmentAndOnTime.rows.filter(
        (row) => row.channelCode !== null && aggregatorCodes.has(row.channelCode),
      );
      const aggregatorByLocation = sumAcrossDays(aggregatorRows, (row) => row.locationId ?? '', [
        'orders.count.v1',
      ]);
      const onTimeByLocation = sumAcrossDays(
        fulfilmentAndOnTime.rows,
        (row) => row.locationId ?? '',
        ['orders.promised.v1', 'orders.late.v1'],
      );

      this.branchRowsRaw.set(
        locations.map((loc) => {
          const values = buckets.get(loc.id) ?? {
            'revenue.gross.v1': 0,
            'orders.count.v1': 0,
            'orders.cancelled.v1': 0,
          };
          const orderCount = values['orders.count.v1'];
          const cancelled = values['orders.cancelled.v1'];
          const deliveryCount =
            byLocationAndFulfilment.get(`${loc.id}|DELIVERY`)?.['orders.count.v1'] ?? 0;
          const pickupCount =
            byLocationAndFulfilment.get(`${loc.id}|PICKUP`)?.['orders.count.v1'] ?? 0;
          const aggregatorCount = aggregatorByLocation.get(loc.id)?.['orders.count.v1'] ?? 0;
          const onTime = onTimeByLocation.get(loc.id);
          const promised = onTime?.['orders.promised.v1'] ?? 0;
          const late = onTime?.['orders.late.v1'] ?? 0;

          return {
            locationId: loc.id,
            name: nameById.get(loc.id) ?? loc.id,
            orderCount,
            grossSom: values['revenue.gross.v1'],
            averageCheckSom: deriveAverageCheck(values['revenue.gross.v1'], orderCount),
            cancelledCount: cancelled,
            cancelShare: formatShare(cancelled, orderCount + cancelled),
            cancelSharePercent: sharePercentOf(cancelled, orderCount + cancelled),
            prepMedianSeconds: prepById.get(loc.id) ?? null,
            deliveryAverageSeconds: deliveryAverageById.get(loc.id) ?? null,
            deliveryCount,
            pickupCount,
            aggregatorCount,
            onTimeSharePercent:
              promised > 0 ? Math.round(((promised - late) / promised) * 100) : null,
          };
        }),
      );

      const medianByLocation = new Map(
        sla.medians.map((row) => [row.locationId, row.medianSeconds]),
      );
      this.slaRows.set(buildSlaRows(sla.buckets, nameById, medianByLocation));

      this.channelRows.set(buildChannelRows(channelMoney.rows, nameById, nameByChannel));

      this.paymentSplitRows.set(
        mix.byLocation
          .filter((row) => row.locationId !== null)
          .map((row) => ({
            locationId: row.locationId as string,
            locationName: nameById.get(row.locationId as string) ?? (row.locationId as string),
            paymentMethodCode: row.paymentMethodCode,
            paymentMethodName:
              nameByPaymentMethod.get(row.paymentMethodCode) ?? row.paymentMethodCode,
            tenderCount: row.tenderCount,
            amountSom: row.amountSom,
          }))
          .sort(
            (a, b) => a.locationName.localeCompare(b.locationName) || b.amountSom - a.amountSom,
          ),
      );

      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }
}

/** {@link BranchRow.cancelShare}'s numeric counterpart — 0 when the total is zero, never a divide-by-zero NaN. */
function sharePercentOf(count: number, total: number): number {
  return total <= 0 ? 0 : Math.round((count / total) * 100);
}

/**
 * Wave T06 (7.3): the leaderboard's persistent secondary sort control.
 * `Выручка` desc stays the primary/default sort (statistics.md §2.3); every
 * other key also sorts descending — the reader scans top-to-bottom either
 * way, and `onTimeSharePercent`/`prepMedianSeconds` being null (never
 * computed) sorts last rather than first or crowding out real figures.
 */
function sortBranchRows(rows: readonly BranchRow[], key: SortKey): readonly BranchRow[] {
  const valueOf = (row: BranchRow): number | null => {
    switch (key) {
      case 'revenue':
        return row.grossSom;
      case 'orders':
        return row.orderCount;
      case 'averageCheck':
        return row.averageCheckSom;
      case 'cancelShare':
        return row.cancelSharePercent;
      case 'onTime':
        return row.onTimeSharePercent;
      case 'prepTime':
        // Slower (higher seconds) first — sensible framing while the sort
        // stays descending for every column: a manager choosing this column
        // wants to see the slowest kitchen at the top.
        return row.prepMedianSeconds;
      case 'deliveryTime':
        // Same framing as prepTime: slowest courier leg at the top.
        return row.deliveryAverageSeconds;
    }
  };
  return [...rows].sort((a, b) => {
    const av = valueOf(a);
    const bv = valueOf(b);
    if (av === null && bv === null) {
      return 0;
    }
    if (av === null) {
      return 1;
    }
    if (bv === null) {
      return -1;
    }
    return bv - av;
  });
}

/**
 * Fixes the defect wave T06's brief names: the previous version summed
 * `count` across every day in range while overwriting `sharePercent` with
 * whatever the *last* day processed happened to report — so a 7-day or
 * month preset rendered the last day's share printed beside a whole-range
 * count. `sharePercent` is now computed once, from the same whole-range
 * totals `count` already is: `Math.round((count / total) * 100)`, where
 * `total` is that branch's own sum across every bucket in the range.
 *
 * Also carries wave T06's new `medianSeconds` column
 * (`handover_time.median.v1`, from the same `/sla-buckets` response) — the
 * median already exists as of this wave (see
 * `JdbcReportingStore#medianSecondsTotalByLocation`); this only wires it in.
 */
export function buildSlaRows(
  buckets: readonly BucketResponse[],
  nameById: ReadonlyMap<string, string>,
  medianByLocation: ReadonlyMap<string, number | null>,
): readonly SlaRow[] {
  const countsByLocation = new Map<string, Map<string, number>>();
  for (const bucket of buckets) {
    const forLocation = countsByLocation.get(bucket.locationId) ?? new Map<string, number>();
    forLocation.set(
      bucket.bucketCode,
      (forLocation.get(bucket.bucketCode) ?? 0) + bucket.orderCount,
    );
    countsByLocation.set(bucket.locationId, forLocation);
  }

  return [...countsByLocation.entries()]
    .map(([locationId, countByBucket]) => {
      const total = SLA_BUCKETS.reduce((sum, code) => sum + (countByBucket.get(code) ?? 0), 0);
      const rowBuckets: Record<string, { count: number; sharePercent: number }> = {};
      for (const code of SLA_BUCKETS) {
        const count = countByBucket.get(code) ?? 0;
        rowBuckets[code] = { count, sharePercent: sharePercentOf(count, total) };
      }
      return {
        locationId,
        name: nameById.get(locationId) ?? locationId,
        buckets: rowBuckets,
        total,
        medianSeconds: medianByLocation.get(locationId) ?? null,
      };
    })
    .sort((a, b) => b.total - a.total);
}

/**
 * Wave T06 (7.3b): the per-channel count-and-average-check block, one flat
 * row per (branch, channel) — `order-reports-page.ts`'s own «Сводка» tab
 * doc explains why a flat table rather than the 2D matrix statistics.md
 * §2.3 draws is the right simplification for this wave.
 */
function buildChannelRows(
  rows: readonly {
    readonly locationId: string | null;
    readonly channelCode: string | null;
    readonly values: Readonly<Record<string, number | null>>;
  }[],
  nameById: ReadonlyMap<string, string>,
  nameByChannel: ReadonlyMap<string, string>,
): readonly ChannelRow[] {
  const byCell = new Map<
    string,
    { locationId: string; channelCode: string; grossSom: number; orderCount: number }
  >();
  for (const row of rows) {
    if (row.locationId === null || row.channelCode === null) {
      continue;
    }
    const key = `${row.locationId}|${row.channelCode}`;
    const existing = byCell.get(key) ?? {
      locationId: row.locationId,
      channelCode: row.channelCode,
      grossSom: 0,
      orderCount: 0,
    };
    byCell.set(key, {
      ...existing,
      grossSom: existing.grossSom + (row.values['revenue.gross.v1'] ?? 0),
      orderCount: existing.orderCount + (row.values['orders.count.v1'] ?? 0),
    });
  }

  return [...byCell.values()]
    .map((cell) => ({
      locationId: cell.locationId,
      locationName: nameById.get(cell.locationId) ?? cell.locationId,
      channelCode: cell.channelCode,
      channelName: nameByChannel.get(cell.channelCode) ?? cell.channelCode,
      orderCount: cell.orderCount,
      averageCheckSom: deriveAverageCheck(cell.grossSom, cell.orderCount),
    }))
    .sort((a, b) => a.locationName.localeCompare(b.locationName) || b.orderCount - a.orderCount);
}
