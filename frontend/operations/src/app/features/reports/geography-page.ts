import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChartCategory, ChartHeatRow } from '../../shared/ui/charts/chart-model';
import { HeatmapChart } from '../../shared/ui/charts/heatmap-chart';
import { HistogramChart } from '../../shared/ui/charts/histogram-chart';
import { LocationsApi, LocationView } from '../settings/locations/locations-api';
import { OrderRowsTable, OrderTableColumn } from './order-rows-table';
import { ProvenanceBanner } from './provenance-banner';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';
import { formatCount } from './report-formatting';
import {
  DemandHistoryResponse,
  OrderRowResponse,
  ProvenanceResponse,
  ReportingApi,
} from './reporting-api';

/**
 * `sla_bucket_set.v1` — see `branch-sla-report-page.ts`'s own doc for what
 * these mean. Duplicated locally the same way `courier-report-page.ts`
 * already duplicates it, rather than importing across two sibling report
 * pages for a six-item constant.
 */
const SLA_BUCKETS = ['UNDER_30', 'M30_35', 'M35_40', 'M40_50', 'M50_60', 'OVER_60'] as const;
type SlaBucketCode = (typeof SLA_BUCKETS)[number];
const SLA_BUCKET_LABEL_KEYS: Readonly<Record<SlaBucketCode, MessageKey>> = {
  UNDER_30: 'reports.branches.sla.bucket.UNDER_30',
  M30_35: 'reports.branches.sla.bucket.M30_35',
  M35_40: 'reports.branches.sla.bucket.M35_40',
  M40_50: 'reports.branches.sla.bucket.M40_50',
  M50_60: 'reports.branches.sla.bucket.M50_60',
  OVER_60: 'reports.branches.sla.bucket.OVER_60',
};

/** ISO-8601: 1 = Monday .. 7 = Sunday, matching `demand-forecast-page.ts`'s own `WEEKDAYS`. */
const WEEKDAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

/** Reuses 7.8's own weekday names — plain calendar names, not forecast-specific copy. */
const WEEKDAY_LABEL_KEYS: Readonly<Record<number, MessageKey>> = {
  1: 'reports.forecast.weekday.1',
  2: 'reports.forecast.weekday.2',
  3: 'reports.forecast.weekday.3',
  4: 'reports.forecast.weekday.4',
  5: 'reports.forecast.weekday.5',
  6: 'reports.forecast.weekday.6',
  7: 'reports.forecast.weekday.7',
};

/** How many of the location's most recent occurrences of a weekday to average over — same options 7.8 offers. */
const SAMPLE_SIZE_OPTIONS: readonly number[] = [4, 8, 12];

/** The histogram section's own fixed window — a first cut, not yet wired to `ReportsFilterState` (see this file's own doc). */
const HISTOGRAM_WINDOW_DAYS = 30;

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type SecondaryLoadState = 'idle' | 'loading' | 'ready' | 'error';

/** One matched drill-down: which cell was clicked, and what `/reporting/orders` turned up for it. */
interface DrillDown {
  readonly weekday: number;
  readonly hour: number;
  /** `18:00–19:00`, operating-day-relative — see {@link hourWindowLabel}. Computed once at click time, not re-derived in the template. */
  readonly hourLabel: string;
  readonly rows: readonly OrderRowResponse[];
  /** True when the underlying 300-row read came back full — there may be more matches this drill-down missed. */
  readonly maybeIncomplete: boolean;
}

