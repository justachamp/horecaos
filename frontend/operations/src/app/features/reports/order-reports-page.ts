import {
  ChangeDetectionStrategy,
  Component,
  WritableSignal,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { OrderRowsTable, OrderTableColumn } from './order-rows-table';
import { ProvenanceBanner } from './provenance-banner';
import { ddmm, formatCount, formatShare, formatSignedMinutes, median } from './report-formatting';
import { deriveAverageCheck, sumAcrossDays } from './report-rollup';
import { DateRange, ReportsFilterState } from './reports-filter-state';
import { OrderRowResponse, ProvenanceResponse, ReportingApi } from './reporting-api';

type OrderReportTab = 'stages' | 'commercial' | 'daily' | 'summary' | 'late';
type FulfilmentFilter = 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN';
type PivotMeasure = 'count' | 'sum' | 'avgCheck';
type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const TAB_DEFINITIONS: readonly { readonly id: OrderReportTab; readonly labelKey: MessageKey }[] = [
  { id: 'stages', labelKey: 'reports.orders.tab.stages' },
  { id: 'commercial', labelKey: 'reports.orders.tab.commercial' },
  { id: 'daily', labelKey: 'reports.orders.tab.daily' },
  { id: 'summary', labelKey: 'reports.orders.tab.summary' },
  { id: 'late', labelKey: 'reports.orders.tab.late' },
];

const STAGE_COLUMNS: readonly OrderTableColumn[] = [
  'orderId',
  'channel',
  'confirm',
  'ready',
  'total',
];
const COMMERCIAL_COLUMNS: readonly OrderTableColumn[] = [
  'orderId',
  'businessDate',
  'channel',
  'fulfilment',
  'status',
  'gross',
  'discount',
  'deliveryFee',
  'net',
  'items',
];
const LATE_COLUMNS: readonly OrderTableColumn[] = [
  'orderId',
  'late',
  'channel',
  'fulfilment',
  'gross',
  'items',
  'occurredAt',
];

interface DailyRow {
  readonly businessDate: string;
  readonly grossSom: number;
  readonly netSom: number;
  readonly orderCount: number;
  readonly cancelledCount: number;
  readonly averageCheckSom: number | null;
  readonly byFulfilment: Readonly<
    Record<'DELIVERY' | 'PICKUP' | 'DINE_IN', { count: number; grossSom: number }>
  >;
}

interface PivotBucket {
  readonly locationId: string;
  readonly channelCode: string;
  readonly fulfilmentType: string;
  readonly grossSom: number;
  readonly orderCount: number;
}

interface PivotRow {
  readonly locationName: string;
  readonly channelName: string;
  readonly value: number | null;
}

/**
 * 7.2 Order reports (`frontend-information-architecture.md` PART 2 §7, tier
 * P) — statistics.md §2.2. "The per-order evidence behind every number on the
 * overview — the screen you open when a figure looks wrong."
 *
 * Five tabs, matching the IA row's explicit "Owns": per-stage duration
 * («Этапы»), commercial/CRM log («Заказы»), daily operations («Посуточно»),
 * the two roll-ups as one pivot («Сводка»), and delayed orders («Опоздания»).
 * Excel/CSV export is the sixth thing the IA row names and is the one item
 * genuinely not built: ADR 0043's own rollout puts exports last, behind
 * `report.export`/`customer.pii.export` and an async job queue, none of
 * which exist yet — see the export button's own not-built state below.
 *
 * «Этапы»/«Заказы»/«Опоздания» read `GET .../reporting/orders`, a bounded
 * order-grain read (see its own doc) rather than a paginated feed — real data,
 * capped rather than complete for a very wide range. «Посуточно»/«Сводка» read
 * the typed `GET .../reporting/queries`, rolled up client-side across the
 * date axis the API always returns split by; `report-rollup.ts` explains why
 * that roll-up is arithmetic the registry already defines rather than a new
 * aggregate.
 *
 * **«Сводка» is a flat branch×channel table for this wave, not the 2D pivot
 * grid statistics.md §2.2 draws** (rows = branch, columns = channel). The
 * measure and split selectors are real and reactive over one already-fetched,
 * correctly-summed dataset — no additional fetch on either control — only the
 * layout is simplified; a true grid is a template change over the same
 * `pivotRows()` data, not a new data model.
 */
@Component({
  selector: 'q-order-reports-page',
  imports: [TPipe, ProvenanceBanner, OrderRowsTable],
  templateUrl: './order-reports-page.html',
  styleUrl: './order-reports-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderReportsPage {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly filters = inject(ReportsFilterState);
  private readonly i18n = inject(I18n);

  protected readonly tabs = TAB_DEFINITIONS;
  protected readonly activeTab = signal<OrderReportTab>('stages');

  protected readonly state = signal<LoadState>('loading');
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly requestedTo = computed(() => this.filters.range().to);

  private readonly stagesRowsRaw = signal<readonly OrderRowResponse[]>([]);
  private readonly commercialRowsRaw = signal<readonly OrderRowResponse[]>([]);
  private readonly lateRowsRaw = signal<readonly OrderRowResponse[]>([]);
  protected readonly lateMaybeMore = signal(false);

  protected readonly stageColumns = STAGE_COLUMNS;
  protected readonly commercialColumns = COMMERCIAL_COLUMNS;
  protected readonly lateColumns = LATE_COLUMNS;

  protected readonly stagesRows = computed(() =>
    filterByFulfilment(this.stagesRowsRaw(), this.filters.fulfilmentType()),
  );
  protected readonly commercialRows = computed(() =>
    filterByFulfilment(this.commercialRowsRaw(), this.filters.fulfilmentType()),
  );
  protected readonly lateRows = computed(() =>
    filterByFulfilment(this.lateRowsRaw(), this.filters.fulfilmentType()),
  );
  protected readonly lateSummaryLine = computed(() => summariseLate(this.lateRows(), this.i18n));

  protected readonly dailyRows = signal<readonly DailyRow[]>([]);

  private readonly pivotBuckets = signal<readonly PivotBucket[]>([]);
  protected readonly pivotMeasure = signal<PivotMeasure>('sum');
  protected readonly pivotSplit = signal<FulfilmentFilter>('ALL');
  protected readonly pivotRows = computed(() => this.buildPivotRows());

  private locations: readonly LocationView[] = [];
  private channels: readonly ChannelView[] = [];

  constructor() {
    // Re-fetches the active tab whenever the period changes. Switching tabs
    // fetches on demand rather than pre-loading all five: a manager reading
    // «Этапы» most days never opens «Сводка», and statistics.md §3 explicitly
    // says only three of the ten views belong on the daily path.
    effect(() => {
      const range = this.filters.range();
      const tab = this.activeTab();
      void this.loadTab(tab, range);
    });
  }

  protected selectTab(tab: OrderReportTab): void {
    this.activeTab.set(tab);
  }

  protected retry(): void {
    void this.loadTab(this.activeTab(), this.filters.range());
  }

  protected selectPivotMeasure(measure: PivotMeasure): void {
    this.pivotMeasure.set(measure);
  }

  protected selectPivotSplit(split: FulfilmentFilter): void {
    this.pivotSplit.set(split);
  }

  protected formatPivotValue(value: number | null): string {
    if (value === null) {
      return '—';
    }
    return this.pivotMeasure() === 'count'
      ? formatCount(value)
      : formatMoney({ amountMinor: value, currency: 'UZS' }, this.i18n.locale());
  }

  protected ddmm(iso: string): string {
    return ddmm(iso);
  }

  protected money(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale());
  }

  protected count(value: number): string {
    return formatCount(value);
  }

  protected cancelledShare(row: DailyRow): string {
    return formatShare(row.cancelledCount, row.orderCount + row.cancelledCount);
  }

  private async loadTab(tab: OrderReportTab, range: DateRange): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }

    this.state.set('loading');
    try {
      if (this.locations.length === 0) {
        this.locations = await this.locationsApi.list(scope).catch(() => []);
        this.channels = await this.channelsApi.list(scope).catch(() => []);
      }

      switch (tab) {
        case 'stages':
          await this.loadOrders(scope, range, 'DURATION_DESC', this.stagesRowsRaw);
          break;
        case 'commercial':
          await this.loadOrders(scope, range, 'DATE_DESC', this.commercialRowsRaw);
          break;
        case 'late':
          await this.loadOrders(scope, range, 'LATENESS_DESC', this.lateRowsRaw);
          break;
        case 'daily':
          await this.loadDaily(scope, range);
          break;
        case 'summary':
          await this.loadSummary(scope, range);
          break;
      }
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

  private async loadOrders(
    scope: LocationScope,
    range: DateRange,
    sort: 'DATE_DESC' | 'DURATION_DESC' | 'LATENESS_DESC',
    target: WritableSignal<readonly OrderRowResponse[]>,
  ): Promise<void> {
    const result = await this.api.orders(scope.tenantId, {
      from: range.from,
      to: range.to,
      sort,
      limit: 200,
    });
    target.set(result.rows);
    this.lateMaybeMore.set(sort === 'LATENESS_DESC' ? result.maybeMore : this.lateMaybeMore());
    this.provenance.set(result.provenance);
  }

  private async loadDaily(scope: LocationScope, range: DateRange): Promise<void> {
    const [totals, byFulfilment] = await Promise.all([
      this.api.query(scope.tenantId, {
        from: range.from,
        to: range.to,
        metric: [
          'revenue.gross.v1',
          'revenue.net.v1',
          'orders.count.v1',
          'orders.cancelled.v1',
          'average_check.v1',
        ],
      }),
      this.api.query(scope.tenantId, {
        from: range.from,
        to: range.to,
        metric: ['orders.count.v1', 'revenue.gross.v1'],
        groupBy: ['FULFILMENT_TYPE'],
      }),
    ]);
    this.provenance.set(totals.provenance);

    const byDate = new Map<string, DailyRow>();
    for (const row of totals.rows) {
      byDate.set(row.businessDate, {
        businessDate: row.businessDate,
        grossSom: row.values['revenue.gross.v1'] ?? 0,
        netSom: row.values['revenue.net.v1'] ?? 0,
        orderCount: row.values['orders.count.v1'] ?? 0,
        cancelledCount: row.values['orders.cancelled.v1'] ?? 0,
        averageCheckSom: row.values['average_check.v1'] ?? null,
        byFulfilment: emptyFulfilmentBreakdown(),
      });
    }
    for (const row of byFulfilment.rows) {
      const existing = byDate.get(row.businessDate);
      if (!existing || row.fulfilmentType === null) {
        continue;
      }
      const type = row.fulfilmentType as 'DELIVERY' | 'PICKUP' | 'DINE_IN';
      byDate.set(row.businessDate, {
        ...existing,
        byFulfilment: {
          ...existing.byFulfilment,
          [type]: {
            count: row.values['orders.count.v1'] ?? 0,
            grossSom: row.values['revenue.gross.v1'] ?? 0,
          },
        },
      });
    }
    this.dailyRows.set(
      [...byDate.values()].sort((a, b) => (a.businessDate < b.businessDate ? 1 : -1)),
    );
  }

  private async loadSummary(scope: LocationScope, range: DateRange): Promise<void> {
    const result = await this.api.query(scope.tenantId, {
      from: range.from,
      to: range.to,
      metric: ['revenue.gross.v1', 'orders.count.v1'],
      groupBy: ['LOCATION', 'CHANNEL', 'FULFILMENT_TYPE'],
    });
    this.provenance.set(result.provenance);

    const buckets = sumAcrossDays(
      result.rows,
      (row) => `${row.locationId}|${row.channelCode}|${row.fulfilmentType}`,
      ['revenue.gross.v1', 'orders.count.v1'],
    );
    this.pivotBuckets.set(
      [...buckets.entries()].map(([key, values]) => {
        const [locationId, channelCode, fulfilmentType] = key.split('|');
        return {
          locationId,
          channelCode,
          fulfilmentType,
          grossSom: values['revenue.gross.v1'],
          orderCount: values['orders.count.v1'],
        };
      }),
    );
  }

  private buildPivotRows(): readonly PivotRow[] {
    const split = this.pivotSplit();
    const measure = this.pivotMeasure();
    const nameOfLocation = new Map(this.locations.map((l) => [l.id, l.displayName]));
    const nameOfChannel = new Map(this.channels.map((c) => [c.code, c.displayName]));

    const matching = this.pivotBuckets().filter(
      (b) => split === 'ALL' || b.fulfilmentType === split,
    );
    const rolled = new Map<string, { grossSom: number; orderCount: number }>();
    for (const bucket of matching) {
      const key = `${bucket.locationId}|${bucket.channelCode}`;
      const existing = rolled.get(key) ?? { grossSom: 0, orderCount: 0 };
      rolled.set(key, {
        grossSom: existing.grossSom + bucket.grossSom,
        orderCount: existing.orderCount + bucket.orderCount,
      });
    }

    return [...rolled.entries()]
      .map(([key, values]) => {
        const [locationId, channelCode] = key.split('|');
        const value =
          measure === 'count'
            ? values.orderCount
            : measure === 'sum'
              ? values.grossSom
              : deriveAverageCheck(values.grossSom, values.orderCount);
        return {
          locationName: nameOfLocation.get(locationId) ?? locationId,
          channelName: nameOfChannel.get(channelCode) ?? channelCode,
          value,
        };
      })
      .sort(
        (a, b) =>
          a.locationName.localeCompare(b.locationName) ||
          a.channelName.localeCompare(b.channelName),
      );
  }
}

function filterByFulfilment(
  rows: readonly OrderRowResponse[],
  filter: FulfilmentFilter,
): readonly OrderRowResponse[] {
  return filter === 'ALL' ? rows : rows.filter((row) => row.fulfilmentType === filter);
}

function summariseLate(rows: readonly OrderRowResponse[], i18n: I18n): string | null {
  if (rows.length === 0) {
    return null;
  }
  const values = rows
    .map((row) => row.secondsLate)
    .filter((value): value is number => value !== null);
  const med = median(values);
  const worst = values.length > 0 ? Math.max(...values) : null;
  return i18n.t('reports.orders.late.summary', {
    count: rows.length,
    median: med === null ? '—' : formatSignedMinutes(med, i18n.t('reports.unit.minutes')),
    worst: worst === null ? '—' : formatSignedMinutes(worst, i18n.t('reports.unit.minutes')),
  });
}

function emptyFulfilmentBreakdown(): DailyRow['byFulfilment'] {
  return {
    DELIVERY: { count: 0, grossSom: 0 },
    PICKUP: { count: 0, grossSom: 0 },
    DINE_IN: { count: 0, grossSom: 0 },
  };
}
