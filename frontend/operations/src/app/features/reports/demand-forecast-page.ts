import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { HeatmapChart } from '../../shared/ui/charts/heatmap-chart';
import { ChartHeatRow } from '../../shared/ui/charts/chart-model';
import { LocationsApi, LocationView } from '../settings/locations/locations-api';
import { ProvenanceBanner } from './provenance-banner';
import { ddmmyyyy, formatAverage } from './report-formatting';
import {
  DemandForecastBreakdownResponse,
  DemandForecastResponse,
  DemandHistoryResponse,
  HolidayMode,
  ReportingApi,
} from './reporting-api';

/** ISO-8601: 1 = Monday .. 7 = Sunday, matching `kitchen.station_capacity.weekday` (V0144). */
const WEEKDAYS: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

const WEEKDAY_LABEL_KEYS: Readonly<Record<number, MessageKey>> = {
  1: 'reports.forecast.weekday.1',
  2: 'reports.forecast.weekday.2',
  3: 'reports.forecast.weekday.3',
  4: 'reports.forecast.weekday.4',
  5: 'reports.forecast.weekday.5',
  6: 'reports.forecast.weekday.6',
  7: 'reports.forecast.weekday.7',
};

/** How many of the location's most recent occurrences of a weekday to offer averaging over. */
const SAMPLE_SIZE_OPTIONS: readonly number[] = [4, 8, 12];

const HOURS: readonly number[] = Array.from({ length: 24 }, (_unused, hour) => hour);

const HOLIDAY_MODES: readonly HolidayMode[] = ['INCLUDE', 'EXCLUDE', 'WEIGHT'];

const HOLIDAY_MODE_LABEL_KEYS: Readonly<Record<HolidayMode, MessageKey>> = {
  INCLUDE: 'reports.forecast.holiday.mode.include',
  EXCLUDE: 'reports.forecast.holiday.mode.exclude',
  WEIGHT: 'reports.forecast.holiday.mode.weight',
};

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type SecondaryLoadState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * IA §7.8 Demand. Two sections, deliberately kept apart in wording as well as
 * in layout.
 *
 * **The demand-history section (unchanged in spirit since wave 48).** For
 * *completed* orders only, the average per hour over the location's most
 * recent occurrences of a weekday, every count traceable to a named business
 * date in `sampleDates` (`GET .../reporting/demand-history`). Still never a
 * forecast in its own copy: no hour here shows a number computed from fewer
 * than `minimumSampleSize` qualifying dates, and a location with no history
 * says so rather than rendering zeros.
 *
 * **The forecast section — wave W02, the owner's 2026-09-05 decision's other
 * half.** `reporting.forecast_run`/`fact_forecast` now exist:
 * `ForecastScheduler` generates a seasonal-naive mean and confidence interval
 * nightly from the identical trailing sample the section above already
 * shows, and backfills the actual and its error once a forecasted date's
 * business day closes. This section is the one place in the console the word
 * "forecast" (`прогноз`/`bashorat`) is allowed to appear, because this is now
 * the genuine article: model version, sample size and confidence level are
 * always on screen beside the number, never only in a tooltip.
 *
 * **Wave W02's three screen gaps, both sections share:**
 * - A branch selector (`locations`, fetched once for the operator's brand) —
 *   the endpoint already took an arbitrary `locationId` under a
 *   tenant-scoped capability; only the screen was single-location.
 * - `hourWindowLabel` is now operating-day-relative, using the response's own
 *   `provenance.businessDayStart` — a 09:00 tenant's "hour 23" is 08:00-09:00
 *   the next calendar day, not wall-clock 23:00-24:00.
 * - 7.8a's department/product breakdown, opt-in like the week overview,
 *   drawn from the same forecast run rather than a second, differently
 *   sampled read.
 *
 * **7.8b holiday awareness** lives on the demand-history section only (the
 * forecast section shows whatever `holiday_mode` the run itself used):
 * `holidayDates` flags a `tenant.public_holidays` date inside `sampleDates`,
 * and `holidayMode` (INCLUDE/EXCLUDE/WEIGHT) controls how it counts.
 *
 * `reports-shell.ts`'s own filter bar (period, fulfilment, channel, legal
 * entity, payment method, granularity) is hidden entirely on this route — see
 * that file's `isForecastRoute` — because none of those axes apply to "the
 * most recent occurrences of one weekday"; the branch control below is this
 * screen's own, not shared with the bar.
 */