/**
 * IA §7.10 Geography (wave W04, rows 7.10b/7.10c) — the section's first
 * honest surfacing: no route, no nav tab and no placeholder existed before
 * this wave, unlike every other unbuilt reports section.
 *
 * **What ships.** 7.10b — a handover-time histogram, and 7.10c — the
 * day-of-week × hour cohort grid with cell drill-down. **What stays
 * deferred** (named on screen, never silently dropped): the order-density
 * heatmap and today's-orders-as-pins rows (7.10/7.10a), both blocked on a
 * map provider decision tracked under `X.4`.
 *
 * **7.10b's histogram is two charts, not one, and only one of them is
 * real today.** `reporting.fact_delivery` (T11, ADR 0125) now carries a raw
 * `distance_meters`/`transit_seconds` per delivery, but nothing buckets it —
 * `CourierReportController` only ever answers a per-courier *aggregate*
 * (min/max/avg/total) or the fixed six-bucket SLA distribution, never a
 * distance histogram. Building that bucketing is new backend surface this
 * wave does not add (no migration, no new endpoint — see this wave's own
 * brief), so the distance chart names exactly what is missing rather than
 * rendering nothing. The duration chart is real: `GET
 * .../reporting/sla-buckets`, the same `sla_bucket_set.v1` six buckets
 * `branch-sla-report-page.ts` already renders as a table, narrowed to the
 * one selected branch and rendered as a histogram instead — no new
 * endpoint, no new SQL.
 *
 * **7.10c is the cheapest row in the whole section**: seven
 * `GET .../reporting/demand-history` calls, one per weekday, for the
 * selected branch, coloured by `averageOrders` — the exact shape
 * `demand-forecast-page.ts`'s own opt-in week overview (IA X.19) already
 * proves, fetched eagerly here because the grid *is* this row's whole
 * point rather than one of several sections on a page. Cell drill-down is
 * the one new client behaviour: `/reporting/orders` has no weekday or
 * hour-of-day filter, so the response's own `occurredAt` is filtered
 * client-side against the clicked cell's weekday/hour, over the exact
 * `sampleDates` that weekday's own `demandHistory` response already named,
 * bounded to the most recent 300 orders in that span — honestly labelled
 * as possibly incomplete when the underlying read itself came back full
 * (`maybeMore`).
 *
 * **Filtering.** Unlike most of §7, this page does not read
 * `ReportsFilterState` at all — both rows are single-branch analyses (the
 * histogram over a fixed recent window, the cohort grid over "the most
 * recent N occurrences of a weekday"), the same reasoning
 * `demand-forecast-page.ts`'s own doc gives for opting out of the shared
 * bar entirely; `reports-shell.html` hides the bar on this route the same
 * way it already does on `/statistics/forecast`.
 */
