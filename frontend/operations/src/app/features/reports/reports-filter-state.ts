import { Injectable, computed, effect, signal } from '@angular/core';

/**
 * The shared global filter bar (statistics.md §1.1), scoped to the reports
 * feature: one instance, provided by {@code ReportsShell}, so the period and
 * slice a manager sets on the overview survives switching to the order-report
 * tabs beside it.
 *
 * **Wave P27.** Every axis statistics.md §1.1 names is now here and applied:
 * a custom date range and a chart granularity join the four period pills;
 * branch (`locationIds`) and legal entity (`legalEntityIds`) join channel and
 * fulfilment type as slice filters; every one of them round-trips through the
 * URL's query string (`syncToUrl`/the constructor's own read), so a filtered
 * view survives a reload and can be pasted as a link; and the arrow keys step
 * the active window by its own length ({@link stepPeriod}). Locations and
 * legal entities are still multiselects rather than single pickers — the
 * pilot's single-location scope (`docs/adr/meta/0055-greenfield-launch-scope.md`)
 * made a control unnecessary before a tenant had a second branch to filter
 * out; wave P20/P33 give a tenant a real list to choose from now.
 */
export type PeriodPreset = 'today' | 'yesterday' | '7d' | 'month' | 'custom';

export type Granularity = 'day' | 'week' | 'month';

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

/** The query-string keys this state round-trips through — prefixed to stay out of any other feature's way. */
const URL_KEYS = {
  period: 'rp_period',
  from: 'rp_from',
  to: 'rp_to',
  fulfilment: 'rp_fulfilment',
  channels: 'rp_channels',
  locations: 'rp_locations',
  legalEntities: 'rp_entities',
  paymentMethods: 'rp_payment',
  granularity: 'rp_granularity',
} as const;

@Injectable()
export class ReportsFilterState {
  readonly period = signal<PeriodPreset>('today');
  /** Only meaningful while {@link period} is `'custom'`. */
  readonly customRange = signal<DateRange>({
    from: todayIn(REPORTS_PLACEHOLDER_TIME_ZONE),
    to: todayIn(REPORTS_PLACEHOLDER_TIME_ZONE),
  });
  readonly granularity = signal<Granularity>('day');
  readonly channelCodes = signal<readonly string[]>([]);
  readonly fulfilmentType = signal<'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'>('ALL');

  /** Wave P27: the branch axis — empty means every branch the caller may read. */
  readonly locationIds = signal<readonly string[]>([]);
  /** Wave P27: the legal-entity axis — empty means every entity. Was only a `groupBy` dimension before this wave. */
  readonly legalEntityIds = signal<readonly string[]>([]);

  /**
   * P39: `payments.payment_methods.code` values to filter by — empty means
   * every method. Unlike `channelCodes`, a real control sets this one (the
   * filter bar's own doc explains why the chip was locked until this wave).
   */
  readonly paymentMethodCodes = signal<readonly string[]>([]);

  /** The resolved [from, to] business-date range for the active period, in the placeholder zone. */
  readonly range = computed<DateRange>(() => {
    const period = this.period();
    return period === 'custom'
      ? this.customRange()
      : rangeFor(period, todayIn(REPORTS_PLACEHOLDER_TIME_ZONE));
  });

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

  constructor() {
    this.readFromUrl();
    // Every signal above, read once so the effect tracks all of them —
    // whichever one changes, the URL is rewritten to match. replaceState, not
    // pushState: stepping the range or ticking a checkbox is not a browser-
    // history event a manager expects the Back button to undo one filter at
    // a time. `effect()` ties its own lifecycle to this constructor's
    // injection context, so it is cleaned up when this service is (Angular's
    // default, not something this class has to arrange).
    effect(() => {
      const period = this.period();
      const custom = this.customRange();
      const granularity = this.granularity();
      const channels = this.channelCodes();
      const fulfilment = this.fulfilmentType();
      const locations = this.locationIds();
      const legalEntities = this.legalEntityIds();
      const paymentMethods = this.paymentMethodCodes();
      this.writeToUrl({
        period,
        custom,
        granularity,
        channels,
        fulfilment,
        locations,
        legalEntities,
        paymentMethods,
      });
    });
  }

  setPeriod(period: PeriodPreset): void {
    this.period.set(period);
  }

  setCustomRange(range: DateRange): void {
    this.period.set('custom');
    this.customRange.set(range);
  }

  setGranularity(granularity: Granularity): void {
    this.granularity.set(granularity);
  }

  setChannelCodes(codes: readonly string[]): void {
    this.channelCodes.set(codes);
  }

  setFulfilmentType(type: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'): void {
    this.fulfilmentType.set(type);
  }

  setLocationIds(ids: readonly string[]): void {
    this.locationIds.set(ids);
  }

  setLegalEntityIds(ids: readonly string[]): void {
    this.legalEntityIds.set(ids);
  }

