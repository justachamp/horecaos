import { Injectable, computed, signal } from '@angular/core';

/**
 * The shared global filter bar (statistics.md §1.1), scoped to the reports
 * feature: one instance, provided by {@code ReportsShell}, so the period and
 * slice a manager sets on the overview survives switching to the order-report
 * tabs beside it.
 *
 * **Scoped down for this wave.** The spec's bar has a period axis, a slice
 * axis (branch/channel/fulfilment type/legal entity/payment method) and
 * persists every one of them as a URL query parameter that survives a reload
 * and the browser's back button. This carries the period and slice axes as
 * plain signals, shared for the lifetime of a visit to `/statistics`, but does
 * not yet round-trip them through the URL — a filtered view is not a
 * shareable link today. Locations, legal entities and pre-order/custom date
 * ranges are also out for this wave: the pilot is single-location
 * (`docs/adr/meta/0055-greenfield-launch-scope.md`), so the multiselect this
 * spec calls for is exactly the "always-Все control is noise" case it says to
 * hide, and only four fixed period pills ship rather than the free-form
 * `Период…` range.
 */
export type PeriodPreset = 'today' | 'yesterday' | '7d' | 'month';

export interface DateRange {
  readonly from: string;
  readonly to: string;
}

/**
 * No location anywhere this feature can reach carries a timezone yet — the
 * same gap `order-queue.ts`'s `PLACEHOLDER_TIME_ZONE` documents. HorecaOS
 * operates in Uzbekistan today, so a fixed zone is the least-wrong constant
 * available; replace it with `tenant.locations.timezone` (or the resolved
 * `reporting.business_day_start` the provenance banner already renders) the
 * moment a call surfaces it.
 */
export const REPORTS_PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

@Injectable()
export class ReportsFilterState {
  readonly period = signal<PeriodPreset>('today');
  readonly channelCodes = signal<readonly string[]>([]);
  readonly fulfilmentType = signal<'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'>('ALL');

  /** The resolved [from, to] business-date range for the active period, in the placeholder zone. */
  readonly range = computed<DateRange>(() =>
    rangeFor(this.period(), todayIn(REPORTS_PLACEHOLDER_TIME_ZONE)),
  );

  /**
   * The comparison window a tile's delta reads against: the same span, shifted
   * back by a whole number of weeks. Statistics.md §2.1 Band A: "the delta
   * compares against the same weekday last week, never against yesterday —
   * Saturday against Friday is noise dressed as a trend." A whole-week shift
   * keeps that true for every preset, not only `today`.
   */
  readonly comparisonRange = computed<DateRange>(() => {
    const current = this.range();
    const days = diffDays(current.from, current.to) + 1;
    const shiftDays = 7 * Math.ceil(days / 7);
    return { from: shiftDate(current.from, -shiftDays), to: shiftDate(current.to, -shiftDays) };
  });

  setPeriod(period: PeriodPreset): void {
    this.period.set(period);
  }

  setChannelCodes(codes: readonly string[]): void {
    this.channelCodes.set(codes);
  }

  setFulfilmentType(type: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'): void {
    this.fulfilmentType.set(type);
  }
}

function rangeFor(preset: PeriodPreset, today: string): DateRange {
  switch (preset) {
    case 'today':
      return { from: today, to: today };
    case 'yesterday': {
      const yesterday = shiftDate(today, -1);
      return { from: yesterday, to: yesterday };
    }
    case '7d':
      return { from: shiftDate(today, -6), to: today };
    case 'month':
      return { from: `${today.slice(0, 7)}-01`, to: today };
  }
}

/** `YYYY-MM-DD` for "today" in an IANA zone. `en-CA` is the one `Intl` locale that formats this way natively. */
function todayIn(zone: string, at: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(at);
}

/** Calendar-day arithmetic on an ISO date string. Zone-independent: both ends are already local dates. */
function shiftDate(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function diffDays(fromIso: string, toIso: string): number {
  const [fy, fm, fd] = fromIso.split('-').map(Number);
  const [ty, tm, td] = toIso.split('-').map(Number);
  const from = Date.UTC(fy, fm - 1, fd);
  const to = Date.UTC(ty, tm - 1, td);
  return Math.round((to - from) / 86_400_000);
}