@Component({
  selector: 'q-geography-page',
  imports: [TPipe, ProvenanceBanner, HistogramChart, HeatmapChart, OrderRowsTable],
  templateUrl: './geography-page.html',
  styleUrl: './geography-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeographyPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly api = inject(ReportingApi);
  protected readonly i18n = inject(I18n);

  protected readonly WEEKDAYS = WEEKDAYS;
  protected readonly SAMPLE_SIZE_OPTIONS = SAMPLE_SIZE_OPTIONS;

  protected readonly state = signal<LoadState>('loading');
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly selectedLocationId = signal<string | null>(null);

  // ---------------------------------------------------------------- 7.10b
  protected readonly histogramState = signal<SecondaryLoadState>('idle');
  protected readonly durationBuckets = signal<readonly ChartCategory[]>([]);
  protected readonly histogramProvenance = signal<ProvenanceResponse | null>(null);

  // ---------------------------------------------------------------- 7.10c
  protected readonly sampleSize = signal<number>(SAMPLE_SIZE_OPTIONS[0]);
  protected readonly weekGridState = signal<SecondaryLoadState>('idle');
  protected readonly weekGrid = signal<readonly ChartHeatRow[] | null>(null);
  private readonly weekGridResponses = signal<readonly DemandHistoryResponse[]>([]);
  protected readonly drillDown = signal<DrillDown | null>(null);
  protected readonly drillDownLoading = signal(false);

  protected readonly formatCount = formatCount;
  /** The drill-down list's own columns — enough to place an order in time without repeating what the cell already said. */
  protected readonly drillDownColumns: readonly OrderTableColumn[] = [
    'orderId',
    'occurredAt',
    'channel',
    'fulfilment',
    'status',
    'total',
  ];

  private scope: LocationScope | null = null;

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.scope = scope;
    this.selectedLocationId.set(scope.locationId);
    await this.loadBranchOptions(scope);
    this.state.set('ready');
    void this.loadHistogram();
    void this.loadWeekGrid();
  }

  protected weekdayLabel(weekday: number): string {
    return this.i18n.t(WEEKDAY_LABEL_KEYS[weekday]);
  }

  protected bucketLabel(bucket: SlaBucketCode): string {
    return this.i18n.t(SLA_BUCKET_LABEL_KEYS[bucket]);
  }

  protected selectBranch(locationId: string): void {
    if (locationId === this.selectedLocationId()) {
      return;
    }
    this.selectedLocationId.set(locationId);
    this.drillDown.set(null);
    void this.loadHistogram();
    void this.loadWeekGrid();
  }

  protected selectSampleSize(size: number): void {
    if (size === this.sampleSize()) {
      return;
    }
    this.sampleSize.set(size);
    void this.loadWeekGrid();
  }

  protected retryHistogram(): void {
    void this.loadHistogram();
  }

  protected retryWeekGrid(): void {
    void this.loadWeekGrid();
  }

  protected closeDrillDown(): void {
    this.drillDown.set(null);
  }

  /** Bound to `q-heatmap-chart`'s `(cellClicked)` — `rowKey` is the weekday, `colKey` the operating hour, both as strings. */
  protected onCellClicked(event: { readonly rowKey: string; readonly colKey: string }): void {
    void this.loadDrillDown(Number(event.rowKey), Number(event.colKey));
  }

  private async loadBranchOptions(scope: LocationScope): Promise<void> {
    try {
      this.locations.set(await this.locationsApi.list(scope));
    } catch {
      // A single-location tenant, or the list call failed: either way the
      // branch control hides itself and the screen still works for the
      // operator's own location — same stance `demand-forecast-page.ts` takes.
      this.locations.set([]);
    }
  }

  private async loadHistogram(): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    if (!scope || !locationId) {
      return;
    }
    this.histogramState.set('loading');
    try {
      const to = todayIso();
      const from = daysAgoIso(HISTOGRAM_WINDOW_DAYS - 1);
      const result = await this.api.slaBuckets(scope.tenantId, {
        from,
        to,
        locationId: [locationId],
      });
      this.histogramProvenance.set(result.provenance);
      const totals = new Map<SlaBucketCode, number>(SLA_BUCKETS.map((code) => [code, 0]));
      for (const bucket of result.buckets) {
        if ((SLA_BUCKETS as readonly string[]).includes(bucket.bucketCode)) {
          const code = bucket.bucketCode as SlaBucketCode;
          totals.set(code, (totals.get(code) ?? 0) + bucket.orderCount);
        }
      }
      this.durationBuckets.set(
        SLA_BUCKETS.map((code) => ({
          key: code,
          label: this.bucketLabel(code),
          value: totals.get(code) ?? 0,
        })),
      );
      this.histogramState.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        this.histogramState.set('error');
      } else {
        throw error;
      }
    }
  }

  private async loadWeekGrid(): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    if (!scope || !locationId) {
      return;
    }
    this.weekGridState.set('loading');
    this.drillDown.set(null);
    try {
      const responses = await Promise.all(
        WEEKDAYS.map((weekday) =>
          this.api.demandHistory(scope.tenantId, {
            locationId,
            weekday,
            sampleSize: this.sampleSize(),
          }),
        ),
      );
      this.weekGridResponses.set(responses);
      this.weekGrid.set(
        WEEKDAYS.map((weekday, index) => ({
          key: String(weekday),
          label: this.weekdayLabel(weekday),
          cells: responses[index].hours.map((hour) => ({
            key: String(hour.hourOfDay),
            label: hourWindowLabel(responses[index].provenance.businessDayStart, hour.hourOfDay),
            value: hour.averageOrders,
          })),
        })),
      );
      this.weekGridState.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        this.weekGridState.set('error');
      } else {
        throw error;
      }
    }
  }

  private async loadDrillDown(weekday: number, hour: number): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    const response = this.weekGridResponses().find((r) => r.weekday === weekday);
    if (!scope || !locationId || !response || response.sampleDates.length === 0) {
      return;
    }
    const hourLabel = hourWindowLabel(response.provenance.businessDayStart, hour);
    this.drillDownLoading.set(true);
    this.drillDown.set({ weekday, hour, hourLabel, rows: [], maybeIncomplete: false });
    try {
      const sorted = [...response.sampleDates].sort();
      const result = await this.api.orders(scope.tenantId, {
        from: sorted[0],
        to: sorted[sorted.length - 1],
        locationId: [locationId],
        sort: 'DATE_DESC',
        limit: 300,
      });
      const sampleDateSet = new Set(response.sampleDates);
      const zone = response.provenance.timezone;
      const businessDayStart = response.provenance.businessDayStart;
      const matched = result.rows.filter(
        (row) =>
          sampleDateSet.has(row.businessDate) &&
          operatingHourOf(row.occurredAt, zone, businessDayStart) === hour,
      );
      this.drillDown.set({
        weekday,
        hour,
        hourLabel,
        rows: matched,
        maybeIncomplete: result.maybeMore,
      });
    } catch (error) {
      if (error instanceof ApiError) {
        this.drillDown.set(null);
      } else {
        throw error;
      }
    } finally {
      this.drillDownLoading.set(false);
    }
  }
}