  setPaymentMethodCodes(codes: readonly string[]): void {
    this.paymentMethodCodes.set(codes);
  }

  /** Whether any secondary-row filter is currently set — the filter bar's own "Сбросить фильтры" visibility. */
  hasAnySecondaryFilter(): boolean {
    return (
      this.channelCodes().length > 0 ||
      this.fulfilmentType() !== 'ALL' ||
      this.locationIds().length > 0 ||
      this.legalEntityIds().length > 0 ||
      this.paymentMethodCodes().length > 0 ||
      this.granularity() !== 'day'
    );
  }

  resetFilters(): void {
    this.channelCodes.set([]);
    this.fulfilmentType.set('ALL');
    this.locationIds.set([]);
    this.legalEntityIds.set([]);
    this.paymentMethodCodes.set([]);
    this.granularity.set('day');
  }

  /**
   * Statistics.md §1.1's arrow-key stepping: shifts the active window by its
   * own length, always landing on a `'custom'` range — "the day before
   * yesterday" or "the seven days before this week" are not one of the four
   * pills, so a step is never expressed as a preset.
   */
  stepPeriod(direction: -1 | 1): void {
    const current = this.range();
    const days = diffDays(current.from, current.to) + 1;
    const shift = direction * days;
    this.setCustomRange({ from: shiftDate(current.from, shift), to: shiftDate(current.to, shift) });
  }

  // ------------------------------------------------------------- URL round-trip

  private readFromUrl(): void {
    if (typeof window === 'undefined') {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    const period = params.get(URL_KEYS.period);
    if (isPeriodPreset(period)) {
      this.period.set(period);
    }
    const from = params.get(URL_KEYS.from);
    const to = params.get(URL_KEYS.to);
    if (from && to) {
      this.customRange.set({ from, to });
    }
    const granularity = params.get(URL_KEYS.granularity);
    if (granularity === 'day' || granularity === 'week' || granularity === 'month') {
      this.granularity.set(granularity);
    }
    const fulfilment = params.get(URL_KEYS.fulfilment);
    if (
      fulfilment === 'ALL' ||
      fulfilment === 'DELIVERY' ||
      fulfilment === 'PICKUP' ||
      fulfilment === 'DINE_IN'
    ) {
      this.fulfilmentType.set(fulfilment);
    }
    this.channelCodes.set(readList(params, URL_KEYS.channels));
    this.locationIds.set(readList(params, URL_KEYS.locations));
    this.legalEntityIds.set(readList(params, URL_KEYS.legalEntities));
    this.paymentMethodCodes.set(readList(params, URL_KEYS.paymentMethods));
  }

  private writeToUrl(state: {
    readonly period: PeriodPreset;
    readonly custom: DateRange;
    readonly granularity: Granularity;
    readonly channels: readonly string[];
    readonly fulfilment: string;
    readonly locations: readonly string[];
    readonly legalEntities: readonly string[];
    readonly paymentMethods: readonly string[];
  }): void {
    if (typeof window === 'undefined' || typeof history === 'undefined') {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    params.set(URL_KEYS.period, state.period);
    if (state.period === 'custom') {
      params.set(URL_KEYS.from, state.custom.from);
      params.set(URL_KEYS.to, state.custom.to);
    } else {
      params.delete(URL_KEYS.from);
      params.delete(URL_KEYS.to);
    }
    setOrDelete(
      params,
      URL_KEYS.granularity,
      state.granularity === 'day' ? null : state.granularity,
    );
    setOrDelete(params, URL_KEYS.fulfilment, state.fulfilment === 'ALL' ? null : state.fulfilment);
    writeList(params, URL_KEYS.channels, state.channels);
    writeList(params, URL_KEYS.locations, state.locations);
    writeList(params, URL_KEYS.legalEntities, state.legalEntities);
    writeList(params, URL_KEYS.paymentMethods, state.paymentMethods);

    const query = params.toString();
    const url = `${window.location.pathname}${query ? `?${query}` : ''}${window.location.hash}`;
    history.replaceState(history.state, '', url);
  }
}

function isPeriodPreset(value: string | null): value is PeriodPreset {
  return (
    value === 'today' ||
    value === 'yesterday' ||
    value === '7d' ||
    value === 'month' ||
    value === 'custom'
  );
}

function readList(params: URLSearchParams, key: string): readonly string[] {
  const raw = params.get(key);
  return raw ? raw.split(',').filter((value) => value.length > 0) : [];
}

function writeList(params: URLSearchParams, key: string, values: readonly string[]): void {
  setOrDelete(params, key, values.length > 0 ? values.join(',') : null);
}

function setOrDelete(params: URLSearchParams, key: string, value: string | null): void {
  if (value === null) {
    params.delete(key);
  } else {
    params.set(key, value);
  }
}

function rangeFor(preset: Exclude<PeriodPreset, 'custom'>, today: string): DateRange {
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
