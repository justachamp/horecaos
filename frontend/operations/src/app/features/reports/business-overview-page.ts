import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { DonutChart } from '../../shared/ui/charts/donut-chart';
import { FunnelChart, FunnelDropOff, FunnelStage } from '../../shared/ui/charts/funnel-chart';
import { KpiTile, KpiTileFormula, deltaOf } from '../../shared/ui/charts/kpi-tile';
import { LineChart } from '../../shared/ui/charts/line-chart';
import { StackedBarChart } from '../../shared/ui/charts/stacked-bar-chart';
import { ChartCategory, ChartSeries } from '../../shared/ui/charts/chart-model';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { orderStatusLabel } from '../orders/order-status';
import { ProvenanceBanner } from './provenance-banner';
import {
  ddmm,
  formatCount,
  formatSecondsDuration,
  formatShare,
  formatSignedMinutes,
  median,
} from './report-formatting';
import {
  DailyPoint,
  dailyAverageCheck,
  dailySeries,
  deriveAverageCheck,
  rollUpByGranularity,
  sumAcrossDays,
  sumTotal,
} from './report-rollup';
import { Granularity, ReportsFilterState } from './reports-filter-state';
import {
  CancellationReasonResponse,
  MetricResponse,
  OrderRowResponse,
  OutcomeRowResponse,
  ProvenanceResponse,
  ReportingApi,
  RowResponse,
} from './reporting-api';

const BAND_A_METRICS = [
  'revenue.gross.v1',
  'orders.count.v1',
  'average_check.v1',
  'orders.cancelled.v1',
  'orders.late.v1',
] as const;

const CANCELLING_STATUSES = new Set(['CANCELLED', 'REJECTED', 'EXPIRED', 'PAYMENT_FAILED']);

interface TileViewModel {
  readonly key: string;
  readonly label: string;
  readonly display: string;
  readonly deltaText: string | null;
  readonly deltaUp: boolean;
  readonly deltaSuffix: string | null;
  readonly subtitle: string | null;
  readonly provisional: boolean;
  readonly provisionalNote: string | null;
  /** One point per business date in the tile's own period — {@link KpiTile}'s sparkline (IA X.20). */
  readonly sparklinePoints: readonly (number | null)[];
  /** statistics.md §1.2's published-formula panel — null while the metrics dictionary has not loaded yet. */
  readonly formula: KpiTileFormula | null;
}

interface MixRow {
  readonly key: string;
  readonly label: string;
  readonly count: number;
  readonly revenueSom: number;
  readonly countSharePercent: number;
  readonly revenueSharePercent: number;
}

interface OutcomeRow {
  readonly status: string;
  readonly reasonCode: string | null;
  /** Already resolved to the tenant's own wording where a match exists — see `resolveReasonName`. */
  readonly reasonLabel: string | null;
  readonly stockDisposition: string | null;
  readonly liabilityParty: string | null;
  readonly count: number;
  readonly sharePercent: number;
}

