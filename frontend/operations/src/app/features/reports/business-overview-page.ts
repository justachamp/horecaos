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
import { KpiTile, deltaOf } from '../../shared/ui/charts/kpi-tile';
import { LineChart } from '../../shared/ui/charts/line-chart';
import { StackedBarChart } from '../../shared/ui/charts/stacked-bar-chart';
import { ChartCategory, ChartSeries } from '../../shared/ui/charts/chart-model';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
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
  sumAcrossDays,
  sumTotal,
} from './report-rollup';
import { ReportsFilterState } from './reports-filter-state';
import {
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
 * **What is scoped down for this wave**, named rather than silently missing:
 * no hourly sparkline (the day-grain query has no hour dimension to draw one
 * from); the delta compares against the same span one *whole number of weeks*
 * back rather than a hand-picked "same weekday last week", which is the same
 * property for every period this bar offers (`ReportsFilterState.comparisonRange`);
 * `Доставка`/`Самовывоз` timing and `Оплата` mix render as honest not-built
 * cards (ADR 0042, ADR 0013/0046); the cancellation panel is an inline
 * breakdown table rather than a peek modal, and it cannot show what a
 * cancellation *cost* — `stock_disposition`/`liability_party` are columns
 * `fact_order` carries but ADR 0039's `order_outcomes` does not exist yet to
 * fill them, so every row would read null regardless.
 */
@Component({
  selector: 'q-business-overview-page',
  imports: [TPipe, ProvenanceBanner, KpiTile, DonutChart, StackedBarChart, LineChart],
  templateUrl: './business-overview-page.html',
  styleUrl: './business-overview-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BusinessOverviewPage implements OnInit {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
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
  protected readonly outcomes = signal<readonly OutcomeRow[]>([]);
  protected readonly completedCount = signal(0);
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

  protected readonly revenueTrend = computed<readonly ChartSeries[]>(() => [
    dailySeriesToChart(
      this.dailyRows(),
      'revenue.gross.v1',
      this.i18n.t('reports.overview.tile.revenue'),
    ),
  ]);
  protected readonly ordersTrend = computed<readonly ChartSeries[]>(() => [
    dailySeriesToChart(
      this.dailyRows(),
      'orders.count.v1',
      this.i18n.t('reports.overview.tile.orders'),
    ),
  ]);
  protected readonly hasTrend = computed(() => this.dailyRows().length > 0);

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
        this.loadTilesAndMix(scope),
        this.loadPrepTime(scope),
        this.loadOutcomes(scope),
        this.loadBranches(scope),
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
    const channels = await this.channelsApi.list(scope).catch(() => [] as readonly ChannelView[]);
    const channelByCode = new Map(channels.map((channel) => [channel.code, channel]));

    const [currentQuery, comparisonQuery, channelQuery, fulfilmentQuery, lateSample] =
      await Promise.all([
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: [...BAND_A_METRICS],
        }),
        this.api.query(scope.tenantId, {
          from: comparison.from,
          to: comparison.to,
          metric: [...BAND_A_METRICS],
        }),
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['channel_mix.count.v1', 'revenue.gross.v1'],
          groupBy: ['CHANNEL'],
        }),
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['orders.count.v1'],
          groupBy: ['FULFILMENT_TYPE'],
        }),
        this.api.orders(scope.tenantId, {
          from: range.from,
          to: range.to,
          sort: 'LATENESS_DESC',
          limit: 200,
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

  private async loadPrepTime(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const result = await this.api.preparationTime(scope.tenantId, {
      from: range.from,
      to: range.to,
    });
    this.prepMedianSeconds.set(result.medianSeconds);
  }

  private async loadOutcomes(scope: LocationScope): Promise<void> {
    const range = this.filters.range();
    const result = await this.api.orderOutcomes(scope.tenantId, { from: range.from, to: range.to });
    const total = result.rows.reduce((sum, row) => sum + row.count, 0);
    const completed = result.rows
      .filter((row) => row.terminalStatus === 'COMPLETED')
      .reduce((sum, row) => sum + row.count, 0);
    this.completedCount.set(completed);
    this.outcomes.set(
      result.rows
        .filter((row) => CANCELLING_STATUSES.has(row.terminalStatus))
        .map((row) => outcomeRow(row, total)),
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
      groupBy: ['LOCATION'],
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
    };
  }
}

/** One metric's day-by-day series as {@link LineChart} wants it — DD.MM labels, a translated series name. */
function dailySeriesToChart(
  rows: readonly RowResponse[],
  metricCode: string,
  label: string,
): ChartSeries {
  const points: readonly DailyPoint[] = dailySeries(rows, metricCode);
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

function outcomeRow(row: OutcomeRowResponse, total: number): OutcomeRow {
  return {
    status: row.terminalStatus,
    reasonCode: row.cancellationReasonCode,
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