/**
 * `18:00–19:00` (or crossing midnight for a non-midnight tenant) —
 * operating-day-relative, mirroring `demand-forecast-page.ts`'s own instance
 * method but as a pure function of the response's own `businessDayStart`,
 * since the cohort grid holds seven independent responses rather than one.
 */
function hourWindowLabel(businessDayStart: string, operatingHour: number): string {
  const [startHour, startMinute] = businessDayStart.split(':').map(Number);
  const startTotal = ((startHour || 0) * 60 + (startMinute || 0)) % (24 * 60);
  const windowStart = (startTotal + operatingHour * 60) % (24 * 60);
  const windowEnd = (windowStart + 60) % (24 * 60);
  return `${clockLabel(windowStart)}–${clockLabel(windowEnd)}`;
}

/** `HH:mm` from a minute-of-day count, wrapped to 24h. */
function clockLabel(totalMinutes: number): string {
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/**
 * The inverse of `demand-forecast-page.ts`'s own `hourWindowLabel`: given an
 * order's `occurredAt` (a UTC instant) and the same `provenance.timezone` /
 * `provenance.businessDayStart` the cohort grid's own hour axis is built
 * from, returns which operating hour (0..23, relative to the business-day
 * start) that instant falls in — so a cell drill-down matches exactly the
 * same hour the clicked cell was shaded for, not the wall-clock hour.
 */
function operatingHourOf(occurredAtIso: string, zone: string, businessDayStart: string): number {
  const [localHour, localMinute] = formatTime(new Date(occurredAtIso), zone).split(':').map(Number);
  const totalMinutes = localHour * 60 + localMinute;
  const [startHour, startMinute] = businessDayStart.split(':').map(Number);
  const startTotal = ((startHour || 0) * 60 + (startMinute || 0)) % (24 * 60);
  const diff = (((totalMinutes - startTotal) % (24 * 60)) + 24 * 60) % (24 * 60);
  return Math.floor(diff / 60);
}

/** `YYYY-MM-DD` for "today" in the reports placeholder zone — mirrors `reports-filter-state.ts`'s own private `todayIn`. */
function todayIso(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: REPORTS_PLACEHOLDER_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

/** `YYYY-MM-DD` for `days` days before today, in the same zone. */
function daysAgoIso(days: number): string {
  const [year, month, day] = todayIso().split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}