interface BranchRow {
  readonly locationId: string;
  readonly name: string;
  readonly grossSom: number;
  readonly orderCount: number;
  readonly averageCheckSom: number | null;
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * 7.1 Business overview (`frontend-information-architecture.md` PART 2 §7,
 * tier P) — statistics.md §2.1. "The one screen a manager opens between
 * services to answer 'is today going normally, and if not, where.'"
 *
 * **What is real here.** Every tile, chart and table names a registry metric
 * id (ADR 0043) and is fetched from the typed `GET .../reporting/queries`,
 * `.../order-outcomes`, `.../orders` and `.../preparation-time` endpoints —
 * nothing on this page invents an aggregate. `deriveAverageCheck` in
 * `report-rollup.ts` explains the one arithmetic step this page does perform
 * and why it is the registry's own formula rather than a new one.
 *
 * **Wave P27.** Every money-metric query now always names `LEGAL_ENTITY` in
 * `groupBy` — a two-entity tenant would otherwise have every Band A tile
 * throw `CombinedEntityTotalException` and error the whole page (ADR 0038;
 * see the trap this wave's own brief names) — and folds the per-entity rows
 * back into one figure the same way `sumTotal` already folds per-day rows,
 * which is transparent rather than the server-side combine ADR 0038
 * forbids. `channelCodes`/`locationIds`/`legalEntityIds` from
 * `ReportsFilterState` now reach every query this page makes. `Доставка`/
 * `Самовывоз` read `delivery_time.median.v1`/`pickup_time.median.v1` over
 * `GET .../reporting/fulfilment-time` instead of rendering "not built".
 * Every tile carries the published-formula panel statistics.md §1.2
 * requires, from one `GET .../reporting/metrics` call. The cancellation
 * panel resolves its reason code to the tenant's own wording
 * (`GET .../reporting/cancellation-reasons`) and shows what a cancellation
 * cost — `stock_disposition`/`liability_party`, copied from ADR 0039's
 * `order_outcomes` onto the fact this same wave.
 *
 * **What is still scoped down**, named rather than silently missing: no
 * hourly sparkline (the day-grain query has no hour dimension to draw one
 * from); the delta compares against the same span one *whole number of weeks*
 * back rather than a hand-picked "same weekday last week", which is the same
 * property for every period this bar offers (`ReportsFilterState.comparisonRange`);
 * distance has no tile — `reporting.fact_delivery` has no producer (ADR 0042)
 * and is out of this wave's scope.
 */
@Component({
  selector: 'q-business-overview-page',
  imports: [TPipe, ProvenanceBanner, KpiTile, DonutChart, StackedBarChart, LineChart, FunnelChart],
  templateUrl: './business-overview-page.html',
  styleUrl: './business-overview-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BusinessOverviewPage implements OnInit {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly filters = inject(ReportsFilterState);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly tiles = signal<readonly TileViewModel[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly prepMedianSeconds = signal<number | null>(null);
  protected readonly channelMix = signal<readonly MixRow[]>([]);
  protected readonly channelMixByRevenue = signal(false);
  protected readonly fulfilmentMix = signal<readonly MixRow[]>([]);
  /** P39 (7.1c): the payment-mix card, folded across every branch in range. */
  protected readonly paymentMix = signal<readonly MixRow[]>([]);
  protected readonly outcomes = signal<readonly OutcomeRow[]>([]);
  protected readonly completedCount = signal(0);
  /** Wave 8 w7-reports (7.1a): orders.late.v1 over the same period — the funnel's own «Опоздание» branch. */
  protected readonly lateOrderCount = signal(0);
  protected readonly branches = signal<readonly BranchRow[]>([]);
  protected readonly multiLocation = signal(false);

  /**
   * The per-business-date rows Band A's own metrics query returns — kept,
   * not collapsed the way `sumTotal` collapses them for the tiles above. Feed
   * the "Dynamics" trend band (IA X.19) and every tile's sparkline (IA X.20).
   */
  private readonly dailyRows = signal<readonly RowResponse[]>([]);

  protected readonly requestedTo = computed(() => this.filters.range().to);
  protected readonly outcomeTotalCount = computed(() =>
    this.outcomes().reduce((sum, row) => sum + row.count, 0),
  );

  /**
   * Wave 8 w7-reports (7.1a): «Воронка по финальному статусу»
   * (statistics.md §2.1 Band D) — Всего заказов → Завершено → Вовремя, with
   * every non-completing terminal status and the late share as labelled
   * drop-offs (`funnelDropOffs`) rather than further stages of the main
   * path. `completedCount` and `outcomeTotalCount` both come off the same
   * `GET .../order-outcomes` read (`loadOutcomes`), so `total` here is
   * exactly the read's own row sum — the reconciliation `business-overview-
   * page.spec.ts` proves.
   */
  protected readonly funnelStages = computed<readonly FunnelStage[]>(() => {
    const completed = this.completedCount();
    const total = completed + this.outcomeTotalCount();
    const onTime = Math.max(0, completed - this.lateOrderCount());
    return [
      { key: 'TOTAL', label: this.i18n.t('reports.overview.funnel.stage.total'), value: total },
      {
        key: 'COMPLETED',
        label: this.i18n.t('reports.overview.funnel.completed'),
        value: completed,
      },
      { key: 'ON_TIME', label: this.i18n.t('reports.overview.funnel.stage.onTime'), value: onTime },
    ];
  });

  /**
   * One branch per non-completing terminal status (summed across its own
   * cancellation reasons — the detail table below keeps the per-reason cut)
   * off TOTAL, plus one branch off COMPLETED for orders that closed late.
   * `outcomes()` is already narrowed to `CANCELLING_STATUSES` by {@link
   * loadOutcomes}, so grouping it by `status` alone, with no further filter,
   * is exactly the funnel's drop-off set.
   */
  protected readonly funnelDropOffs = computed<readonly FunnelDropOff[]>(() => {
    const byStatus = new Map<string, number>();
    for (const row of this.outcomes()) {
      byStatus.set(row.status, (byStatus.get(row.status) ?? 0) + row.count);
    }
    const dropOffs: FunnelDropOff[] = [...byStatus.entries()].map(([status, count]) => ({
      key: status,
      label: this.statusLabel(status),
      value: count,
      fromStageKey: 'TOTAL',
    }));
    const late = this.lateOrderCount();
    if (late > 0) {
      dropOffs.push({
        key: 'LATE',
        label: this.i18n.t('reports.overview.funnel.stage.late'),
        value: late,
        fromStageKey: 'COMPLETED',
      });
    }
    return dropOffs;
  });

  protected readonly revenueTrend = computed<readonly ChartSeries[]>(() => [
    dailySeriesToChart(
      this.dailyRows(),
      'revenue.gross.v1',
      this.i18n.t('reports.overview.tile.revenue'),
      this.filters.granularity(),
    ),
  ]);
  protected readonly ordersTrend = computed<readonly ChartSeries[]>(() => [
    dailySeriesToChart(
      this.dailyRows(),
      'orders.count.v1',
      this.i18n.t('reports.overview.tile.orders'),
      this.filters.granularity(),
    ),
  ]);
  protected readonly hasTrend = computed(() => this.dailyRows().length > 0);

  /** Wave P27 (7.1): pickup/delivery elapsed time — delivery_time.median.v1 / pickup_time.median.v1. */
  protected readonly deliveryTimeSeconds = signal<number | null>(null);
  protected readonly pickupTimeSeconds = signal<number | null>(null);
  private readonly deliveryTimeLoaded = signal(false);
  private readonly pickupTimeLoaded = signal(false);
  protected readonly deliveryTimeBuilt = computed(() => this.deliveryTimeLoaded());
  protected readonly pickupTimeBuilt = computed(() => this.pickupTimeLoaded());

  /** Wave P27 (7.1): the metric dictionary, keyed by code — GET .../reporting/metrics, called once. */
  private readonly metricsByCode = signal<ReadonlyMap<string, MetricResponse>>(new Map());
  /** Wave P27 (7.1a): a CANCELLED order's reason code (order_outcome_reasons.id) to its internal_name. */
  private readonly cancellationReasonNames = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly channelMixSegments = computed<readonly ChartCategory[]>(() =>
    this.channelMix().map((row) => ({
      key: row.key,
      label: row.label,
      value: this.channelMixByRevenue() ? row.revenueSom : row.count,
    })),
  );

  protected readonly fulfilmentMixSegments = computed<readonly ChartCategory[]>(() =>
    this.fulfilmentMix().map((row) => ({ key: row.key, label: row.label, value: row.count })),
  );

  /** P39 (7.1c): payment_mix.amount.v1 — takings by method, always by revenue. */
  protected readonly paymentMixSegments = computed<readonly ChartCategory[]>(() =>
    this.paymentMix().map((row) => ({ key: row.key, label: row.label, value: row.revenueSom })),
  );

  protected readonly paymentMixTotalDisplay = computed(() =>
    this.formatMoneyValue(
      this.paymentMixSegments().reduce((sum, segment) => sum + segment.value, 0),
    ),
  );

  /** The donut's own centre label — the period's whole channel-mix total, on whichever basis is selected. */
  protected readonly channelMixTotalDisplay = computed(() => {
    const total = this.channelMixSegments().reduce((sum, segment) => sum + segment.value, 0);
    return this.channelMixByRevenue() ? this.formatMoneyValue(total) : this.formatCountValue(total);
  });

  protected readonly formatDuration = formatSecondsDuration;
  protected readonly formatCountValue = formatCount;

  ngOnInit(): void {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected toggleChannelMixBasis(): void {
    this.channelMixByRevenue.update((current) => !current);
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  protected openOrderLog(): void {
    void this.router.navigate(['/statistics/orders']);
  }

  protected statusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  /** Wave P27 (7.1): what a cancellation cost the tenant's stock — ADR 0039's disposition, translated. */
  protected dispositionLabel(disposition: string | null): string | null {
    if (disposition === null) {
      return null;
    }
    const key = `reports.overview.funnel.disposition.${disposition}` as MessageKey;
    return this.i18n.t(key);
  }

  /** Wave P27 (7.1): who carried the cost — ADR 0039's liability party, translated. */
  protected liabilityLabel(party: string | null): string | null {
    if (party === null) {
      return null;
    }
    const key = `reports.overview.funnel.liability.${party}` as MessageKey;
    return this.i18n.t(key);
  }

  /** Wave P27 (7.1d): branch/channel/legal-entity, in the shape every query call on this page shares. */
  private sliceParams(): {
    readonly locationId?: readonly string[];
    readonly channelCode?: readonly string[];
    readonly legalEntityId?: readonly string[];
  } {
    const locationIds = this.filters.locationIds();
    const channelCodes = this.filters.channelCodes();
    const legalEntityIds = this.filters.legalEntityIds();
    return {
      locationId: locationIds.length > 0 ? locationIds : undefined,
      channelCode: channelCodes.length > 0 ? channelCodes : undefined,
      legalEntityId: legalEntityIds.length > 0 ? legalEntityIds : undefined,
    };
  }

  /** Wave P27 (7.1d): the fulfilment axis, pushed into the order-grain read rather than filtered after it. */
  private fulfilmentTypeParam(): readonly string[] | undefined {
    const type = this.filters.fulfilmentType();
    return type === 'ALL' ? undefined : [type];
  }

  /** Wave P27 (7.1): statistics.md §1.2's published-formula panel, from the one metrics call this page makes. */
  protected tileFormula(metricCode: string): KpiTileFormula | null {
    const definition = this.metricsByCode().get(metricCode);
    if (!definition) {
      return null;
    }
    return {
      definition: definition.definition,
      inclusion: definition.includes,
      exclusion: definition.excludes,
      unit: definition.unit,
    };
  }

  private async load(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }

    this.state.set('loading');
    try {
      await Promise.all([
        this.loadMetricsDictionary(scope),
        this.loadTilesAndMix(scope),
        this.loadPrepTime(scope),
        this.loadFulfilmentTimes(scope),
        this.loadOutcomes(scope),
        this.loadBranches(scope),
        this.loadPaymentMix(scope),
      ]);
      this.state.set('ready');
      this.lastError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
        this.state.set('error');
      } else {
        throw error;
      }
    }
  }

  private async loadTilesAndMix(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const comparison = this.filters.comparisonRange();
    const slice = this.sliceParams();
    const channels = await this.channelsApi.list(scope).catch(() => [] as readonly ChannelView[]);
    const channelByCode = new Map(channels.map((channel) => [channel.code, channel]));

    const [currentQuery, comparisonQuery, channelQuery, fulfilmentQuery, lateSample] =
      await Promise.all([
        // LEGAL_ENTITY is always named here even though nothing on this tile
        // set shows it broken down by entity: BAND_A_METRICS mixes money
        // metrics with count metrics, and a money metric queried without it
        // on a two-entity tenant throws CombinedEntityTotalException (ADR
        // 0038) and errors the whole page. sumTotal folds the per-entity
        // rows back into one figure client-side, which is the transparent
        // fold ADR 0038 allows — the refusal is only ever about the server
        // silently combining two taxpayers into one stored total.
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: [...BAND_A_METRICS],
          groupBy: ['LEGAL_ENTITY'],
          ...slice,
        }),
        this.api.query(scope.tenantId, {
          from: comparison.from,
          to: comparison.to,
          metric: [...BAND_A_METRICS],
          groupBy: ['LEGAL_ENTITY'],
          ...slice,
        }),
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['channel_mix.count.v1', 'revenue.gross.v1'],
          groupBy: ['CHANNEL', 'LEGAL_ENTITY'],
          ...slice,
        }),
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['orders.count.v1'],
          groupBy: ['FULFILMENT_TYPE'],
          ...slice,
        }),
        this.api.orders(scope.tenantId, {
          from: range.from,
          to: range.to,
          sort: 'LATENESS_DESC',
          limit: 200,
          locationId: slice.locationId,
          channelCode: slice.channelCode,
          fulfilmentType: this.fulfilmentTypeParam(),
          legalEntityId: slice.legalEntityId,
        }),
      ]);

    this.provenance.set(currentQuery.provenance);
    this.dailyRows.set(currentQuery.rows);

    const sumCodes = [
      'revenue.gross.v1',
      'orders.count.v1',
      'orders.cancelled.v1',
      'orders.late.v1',
    ];
    const current = sumTotal(currentQuery.rows, sumCodes);
    const previous = sumTotal(comparisonQuery.rows, sumCodes);
    const provisional = new Set(currentQuery.provenance.provisionalMetrics);
    // Wave 8 w7-reports (7.1a): orders.late.v1 is already fetched for the
    // "Опоздания" tile above — the funnel's on-time/late split reuses the
    // same figure rather than a second query.
    this.lateOrderCount.set(current['orders.late.v1']);

    const avgCheck = deriveAverageCheck(current['revenue.gross.v1'], current['orders.count.v1']);
    const avgCheckPrevious = deriveAverageCheck(
      previous['revenue.gross.v1'],
      previous['orders.count.v1'],
    );

    const cancelShare = formatShare(
      current['orders.cancelled.v1'],
      current['orders.count.v1'] + current['orders.cancelled.v1'],
    );
    const lateSummary = summariseLateSample(lateSample.rows);

    this.tiles.set([
      this.buildTile({
        key: 'revenue.gross.v1',
        labelKey: 'reports.overview.tile.revenue',
        kind: 'money',
        value: current['revenue.gross.v1'],
        previous: previous['revenue.gross.v1'],
        provisional: provisional.has('revenue.gross.v1'),
        subtitle: null,
        sparklinePoints: dailySeries(currentQuery.rows, 'revenue.gross.v1').map((p) => p.value),
      }),
      this.buildTile({
        key: 'orders.count.v1',
        labelKey: 'reports.overview.tile.orders',
        kind: 'count',
        value: current['orders.count.v1'],
        previous: previous['orders.count.v1'],
        provisional: provisional.has('orders.count.v1'),
        subtitle: null,
        sparklinePoints: dailySeries(currentQuery.rows, 'orders.count.v1').map((p) => p.value),
      }),
      this.buildTile({
        key: 'average_check.v1',
        labelKey: 'reports.overview.tile.averageCheck',
        kind: 'money',
        value: avgCheck,
        previous: avgCheckPrevious,
        provisional: provisional.has('average_check.v1'),
        subtitle: null,
        sparklinePoints: dailyAverageCheck(currentQuery.rows).map((p) => p.value),
      }),
      this.buildTile({
        key: 'orders.cancelled.v1',
        labelKey: 'reports.overview.tile.cancelled',
        kind: 'count',
        value: current['orders.cancelled.v1'],
        previous: previous['orders.cancelled.v1'],
        provisional: provisional.has('orders.cancelled.v1'),
        subtitle: this.i18n.t('reports.overview.tile.cancelled.subtitle', { share: cancelShare }),
        sparklinePoints: dailySeries(currentQuery.rows, 'orders.cancelled.v1').map((p) => p.value),
      }),
      this.buildTile({
        key: 'orders.late.v1',
        labelKey: 'reports.overview.tile.late',
        kind: 'count',
        value: current['orders.late.v1'],
        previous: previous['orders.late.v1'],
        provisional: provisional.has('orders.late.v1'),
        subtitle:
          lateSummary === null
            ? null
            : this.i18n.t('reports.overview.tile.late.subtitle', { minutes: lateSummary }),
        sparklinePoints: dailySeries(currentQuery.rows, 'orders.late.v1').map((p) => p.value),
      }),
    ]);

    const channelBuckets = sumAcrossDays(channelQuery.rows, (row) => row.channelCode ?? '', [
      'channel_mix.count.v1',
      'revenue.gross.v1',
    ]);
    const channelTotalCount = sumOf(channelBuckets, 'channel_mix.count.v1');
    const channelTotalRevenue = sumOf(channelBuckets, 'revenue.gross.v1');
    this.channelMix.set(
      [...channelBuckets.entries()]
        .map(([code, values]) => ({
          key: code,
          label: channelByCode.get(code)?.displayName ?? code,
          count: values['channel_mix.count.v1'],
          revenueSom: values['revenue.gross.v1'],
          countSharePercent: percentOf(values['channel_mix.count.v1'], channelTotalCount),
          revenueSharePercent: percentOf(values['revenue.gross.v1'], channelTotalRevenue),
        }))
        .sort((a, b) => b.count - a.count),
    );

    const fulfilmentBuckets = sumAcrossDays(
      fulfilmentQuery.rows,
      (row) => row.fulfilmentType ?? '',
      ['orders.count.v1'],
    );
    const fulfilmentTotal = sumOf(fulfilmentBuckets, 'orders.count.v1');
    this.fulfilmentMix.set(
      [...fulfilmentBuckets.entries()].map(([type, values]) => ({
        key: type,
        label: this.i18n.t(fulfilmentLabelKey(type)),
        count: values['orders.count.v1'],
        revenueSom: 0,
        countSharePercent: percentOf(values['orders.count.v1'], fulfilmentTotal),
        revenueSharePercent: 0,
      })),
    );
  }

  /** Wave P27 (7.1): the metric dictionary, so every tile can carry statistics.md §1.2's formula panel. */
  private async loadMetricsDictionary(scope: LocationScope): Promise<void> {
    const definitions = await this.api
      .metrics(scope.tenantId)
      .catch(() => [] as readonly MetricResponse[]);
    this.metricsByCode.set(
      new Map(definitions.map((definition) => [definition.metricCode, definition])),
    );
  }

  /** Wave P27 (7.1a): resolves a CANCELLED order's reason code to the tenant's own wording. */
  private async loadCancellationReasonNames(scope: LocationScope): Promise<void> {
    const reasons = await this.api
      .cancellationReasons(scope.tenantId)
      .catch(() => [] as readonly CancellationReasonResponse[]);
    this.cancellationReasonNames.set(
      new Map(reasons.map((reason) => [reason.reasonCode, reason.internalName])),
    );
  }

  /** Wave P27 (7.1): pickup/delivery elapsed time — GET .../reporting/fulfilment-time, a registry-and-endpoint gap. */
  private async loadFulfilmentTimes(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const locationId = this.sliceParams().locationId;
    const [delivery, pickup] = await Promise.all([
      this.api.fulfilmentTime(scope.tenantId, {
        from: range.from,
        to: range.to,
        locationId,
        fulfilmentType: 'DELIVERY',
      }),
      this.api.fulfilmentTime(scope.tenantId, {
        from: range.from,
        to: range.to,
        locationId,
        fulfilmentType: 'PICKUP',
      }),
    ]);
    this.deliveryTimeSeconds.set(delivery.medianSeconds);
    this.deliveryTimeLoaded.set(true);
    this.pickupTimeSeconds.set(pickup.medianSeconds);
    this.pickupTimeLoaded.set(true);
  }

  private async loadPrepTime(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const result = await this.api.preparationTime(scope.tenantId, {
      from: range.from,
      to: range.to,
      locationId: this.sliceParams().locationId,
    });
    this.prepMedianSeconds.set(result.medianSeconds);
  }

  private async loadOutcomes(scope: LocationScope): Promise<void> {
    await this.loadCancellationReasonNames(scope);
    const range = this.filters.range();
    const slice = this.sliceParams();
    const result = await this.api.orderOutcomes(scope.tenantId, {
      from: range.from,
      to: range.to,
      locationId: slice.locationId,
      channelCode: slice.channelCode,
    });
    const total = result.rows.reduce((sum, row) => sum + row.count, 0);
    const completed = result.rows
      .filter((row) => row.terminalStatus === 'COMPLETED')
      .reduce((sum, row) => sum + row.count, 0);
    this.completedCount.set(completed);
    const reasonNames = this.cancellationReasonNames();
    this.outcomes.set(
      result.rows
        .filter((row) => CANCELLING_STATUSES.has(row.terminalStatus))
        .map((row) => outcomeRow(row, total, reasonNames)),
    );
  }

  private async loadBranches(scope: LocationScope): Promise<void> {
    const locations = await this.locationsApi
      .list(scope)
      .catch(() => [] as readonly LocationView[]);
    this.multiLocation.set(locations.length > 1);
    if (locations.length <= 1) {
      this.branches.set([]);
      return;
    }
    const nameById = new Map(locations.map((loc) => [loc.id, loc.displayName]));
    const range = this.filters.range();
    const result = await this.api.query(scope.tenantId, {
      from: range.from,
      to: range.to,
      metric: ['revenue.gross.v1', 'orders.count.v1'],
      groupBy: ['LOCATION', 'LEGAL_ENTITY'],
      ...this.sliceParams(),
    });
    const buckets = sumAcrossDays(result.rows, (row) => row.locationId ?? '', [
      'revenue.gross.v1',
      'orders.count.v1',
    ]);
    this.branches.set(
      [...buckets.entries()]
        .map(([locationId, values]) => ({
          locationId,
          name: nameById.get(locationId) ?? locationId,
          grossSom: values['revenue.gross.v1'],
          orderCount: values['orders.count.v1'],
          averageCheckSom: deriveAverageCheck(
            values['revenue.gross.v1'],
            values['orders.count.v1'],
          ),
        }))
        .sort((a, b) => b.grossSom - a.grossSom)
        .slice(0, 5),
    );
  }

  /**
   * P39 (7.1c): the payment-mix card. `overview` already folds every branch
   * into one row per method (never across legal entities, ADR 0038) — this
   * only has to attach a display name and turn the response into the same
   * {@link MixRow} shape the channel and fulfilment cards beside it use.
   */
  private async loadPaymentMix(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const paymentMethodCodes = this.filters.paymentMethodCodes();
    const [methods, mix] = await Promise.all([
      this.paymentMethodsApi.list(scope).catch(() => [] as readonly PaymentMethodView[]),
      this.api.paymentMix(scope.tenantId, {
        from: range.from,
        to: range.to,
        locationId: this.sliceParams().locationId,
        paymentMethodCode: paymentMethodCodes.length > 0 ? paymentMethodCodes : undefined,
      }),
    ]);
    const nameByCode = new Map(methods.map((method) => [method.code, method]));
    const total = mix.overview.reduce((sum, row) => sum + row.amountSom, 0);

    const totalTenders = mix.overview.reduce((sum, row) => sum + row.tenderCount, 0);
    this.paymentMix.set(
      mix.overview
        .map((row) => {
          const method = nameByCode.get(row.paymentMethodCode);
          const label = method
            ? (method.localizedNames[this.i18n.locale()] ?? method.displayName)
            : row.paymentMethodCode;
          return {
            key: row.paymentMethodCode,
            label,
            count: row.tenderCount,
            revenueSom: row.amountSom,
            countSharePercent: percentOf(row.tenderCount, totalTenders),
            revenueSharePercent: percentOf(row.amountSom, total),
          };
        })
        .sort((a, b) => b.revenueSom - a.revenueSom),
    );
  }

  /**
   * Assembles one {@link TileViewModel} — the one place Band A's five tiles
   * share a definition of the delta, the provisional note and the sparkline,
   * rather than each hand-rolling its own (IA X.20). `deltaOf` and the tile
   * component itself both live in `shared/ui/charts/kpi-tile.ts`.
   */
  private buildTile(config: {
    readonly key: string;
    readonly labelKey: MessageKey;
    readonly kind: 'money' | 'count';
    readonly value: number | null;
    readonly previous: number | null;
    readonly provisional: boolean;
    readonly subtitle: string | null;
    readonly sparklinePoints: readonly (number | null)[];
  }): TileViewModel {
    const delta = deltaOf(config.value, config.previous);
    const display =
      config.value === null
        ? '—'
        : config.kind === 'money'
          ? formatMoney({ amountMinor: config.value, currency: 'UZS' }, this.i18n.locale(), {
              withUnit: true,
            })
          : formatCount(config.value);
    return {
      key: config.key,
      label: this.i18n.t(config.labelKey),
      display,
      deltaText: delta.deltaText,
      deltaUp: delta.deltaUp,
      deltaSuffix:
        delta.deltaText === null ? null : this.i18n.t('reports.overview.tile.deltaSuffix'),
      subtitle: config.subtitle,
      provisional: config.provisional,
      provisionalNote: config.provisional
        ? this.i18n.t('reports.provenance.provisional.short')
        : null,
      sparklinePoints: config.sparklinePoints,
      formula: this.tileFormula(config.key),
    };
  }
}