@Component({
  selector: 'q-demand-forecast-page',
  imports: [TPipe, ProvenanceBanner, HeatmapChart],
  templateUrl: './demand-forecast-page.html',
  styleUrl: './demand-forecast-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DemandForecastPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly api = inject(ReportingApi);
  protected readonly i18n = inject(I18n);

  protected readonly WEEKDAYS = WEEKDAYS;
  protected readonly SAMPLE_SIZE_OPTIONS = SAMPLE_SIZE_OPTIONS;
  protected readonly HOURS = HOURS;
  protected readonly HOLIDAY_MODES = HOLIDAY_MODES;

  protected readonly state = signal<LoadState>('loading');
  protected readonly weekday = signal<number>(defaultWeekday());
  protected readonly sampleSize = signal<number>(SAMPLE_SIZE_OPTIONS[0]);
  protected readonly holidayMode = signal<HolidayMode>('INCLUDE');
  protected readonly response = signal<DemandHistoryResponse | null>(null);

  /** Every branch the operator may pick from — empty (so the control hides) for a single-location tenant. */
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly selectedLocationId = signal<string | null>(null);

  /**
   * The hour-of-day heatmap (IA X.19) — every weekday at once, which
   * `demandHistory` cannot answer in a single call (it takes one `weekday`).
   * Opt-in, not fetched on load: firing seven requests the moment this
   * screen opens for a manager who only ever wants Monday would be seven
   * calls nobody asked for. `null` until the operator asks for it.
   */
  protected readonly weekGrid = signal<readonly ChartHeatRow[] | null>(null);
  protected readonly weekGridLoading = signal(false);
  protected readonly weekGridError = signal(false);

  /** Wave W02: the seasonal-naive forecast, its confidence interval and the forecast-vs-actual trend. */
  protected readonly forecastState = signal<SecondaryLoadState>('idle');
  protected readonly forecast = signal<DemandForecastResponse | null>(null);

  /** Wave W02 (7.8a). Opt-in, the same reasoning `weekGrid` gives. */
  protected readonly breakdownDimension = signal<'CATEGORY' | 'VARIANT'>('CATEGORY');
  protected readonly breakdown = signal<DemandForecastBreakdownResponse | null>(null);
  protected readonly breakdownState = signal<SecondaryLoadState>('idle');

  protected readonly formatAverage = formatAverage;
  protected readonly ddmmyyyy = ddmmyyyy;

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
    await Promise.all([this.load(), this.loadBranchOptions(scope)]);
    // The forecast section is fetched alongside the honest average rather
    // than opt-in: it is the section this wave exists to add, so it should
    // not read as hidden behind an extra click the way the week overview and
    // the breakdown (both pre-existing, expensive, multi-request reads) do.
    void this.loadForecast();
  }

  protected weekdayLabel(weekday: number): string {
    return this.i18n.t(WEEKDAY_LABEL_KEYS[weekday]);
  }

  protected holidayModeLabel(mode: HolidayMode): string {
    return this.i18n.t(HOLIDAY_MODE_LABEL_KEYS[mode]);
  }

  /** `0.80` -> `80` — the confidence level as a whole percentage for the model caption. */
  protected confidencePercent(confidenceLevel: number): number {
    return Math.round(confidenceLevel * 100);
  }

  /**
   * `18:00–19:00` (or crossing midnight for a non-midnight tenant) —
   * operating-day-relative, from the current response's own `provenance
   * .businessDayStart`, never assumed to be wall-clock 00:00. Falls back to
   * midnight (identity shift) before either response has loaded, which only
   * ever shows for an instant during the initial load.
   */
  protected hourWindowLabel(operatingHour: number): string {
    const businessDayStart =
      this.response()?.provenance.businessDayStart ??
      this.forecast()?.provenance.businessDayStart ??
      '00:00:00';
    const [startHour, startMinute] = businessDayStart.split(':').map(Number);
    const startTotal = ((startHour || 0) * 60 + (startMinute || 0)) % (24 * 60);
    const windowStart = (startTotal + operatingHour * 60) % (24 * 60);
    const windowEnd = (windowStart + 60) % (24 * 60);
    return `${clockLabel(windowStart)}–${clockLabel(windowEnd)}`;
  }

  protected selectWeekday(weekday: number): void {
    if (weekday === this.weekday()) {
      return;
    }
    this.weekday.set(weekday);
    void this.load();
    void this.loadForecast();
    this.resetBreakdown();
  }

  protected selectSampleSize(size: number): void {
    if (size === this.sampleSize()) {
      return;
    }
    this.sampleSize.set(size);
    void this.load();
  }

  protected selectHolidayMode(mode: HolidayMode): void {
    if (mode === this.holidayMode()) {
      return;
    }
    this.holidayMode.set(mode);
    void this.load();
  }

  protected selectBranch(locationId: string): void {
    if (locationId === this.selectedLocationId()) {
      return;
    }
    this.selectedLocationId.set(locationId);
    void this.load();
    void this.loadForecast();
    this.resetBreakdown();
    this.weekGrid.set(null);
  }

  protected retry(): void {
    void this.load();
  }

  protected retryForecast(): void {
    void this.loadForecast();
  }

  /** True when `sampleDates` is non-empty but shorter than `minimumSampleSize` — the raw-counts state. */
  protected belowMinimum(response: DemandHistoryResponse): boolean {
    return (
      response.sampleDates.length > 0 && response.sampleDates.length < response.minimumSampleSize
    );
  }

  /** 7.8b: whether this sample date was flagged by a `tenant.public_holidays` rule. */
  protected isHoliday(response: DemandHistoryResponse, date: string): boolean {
    return response.holidayDates.includes(date);
  }

  /**
   * Fetches every weekday at the current sample size and builds the heatmap
   * grid (IA X.19) — one row per weekday, one column per hour, shaded by
   * `averageOrders`. Reuses `demandHistory`'s own honesty rule as-is: a
   * below-minimum-sample hour is `null` in the response already, and the
   * heatmap renders `null` as an uncoloured cell rather than inventing a
   * shade for it (see `heatmap-chart.ts`).
   */
  protected async showWeekOverview(): Promise<void> {
    const locationId = this.selectedLocationId();
    const scope = this.scope;
    if (!scope || !locationId || this.weekGridLoading()) {
      return;
    }
    this.weekGridLoading.set(true);
    this.weekGridError.set(false);
    try {
      const responses = await Promise.all(
        WEEKDAYS.map((weekday) =>
          this.api.demandHistory(scope.tenantId, {
            locationId,
            weekday,
            sampleSize: this.sampleSize(),
            holidayMode: this.holidayMode(),
          }),
        ),
      );
      this.weekGrid.set(
        WEEKDAYS.map((weekday, index) => ({
          key: String(weekday),
          label: this.weekdayLabel(weekday),
          cells: responses[index].hours.map((hour) => ({
            key: String(hour.hourOfDay),
            label: this.hourWindowLabel(hour.hourOfDay),
            value: hour.averageOrders,
          })),
        })),
      );
    } catch (error) {
      if (error instanceof ApiError) {
        this.weekGridError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.weekGridLoading.set(false);
    }
  }

  /** Wave W02 (7.8a). Fetched only once the operator asks, and re-fetched when the dimension toggle changes. */
  protected async showBreakdown(): Promise<void> {
    await this.loadBreakdown(this.breakdownDimension());
  }

  protected async selectBreakdownDimension(dimension: 'CATEGORY' | 'VARIANT'): Promise<void> {
    if (dimension === this.breakdownDimension() && this.breakdown() !== null) {
      return;
    }
    this.breakdownDimension.set(dimension);
    await this.loadBreakdown(dimension);
  }

  private resetBreakdown(): void {
    this.breakdown.set(null);
    this.breakdownState.set('idle');
  }

  private async loadBreakdown(dimension: 'CATEGORY' | 'VARIANT'): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    if (!scope || !locationId) {
      return;
    }
    this.breakdownState.set('loading');
    try {
      const result = await this.api.demandForecastBreakdown(scope.tenantId, {
        locationId,
        weekday: this.weekday(),
        dimension,
      });
      this.breakdown.set(result);
      this.breakdownState.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        this.breakdownState.set('error');
      } else {
        throw error;
      }
    }
  }

  private async loadBranchOptions(scope: LocationScope): Promise<void> {
    try {
      this.locations.set(await this.locationsApi.list(scope));
    } catch {
      // A single-location tenant, or the list call failed: either way the
      // branch control hides itself (see the template's own length check)
      // and the screen still works for the operator's own location.
      this.locations.set([]);
    }
  }

  private async load(): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    if (!scope || !locationId) {
      return;
    }
    this.state.set('loading');
    try {
      const result = await this.api.demandHistory(scope.tenantId, {
        locationId,
        weekday: this.weekday(),
        sampleSize: this.sampleSize(),
        holidayMode: this.holidayMode(),
      });
      this.response.set(result);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        this.state.set('error');
      } else {
        throw error;
      }
    }
  }

  private async loadForecast(): Promise<void> {
    const scope = this.scope;
    const locationId = this.selectedLocationId();
    if (!scope || !locationId) {
      return;
    }
    this.forecastState.set('loading');
    try {
      const result = await this.api.demandForecast(scope.tenantId, {
        locationId,
        weekday: this.weekday(),
      });
      this.forecast.set(result);
      this.forecastState.set('ready');
    } catch (error) {
      if (error instanceof ApiError) {
        this.forecastState.set('error');
      } else {
        throw error;
      }
    }
  }
}

/** `HH:mm` from a minute-of-day count, wrapped to 24h. */
function clockLabel(totalMinutes: number): string {
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

/** ISO-8601 weekday for "today" in the browser's own local date — a reasonable default selection, not a data-correctness concern (unlike the server's own hour-of-day math, which is tenant-timezone-exact; see `ReportQueryService.demandHistory`). */
function defaultWeekday(): number {
  const jsDay = new Date().getDay();
  return jsDay === 0 ? 7 : jsDay;
}
