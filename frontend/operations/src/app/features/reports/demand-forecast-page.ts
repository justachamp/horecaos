import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ProvenanceBanner } from './provenance-banner';
import { ddmmyyyy, formatAverage } from './report-formatting';
import { DemandHistoryResponse, ReportingApi } from './reporting-api';

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

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * IA §7.8 Demand — `docs/frontend-information-architecture.md` names this row
 * "forecast vs actual by hour... holiday-aware modelling", and ADR 0043's own
 * "Forecasting" section sketches a seasonal-naive model with a day-of-week/
 * hour profile and a holiday factor. **None of that is what this screen
 * shows, by the owner's explicit 2026-09-05 decision recorded in that ADR's
 * implementation status: ship the honest historical average now, labelled as
 * exactly that, and build the real model later.**
 *
 * **What this screen is.** For the operator's own location (`CurrentLocation`,
 * the same single-location scope `capacity-page.ts` uses — a manager reads
 * this screen and 7.8's sibling `/kitchen/capacity` side by side to compare
 * "what usually happens" against "the ceiling I set"), pick a weekday and see
 * the average number of *completed* orders in each hour, averaged over that
 * weekday's most recent occurrences with order history
 * (`GET .../reporting/demand-history`, `reporting.fact_order`, wave 48). Every
 * count traces back to a named business date in `sampleDates` — nothing here
 * is invented, smoothed, or extrapolated.
 *
 * **The honesty rules this screen enforces, not just states in prose:**
 * - No hour ever shows a number computed from fewer than
 *   `minimumSampleSize` (3) qualifying dates — see `belowMinimum` below.
 *   Below it, the raw per-date counts are shown instead of an average, so a
 *   manager with one real week of data sees that number rather than nothing.
 * - The sample size is always on screen, in the sentence above the table,
 *   never only in a tooltip.
 * - A location with no history on the selected weekday says so in plain
 *   words rather than rendering a table of zeros.
 *
 * **What this explicitly is not, and does not pretend to be**: no trend
 * line, no confidence interval, no smoothing, no holiday factor, no per-
 * product breakdown (7.8's own spec asks for "branch, department and
 * product" — this wave answers "branch" only; `reporting.fact_order_line`
 * has no timestamp of its own to bucket by hour, and ADR 0043 already lists
 * per-product forecasting as not built). The word "forecast" appears only in
 * this file's own name and the IA section it implements — never in a string
 * an operator reads.
 */
@Component({
  selector: 'q-demand-forecast-page',
  imports: [TPipe, ProvenanceBanner],
  templateUrl: './demand-forecast-page.html',
  styleUrl: './demand-forecast-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DemandForecastPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly api = inject(ReportingApi);
  protected readonly i18n = inject(I18n);

  protected readonly WEEKDAYS = WEEKDAYS;
  protected readonly SAMPLE_SIZE_OPTIONS = SAMPLE_SIZE_OPTIONS;
  protected readonly HOURS = HOURS;

  protected readonly state = signal<LoadState>('loading');
  protected readonly weekday = signal<number>(defaultWeekday());
  protected readonly sampleSize = signal<number>(SAMPLE_SIZE_OPTIONS[0]);
  protected readonly response = signal<DemandHistoryResponse | null>(null);

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
    await this.load();
  }

  protected weekdayLabel(weekday: number): string {
    return this.i18n.t(WEEKDAY_LABEL_KEYS[weekday]);
  }

  /** `18:00–19:00` — a wall-clock window, not the operating-day-relative hour ADR 0043's own sketch chart uses. See this class's doc for why. */
  protected hourWindowLabel(hour: number): string {
    const start = String(hour).padStart(2, '0');
    const end = String((hour + 1) % 24).padStart(2, '0');
    return `${start}:00–${end}:00`;
  }

  protected selectWeekday(weekday: number): void {
    if (weekday === this.weekday()) {
      return;
    }
    this.weekday.set(weekday);
    void this.load();
  }

  protected selectSampleSize(size: number): void {
    if (size === this.sampleSize()) {
      return;
    }
    this.sampleSize.set(size);
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  /** True when `sampleDates` is non-empty but shorter than `minimumSampleSize` — the raw-counts state. */
  protected belowMinimum(response: DemandHistoryResponse): boolean {
    return response.sampleDates.length > 0 && response.sampleDates.length < response.minimumSampleSize;
  }

  private async load(): Promise<void> {
    if (!this.scope) {
      return;
    }
    this.state.set('loading');
    try {
      const result = await this.api.demandHistory(this.scope.tenantId, {
        locationId: this.scope.locationId,
        weekday: this.weekday(),
        sampleSize: this.sampleSize(),
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
}

/** ISO-8601 weekday for "today" in the browser's own local date — a reasonable default selection, not a data-correctness concern (unlike the server's own hour-of-day math, which is tenant-timezone-exact; see `ReportQueryService.demandHistory`). */
function defaultWeekday(): number {
  const jsDay = new Date().getDay();
  return jsDay === 0 ? 7 : jsDay;
}
