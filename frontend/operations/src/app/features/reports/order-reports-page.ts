import {
  ChangeDetectionStrategy,
  Component,
  WritableSignal,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

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

/**
 * «Этапы»: `ready` used to sit next to `confirm` and read as one "kitchen
 * time" span — really CONFIRMED -> READY, wider than the spec's
 * «Приготовлен». Wave P27 splits it into `accept` (CONFIRMED -> PREPARING,
 * the branch-acceptance wait) and `cooking` (PREPARING -> READY, actual
 * cooking) — see `order-rows-table.ts`'s own doc.
 */
const STAGE_COLUMNS: readonly OrderTableColumn[] = [
  'orderId',
  'channel',
  'confirm',
  'accept',
  'cooking',
  'total',
];

/** «Заказы»: wave P27 adds branch, «Предзаказ» and the public order number (via `orderId`'s own rendering). */
const COMMERCIAL_COLUMNS: readonly OrderTableColumn[] = [
  'orderId',
  'businessDate',
  'branch',
  'channel',
  'fulfilment',
  'preorder',
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
  /** Wave P27 (7.2b): per-3PL counts — one entry per aggregator channel code, keyed by `AggregatorChannel.code`. */
  readonly byAggregator: Readonly<Record<string, { count: number; grossSom: number }>>;
}

/** Wave P27 (7.2b): one channel whose `systemType` marks it an aggregator — the «per-3PL counts» the IA names. */
interface AggregatorChannel {
  readonly code: string;
  readonly displayName: string;
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
 * Excel/CSV export is the sixth thing the IA row names — wave P28 builds the
 * export centre (`/statistics/exports`) behind `report.export`/
 * `customer.pii.export` and an async job queue; the Export button here links
 * there rather than triggering an order-log export of its own, since no
 * export source is registered for the order log yet
 * (`ReportExportRegistry`'s own doc names `CUSTOMER_DIRECTORY` as the one
 * report wired end to end so far).
 *
 * «Этапы»/«Заказы»/«Опоздания» read `GET .../reporting/orders`, a bounded
 * order-grain read (see its own doc) rather than a paginated feed — real data,
 * capped rather than complete for a very wide range. «Посуточно»/«Сводка» read
 * the typed `GET .../reporting/queries`, rolled up client-side across the
 * date axis the API always returns split by; `report-rollup.ts` explains why
 * that roll-up is arithmetic the registry already defines rather than a new
 * aggregate.
 *
 * **Wave P27.** The fulfilment axis is now pushed into `/orders` and
 * `/queries` themselves (`fulfilmentType`/`FULFILMENT_TYPE`) rather than
 * filtered client-side over an already-fetched page — the exact bug this
 * wave's brief names by name. Branch, channel and legal-entity filters from
 * `ReportsFilterState` reach every read this page makes, and money queries
 * always name `LEGAL_ENTITY` (ADR 0038; see `business-overview-page.ts`'s
 * own doc for why). «Этапы» no longer conflates the branch-acceptance wait
 * with cooking (`accept`/`cooking` columns, `order-rows-table.ts`'s own
 * doc). «Заказы» stopped discarding the server's `maybeMore`, adds branch
 * (`Филиал`), «Предзаказ» and the public order number, and pages past its
 * 200-row cap with the server's own keyset cursor rather than a second,
 * silently-incomplete fetch. «Посуточно» adds a per-aggregator column
 * (`groupBy: ['CHANNEL']`, no new endpoint) for every channel whose
 * `systemType` marks it an aggregator.
 *
 * **«Сводка» is a flat branch×channel table for this wave, not the 2D pivot
 * grid statistics.md §2.2 draws** (rows = branch, columns = channel). The
 * measure and split selectors are real and reactive over one already-fetched,
 * correctly-summed dataset — no additional fetch on either control — only the
 * layout is simplified; a true grid is a template change over the same
 * `pivotRows()` data, not a new data model. Saved views and a column chooser
 * are out of this wave's scope — noted rather than silently missing.
 */
@Component({
  selector: 'q-order-reports-page',
  imports: [TPipe, RouterLink, ProvenanceBanner, OrderRowsTable],
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

  private readonly stagesRows_ = signal<readonly OrderRowResponse[]>([]);
  private readonly commercialRows_ = signal<readonly OrderRowResponse[]>([]);
  private readonly lateRows_ = signal<readonly OrderRowResponse[]>([]);
  protected readonly lateMaybeMore = signal(false);
  /** Wave P27: «Этапы» used to discard `maybeMore` entirely — now shown the same as «Опоздания» already was. */
  protected readonly stagesMaybeMore = signal(false);
  /** Wave P27 (7.2a): «Заказы»'s own bounded page, plus whether a cursor page beyond it exists. */
  protected readonly commercialMaybeMore = signal(false);
  private commercialCursor: { readonly occurredAt: string; readonly orderId: string } | null = null;
  protected readonly commercialLoadingMore = signal(false);

  protected readonly stageColumns = STAGE_COLUMNS;
  protected readonly commercialColumns = COMMERCIAL_COLUMNS;
  protected readonly lateColumns = LATE_COLUMNS;

  // Wave P27: the fulfilment axis moved into the query itself (see
  // `loadOrders` below); these are now the server's own rows, unfiltered a
  // second time client-side.
  protected readonly stagesRows = this.stagesRows_.asReadonly();
  protected readonly commercialRows = this.commercialRows_.asReadonly();
  protected readonly lateRows = this.lateRows_.asReadonly();
  protected readonly lateSummaryLine = computed(() => summariseLate(this.lateRows(), this.i18n));

  protected readonly dailyRows = signal<readonly DailyRow[]>([]);
  protected readonly aggregatorChannels = signal<readonly AggregatorChannel[]>([]);

  private readonly pivotBuckets = signal<readonly PivotBucket[]>([]);
  protected readonly pivotMeasure = signal<PivotMeasure>('sum');
  protected readonly pivotSplit = signal<FulfilmentFilter>('ALL');
  protected readonly pivotRows = computed(() => this.buildPivotRows());

  private locations: readonly LocationView[] = [];
  private channels: readonly ChannelView[] = [];
  /** Wave P27 (7.2a): «Филиал» — resolved once per load, from the same location list every other tab already fetches. */
  protected readonly locationNames = signal<ReadonlyMap<string, string>>(new Map());

  constructor() {
    // Re-fetches the active tab whenever the period or the shared filter
    // state changes. Switching tabs fetches on demand rather than
    // pre-loading all five: a manager reading «Этапы» most days never opens
    // «Сводка», and statistics.md §3 explicitly says only three of the ten
    // views belong on the daily path.
    effect(() => {
      const range = this.filters.range();
      const tab = this.activeTab();
      // Read every slice axis so this effect re-fires when any of them
      // changes — the whole point of "every page consumes the shared state".
      this.filters.fulfilmentType();
      this.filters.locationIds();
      this.filters.channelCodes();
      this.filters.legalEntityIds();
      this.commercialCursor = null;
      void this.loadTab(tab, range);
    });
  }

  protected selectTab(tab: OrderReportTab): void {
    this.activeTab.set(tab);
  }

  protected retry(): void {
    void this.loadTab(this.activeTab(), this.filters.range());
  }

  /** Wave P27 (7.2a): the server's own keyset cursor past «Заказы»'s 200-row cap. */
  protected async loadMoreCommercial(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.commercialCursor === null || this.commercialLoadingMore()) {
      return;
    }
    this.commercialLoadingMore.set(true);
    try {
      const range = this.filters.range();
      const result = await this.api.orders(scope.tenantId, {
        from: range.from,
        to: range.to,
        sort: 'DATE_DESC',
        limit: 200,
        locationId: this.slice().locationId,
        channelCode: this.slice().channelCode,
        fulfilmentType: this.fulfilmentTypeParam(),
        legalEntityId: this.slice().legalEntityId,
        afterOccurredAt: this.commercialCursor.occurredAt,
        afterOrderId: this.commercialCursor.orderId,
      });
      this.commercialRows_.update((existing) => [...existing, ...result.rows]);
      this.commercialMaybeMore.set(result.maybeMore);
      this.commercialCursor = cursorOf(result.rows);
    } finally {
      this.commercialLoadingMore.set(false);
    }
  }

  /** Wave P27 (7.1d): branch/channel/legal-entity, in the shape every read on this page shares. */
  private slice(): {
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

  private fulfilmentTypeParam(): readonly string[] | undefined {
    const type = this.filters.fulfilmentType();
    return type === 'ALL' ? undefined : [type];
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
        this.locationNames.set(new Map(this.locations.map((loc) => [loc.id, loc.displayName])));
        this.aggregatorChannels.set(
          this.channels
            .filter((channel) => channel.systemType.toUpperCase().includes('AGGREGATOR'))
            .map((channel) => ({ code: channel.code, displayName: channel.displayName })),
        );
      }

      switch (tab) {
        case 'stages':
          await this.loadOrders(
            scope,
            range,
            'DURATION_DESC',
            this.stagesRows_,
            this.stagesMaybeMore,
          );
          break;
        case 'commercial':
          await this.loadOrders(
            scope,
            range,
            'DATE_DESC',
            this.commercialRows_,
            this.commercialMaybeMore,
          );
          this.commercialCursor = cursorOf(this.commercialRows_());
          break;
        case 'late':
          await this.loadOrders(scope, range, 'LATENESS_DESC', this.lateRows_, this.lateMaybeMore);
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
    maybeMoreTarget: WritableSignal<boolean>,
  ): Promise<void> {
    const slice = this.slice();
    const result = await this.api.orders(scope.tenantId, {
      from: range.from,
      to: range.to,
      sort,
      limit: 200,
      locationId: slice.locationId,
      channelCode: slice.channelCode,
      fulfilmentType: this.fulfilmentTypeParam(),
      legalEntityId: slice.legalEntityId,
    });
    target.set(result.rows);
    maybeMoreTarget.set(result.maybeMore);
    this.provenance.set(result.provenance);
  }

  private async loadDaily(scope: LocationScope, range: DateRange): Promise<void> {
    const slice = this.slice();
    const [totals, byFulfilment, byChannel] = await Promise.all([
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
        // LEGAL_ENTITY is always named: this mixes money and count metrics,
        // and a money metric queried without it on a two-entity tenant
        // throws CombinedEntityTotalException (ADR 0038). Folded back into
        // one figure by `byDate`'s own accumulation below, the same
        // transparent fold `business-overview-page.ts` uses.
        groupBy: ['LEGAL_ENTITY'],
        ...slice,
      }),
      this.api.query(scope.tenantId, {
        from: range.from,
        to: range.to,
        metric: ['orders.count.v1', 'revenue.gross.v1'],
        groupBy: ['FULFILMENT_TYPE', 'LEGAL_ENTITY'],
        ...slice,
      }),
      // Wave P27 (7.2b): the per-3PL counts — no new endpoint, the same
      // typed query grouped by CHANNEL instead of FULFILMENT_TYPE.
      this.api.query(scope.tenantId, {
        from: range.from,
        to: range.to,
        metric: ['orders.count.v1', 'revenue.gross.v1'],
        groupBy: ['CHANNEL', 'LEGAL_ENTITY'],
        ...slice,
      }),
    ]);
    this.provenance.set(totals.provenance);

    // Every query above now also groups by LEGAL_ENTITY (ADR 0038), so a
    // two-entity tenant returns more than one row per business date — sumAcrossDays
    // folds those back into one figure per date, the same transparent fold
    // `business-overview-page.ts` uses, rather than the last row silently
    // overwriting the ones before it.
    const totalsByDate = sumAcrossDays(totals.rows, (row) => row.businessDate, [
      'revenue.gross.v1',
      'revenue.net.v1',
      'orders.count.v1',
      'orders.cancelled.v1',
    ]);
    const byDate = new Map<string, DailyRow>();
    for (const [businessDate, values] of totalsByDate.entries()) {
      byDate.set(businessDate, {
        businessDate,
        grossSom: values['revenue.gross.v1'],
        netSom: values['revenue.net.v1'],
        orderCount: values['orders.count.v1'],
        cancelledCount: values['orders.cancelled.v1'],
        averageCheckSom: deriveAverageCheck(values['revenue.gross.v1'], values['orders.count.v1']),
        byFulfilment: emptyFulfilmentBreakdown(),
        byAggregator: {},
      });
    }

    const fulfilmentByDateAndType = sumAcrossDays(
      byFulfilment.rows,
      (row) => `${row.businessDate}|${row.fulfilmentType}`,
      ['orders.count.v1', 'revenue.gross.v1'],
    );
    for (const [key, values] of fulfilmentByDateAndType.entries()) {
      const [businessDate, fulfilmentType] = key.split('|');
      const existing = byDate.get(businessDate);
      if (!existing || fulfilmentType === 'null') {
        continue;
      }
      const type = fulfilmentType as 'DELIVERY' | 'PICKUP' | 'DINE_IN';
      byDate.set(businessDate, {
        ...existing,
        byFulfilment: {
          ...existing.byFulfilment,
          [type]: { count: values['orders.count.v1'], grossSom: values['revenue.gross.v1'] },
        },
      });
    }

    const aggregatorCodes = new Set(this.aggregatorChannels().map((channel) => channel.code));
    const channelByDateAndCode = sumAcrossDays(
      byChannel.rows,
      (row) => `${row.businessDate}|${row.channelCode}`,
      ['orders.count.v1', 'revenue.gross.v1'],
    );
    for (const [key, values] of channelByDateAndCode.entries()) {
      const [businessDate, channelCode] = key.split('|');
      const existing = byDate.get(businessDate);
      if (!existing || !aggregatorCodes.has(channelCode)) {
        continue;
      }
      byDate.set(businessDate, {
        ...existing,
        byAggregator: {
          ...existing.byAggregator,
          [channelCode]: { count: values['orders.count.v1'], grossSom: values['revenue.gross.v1'] },
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
      // LEGAL_ENTITY joins the other three axes for the same ADR 0038 reason
      // every money query on this page names it now; sumAcrossDays below
      // folds it back out since «Сводка» does not split by entity.
      groupBy: ['LOCATION', 'CHANNEL', 'FULFILMENT_TYPE', 'LEGAL_ENTITY'],
      ...this.slice(),
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

/** Wave P27 (7.2a): the keyset cursor for the next «Заказы» page — DATE_DESC's own sort key, off the last row. */
function cursorOf(
  rows: readonly OrderRowResponse[],
): { readonly occurredAt: string; readonly orderId: string } | null {
  const last = rows.at(-1);
  return last ? { occurredAt: last.occurredAt, orderId: last.orderId } : null;
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