/**
 * One metric's day-by-day series as {@link LineChart} wants it — DD.MM
 * labels, a translated series name. Wave P27: folded to the filter bar's own
 * `granularity` before charting — {@link rollUpByGranularity}'s own doc
 * explains why that fold happens here rather than in the query.
 */
function dailySeriesToChart(
  rows: readonly RowResponse[],
  metricCode: string,
  label: string,
  granularity: Granularity,
): ChartSeries {
  const points: readonly DailyPoint[] = rollUpByGranularity(
    dailySeries(rows, metricCode),
    granularity,
  );
  return {
    key: metricCode,
    label,
    points: points.map((p) => ({ x: ddmm(p.date), y: p.value })),
  };
}

function summariseLateSample(rows: readonly OrderRowResponse[]): string | null {
  const secondsLate = rows
    .map((row) => row.secondsLate)
    .filter((value): value is number => value !== null);
  const value = median(secondsLate);
  return value === null ? null : formatSignedMinutes(value, 'мин');
}

/** Wave P27 (7.1a): resolves `cancellationReasonCode` to the tenant's own wording where a match exists. */
function outcomeRow(
  row: OutcomeRowResponse,
  total: number,
  reasonNames: ReadonlyMap<string, string>,
): OutcomeRow {
  return {
    status: row.terminalStatus,
    reasonCode: row.cancellationReasonCode,
    reasonLabel:
      row.cancellationReasonCode === null
        ? null
        : (reasonNames.get(row.cancellationReasonCode) ?? row.cancellationReasonCode),
    stockDisposition: row.stockDisposition,
    liabilityParty: row.liabilityParty,
    count: row.count,
    sharePercent: percentOf(row.count, total),
  };
}

function sumOf(byKey: ReadonlyMap<string, Record<string, number>>, code: string): number {
  let total = 0;
  for (const values of byKey.values()) {
    total += values[code] ?? 0;
  }
  return total;
}

function percentOf(count: number, total: number): number {
  return total <= 0 ? 0 : Math.round((count / total) * 100);
}

function fulfilmentLabelKey(
  type: string,
):
  | 'orders.fulfillmentMode.DELIVERY'
  | 'orders.fulfillmentMode.PICKUP'
  | 'orders.fulfillmentMode.DINE_IN' {
  switch (type) {
    case 'DELIVERY':
      return 'orders.fulfillmentMode.DELIVERY';
    case 'PICKUP':
      return 'orders.fulfillmentMode.PICKUP';
    default:
      return 'orders.fulfillmentMode.DINE_IN';
  }
}
