import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { CallCentreApi, CallCoverage, CallHourStat } from '../orders/call-centre-api';
import { ProvenanceBanner } from './provenance-banner';
import {
  formatAverage,
  formatCount,
  formatQuantityTotal,
  formatSecondsDuration,
  formatShare,
} from './report-formatting';
import { REPORTS_PLACEHOLDER_TIME_ZONE, ReportsFilterState } from './reports-filter-state';
import {
  OperatorLeaderboardRowResponse,
  ProvenanceResponse,
  ReportingApi,
  VariantSalesRowResponse,
} from './reporting-api';

type StaffReportTab = 'leaderboard' | 'products' | 'telephony';
type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type ProductsState = 'idle' | 'loading' | 'ready' | 'error';
type TelephonyState = 'idle' | 'loading' | 'ready' | 'error';

const TAB_DEFINITIONS: readonly { readonly id: StaffReportTab; readonly labelKey: MessageKey }[] = [
  { id: 'leaderboard', labelKey: 'reports.staff.tab.leaderboard' },
  { id: 'products', labelKey: 'reports.staff.tab.products' },
  { id: 'telephony', labelKey: 'reports.staff.tab.telephony' },
];

/**
 * One operator's summed call activity across the range — see {@link loadTelephony}.
 *
 * ADR 0175: the five call-level sums are taken over the days that were *measured*
 * only. A day closed before the measure existed carries `null` and contributes to
 * the old counts and to nothing here, so a ratio's numerator and denominator are
 * always counted over the same rows.
 */
interface TelephonyRow {
  readonly operatorPrincipalId: string;
  readonly offeredCount: number;
  readonly answeredCount: number;
  readonly missedCount: number;
  readonly transferredCount: number;
  /** The call's length (first event to end), despite the historic field name `talkDurationSeconds`. */
  readonly callLengthSeconds: number;
  /** Answered calls over measured rows: the denominator of the ratios below. */
  readonly measuredAnsweredCalls: number;
  readonly ringSecondsTotal: number;
  readonly ringSampleCount: number;
  readonly ringSecondsMax: number | null;
  readonly convertedCount: number;
}

/** The sentinel `reporting.fact_call_hour` files a call under when nobody could be named. */
const UNATTRIBUTED_OPERATOR = '(unassigned)';

/** `CallStatsController.coverage` refuses a range of a year or more; the page asks for at most this many days. */
const COVERAGE_MAX_DAYS = 365;

/**
 * `CallStatsController` answers one business date at a time (no `groupBy`,
 * unlike every other reporting endpoint this feature calls) — this is how
 * many days the telephony tab will fan out to before it stops rather than
 * firing an unbounded burst of requests for a very wide custom range. The
 * filter bar's widest preset today is a calendar month, comfortably under it.
 */
const TELEPHONY_MAX_DAYS = 31;

/**
 * 7.5/7.5a/7.5b Staff reports (`frontend-information-architecture.md` §7.5,
 * `statistics.md`) — tier 2.
 *
 * **Leaderboard (7.5) and products (7.5a)** are real: `GET
 * .../reporting/operator-leaderboard` and `.../operator-products`, both new
 * this wave, reading `reporting.fact_order`'s new `operator_principal_id`
 * (T12). A machine principal — the bot, the website, any channel nobody on
 * staff touched — renders with an explicit `MACHINE` badge and its channel
 * code; a staff row renders `STAFF` and the name the tenant keeps for the
 * person (ADR 0139, composed by the platform at the web layer -- `reporting`
 * never holds a name), falling back to a truncated Keycloak subject, never a
 * bare UUID with no explanation, for a subject the tenant keeps no name for.
 *
 * **Telephony (7.5b)** reads `CallStatsController` (offered/answered/missed/transferred, answer
 * speed, call-to-order conversion and call length per hour per operator, off
 * `reporting.fact_call_hour`, written by the same day-close pipeline). ADR 0175 defines the two
 * KPIs once: answer speed is the seconds from a call's `OFFERED` event to its first `ANSWERED`
 * event; conversion is the share of answered calls with an order placed from them, in any status.
 * Ratios are computed here from stored counts and sums, never stored; a figure that was not
 * measured is «—, not measured» and never zero; and a branch with no telephony provider and no
 * closed call history has no Telephony tab at all (statistics.md §2.5), decided by the `coverage`
 * read.
 */
@Component({
  selector: 'q-staff-report-page',
  imports: [TPipe, ProvenanceBanner],
  templateUrl: './staff-report-page.html',
  styleUrl: './staff-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffReportPage {
  private readonly api = inject(ReportingApi);
  private readonly callCentre = inject(CallCentreApi);
  private readonly location = inject(CurrentLocation);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly activeTab = signal<StaffReportTab>('leaderboard');

  /** ADR 0175: whether the branch has telephony; loaded with the page, it decides if the tab is offered. */
  protected readonly coverage = signal<CallCoverage | null>(null);
  protected readonly coverageState = signal<'loading' | 'ready' | 'error'>('loading');
  /**
   * The Telephony tab is offered when the branch has a live provider or any closed call history. While the
   * coverage read is in flight it is not (no flash of a tab that then disappears); if the read failed we
   * cannot tell, so the tab stays and does its own loading and error reporting.
   */
  protected readonly telephonyAvailable = computed(() => {
    const state = this.coverageState();
    if (state === 'error') {
      return true;
    }
    const coverage = this.coverage();
    return (
      state === 'ready' &&
      coverage !== null &&
      (coverage.connection === 'CONNECTED' || coverage.closedDaysWithCalls > 0)
    );
  });
  protected readonly tabs = computed(() =>
    TAB_DEFINITIONS.filter((tab) => tab.id !== 'telephony' || this.telephonyAvailable()),
  );
  /** A `?rp_tab=telephony` deep link, honoured only once the coverage read says the tab exists. */
  private readonly requestedTab: StaffReportTab | null = readRequestedTab();

  protected readonly state = signal<LoadState>('loading');
  protected readonly rows = signal<readonly OperatorLeaderboardRowResponse[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly requestedTo = computed(() => this.filters.range().to);

  protected readonly selectedOperator = signal<OperatorLeaderboardRowResponse | null>(null);
  protected readonly productsState = signal<ProductsState>('idle');
  protected readonly productRows = signal<readonly VariantSalesRowResponse[]>([]);
  protected readonly productsMaybeMore = signal(false);

  protected readonly telephonyState = signal<TelephonyState>('idle');
  protected readonly telephonyRows = signal<readonly TelephonyRow[]>([]);
  protected readonly telephonyRangeCapped = signal(false);
  protected readonly telephonyDayCount = TELEPHONY_MAX_DAYS;

  constructor() {
    void this.load();
    void this.loadCoverage();
  }

  protected selectTab(tab: StaffReportTab): void {
    if (tab === 'telephony' && !this.telephonyAvailable()) {
      this.activeTab.set('leaderboard');
      return;
    }
    this.activeTab.set(tab);
    if (tab === 'telephony' && this.telephonyState() === 'idle') {
      void this.loadTelephony();
    }
  }

  /** 7.5a: drills from a leaderboard row into that operator's own product mix. */
  protected selectOperator(row: OperatorLeaderboardRowResponse): void {
    this.selectedOperator.set(row);
    this.activeTab.set('products');
    void this.loadProducts(row);
  }

  protected retry(): void {
    void this.load();
  }

  protected retryProducts(): void {
    const operator = this.selectedOperator();
    if (operator) {
      void this.loadProducts(operator);
    }
  }

  protected retryTelephony(): void {
    void this.loadTelephony();
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  protected formatCountValue = formatCount;
  /** ADR 0137: items sold can be a fraction of a portion, which `formatCount` would round away. */
  protected formatQuantityValue = (value: number): string =>
    formatQuantityTotal(value, this.i18n.locale());
  protected formatAverageValue = formatAverage;
  protected formatDuration = formatSecondsDuration;

  protected formatHandling(seconds: number | null): string {
    return seconds === null ? '—' : formatSecondsDuration(seconds);
  }

  /**
   * A telephony row's operator: the name the leaderboard already resolved for the
   * same subject (ADR 0139), else the short subject. The call-stats read carries
   * no name of its own, so an operator who took calls and no completed order in
   * the range keeps the short form.
   */
  protected telephonyOperator(subject: string): string {
    const named = this.rows().find(
      (row) => row.principalKind === 'STAFF' && row.subject === subject && row.displayName,
    );
    return named?.displayName ?? this.shortSubject(subject);
  }

  /** Never the full Keycloak subject in a table cell — see `order-rows-table.ts`'s `shortId` for the same move. */
  protected shortSubject(subject: string): string {
    return subject.slice(0, 8);
  }

  /** Answered over offered — the branch total's ratio, where both counts exist (an offered call carries no operator). */
  protected answerRate(row: TelephonyRow): string {
    return formatShare(row.answeredCount, row.offeredCount);
  }

  /** Whole seconds of average answer speed, or null when no call in the measured rows was a ring sample. */
  protected averageRingSeconds(row: TelephonyRow): number | null {
    return row.ringSampleCount > 0 ? Math.round(row.ringSecondsTotal / row.ringSampleCount) : null;
  }

  /** «18 s (max 61 s)» — only meaningful when {@link averageRingSeconds} is not null. */
  protected answerSpeed(row: TelephonyRow): string {
    const average = this.averageRingSeconds(row);
    return average === null || row.ringSecondsMax === null
      ? '—'
      : this.i18n.t('reports.staff.telephony.speedValue', {
          avg: average,
          max: row.ringSecondsMax,
        });
  }

  /** Conversion as a whole percentage over the measured answered calls; «—» (never `0%`) when none were measured. */
  protected conversion(row: TelephonyRow): string {
    return formatShare(row.convertedCount, row.measuredAnsweredCalls);
  }

  /** «Not attributed» for the sentinel, else the operator's name or short subject. */
  protected telephonyOperatorLabel(subject: string): string {
    return subject === UNATTRIBUTED_OPERATOR
      ? this.i18n.t('reports.staff.telephony.unattributed')
      : this.telephonyOperator(subject);
  }

  /** The branch line: every operator's row summed, so answer rate has both its counts on one line. */
  protected readonly telephonyTotal = computed<TelephonyRow | null>(() => {
    const rows = this.telephonyRows();
    return rows.length === 0 ? null : sumRows(rows);
  });

  /** The wall-clock time (in the reports' zone) of the next close, for the connected-but-nothing-closed line. */
  protected readonly nextCloseLabel = computed(() => {
    const coverage = this.coverage();
    if (!coverage) {
      return '';
    }
    return new Intl.DateTimeFormat(this.i18n.locale(), {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: REPORTS_PLACEHOLDER_TIME_ZONE,
    }).format(new Date(coverage.nextCloseAt));
  });

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const range = this.filters.range();
      const result = await this.api.operatorLeaderboard(scope.tenantId, {
        from: range.from,
        to: range.to,
        locationId: [scope.locationId],
      });
      this.provenance.set(result.provenance);
      this.rows.set(result.rows);
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  private async loadCoverage(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.coverageState.set('error');
      return;
    }
    try {
      const range = this.filters.range();
      const floor = shiftDateIso(range.to, -COVERAGE_MAX_DAYS);
      const coverage = await this.callCentre.callCoverage(
        scope,
        range.from < floor ? floor : range.from,
        range.to,
      );
      this.coverage.set(coverage);
      this.coverageState.set('ready');
    } catch {
      this.coverageState.set('error');
    }
    this.settleTab();
  }

  /** Once the coverage is known: leave a hidden tab, and honour a deep link to a tab that exists. */
  private settleTab(): void {
    if (this.activeTab() === 'telephony' && !this.telephonyAvailable()) {
      this.activeTab.set('leaderboard');
    }
    if (this.requestedTab === 'telephony' && this.telephonyAvailable()) {
      this.selectTab('telephony');
    }
  }

  private async loadProducts(operator: OperatorLeaderboardRowResponse): Promise<void> {
    this.productsState.set('loading');
    const scope = this.location.scope();
    if (!scope) {
      this.productsState.set('error');
      return;
    }
    try {
      const range = this.filters.range();
      const result = await this.api.operatorProducts(scope.tenantId, {
        operatorPrincipalId: operator.operatorPrincipalId,
        from: range.from,
        to: range.to,
        locationId: [scope.locationId],
      });
      this.productRows.set(result.rows);
      this.productsMaybeMore.set(result.maybeMore);
      this.productsState.set('ready');
    } catch {
      this.productsState.set('error');
    }
  }

  private async loadTelephony(): Promise<void> {
    this.telephonyState.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.telephonyState.set('error');
      return;
    }
    try {
      const range = this.filters.range();
      const { dates, capped } = datesBetween(range.from, range.to, TELEPHONY_MAX_DAYS);
      this.telephonyRangeCapped.set(capped);

      const perDate = await Promise.all(
        dates.map((date: string) => this.callCentre.callStats(scope, date)),
      );
      this.telephonyRows.set(rollUpByOperator(perDate));
      this.telephonyState.set('ready');
    } catch {
      this.telephonyState.set('error');
    }
  }
}

function rollUpByOperator(perDate: readonly (readonly CallHourStat[])[]): readonly TelephonyRow[] {
  const byOperator = new Map<string, TelephonyRow>();
  for (const hours of perDate) {
    for (const hour of hours) {
      const existing =
        byOperator.get(hour.operatorPrincipalId) ?? emptyRow(hour.operatorPrincipalId);
      // A null answeredCallCount is a row closed before ADR 0175's measure existed: not measured, so it
      // adds to the old counts and to none of the call-level sums.
      const measured = hour.answeredCallCount != null;
      byOperator.set(hour.operatorPrincipalId, {
        operatorPrincipalId: hour.operatorPrincipalId,
        offeredCount: existing.offeredCount + hour.offeredCount,
        answeredCount: existing.answeredCount + hour.answeredCount,
        missedCount: existing.missedCount + hour.missedCount,
        transferredCount: existing.transferredCount + hour.transferredCount,
        callLengthSeconds: existing.callLengthSeconds + hour.talkDurationSeconds,
        measuredAnsweredCalls: existing.measuredAnsweredCalls + (hour.answeredCallCount ?? 0),
        ringSecondsTotal: existing.ringSecondsTotal + (measured ? (hour.ringSecondsTotal ?? 0) : 0),
        ringSampleCount: existing.ringSampleCount + (measured ? (hour.ringSampleCount ?? 0) : 0),
        ringSecondsMax: maxOrNull(existing.ringSecondsMax, hour.ringSecondsMax ?? null),
        convertedCount: existing.convertedCount + (measured ? (hour.convertedCount ?? 0) : 0),
      });
    }
  }
  return [...byOperator.values()].sort(
    (a, b) => b.offeredCount + b.answeredCount - (a.offeredCount + a.answeredCount),
  );
}

function emptyRow(operatorPrincipalId: string): TelephonyRow {
  return {
    operatorPrincipalId,
    offeredCount: 0,
    answeredCount: 0,
    missedCount: 0,
    transferredCount: 0,
    callLengthSeconds: 0,
    measuredAnsweredCalls: 0,
    ringSecondsTotal: 0,
    ringSampleCount: 0,
    ringSecondsMax: null,
    convertedCount: 0,
  };
}

function sumRows(rows: readonly TelephonyRow[]): TelephonyRow {
  return rows.reduce<TelephonyRow>(
    (total, row) => ({
      operatorPrincipalId: total.operatorPrincipalId,
      offeredCount: total.offeredCount + row.offeredCount,
      answeredCount: total.answeredCount + row.answeredCount,
      missedCount: total.missedCount + row.missedCount,
      transferredCount: total.transferredCount + row.transferredCount,
      callLengthSeconds: total.callLengthSeconds + row.callLengthSeconds,
      measuredAnsweredCalls: total.measuredAnsweredCalls + row.measuredAnsweredCalls,
      ringSecondsTotal: total.ringSecondsTotal + row.ringSecondsTotal,
      ringSampleCount: total.ringSampleCount + row.ringSampleCount,
      ringSecondsMax: maxOrNull(total.ringSecondsMax, row.ringSecondsMax),
      convertedCount: total.convertedCount + row.convertedCount,
    }),
    emptyRow('total'),
  );
}

function maxOrNull(left: number | null, right: number | null): number | null {
  if (left === null) {
    return right;
  }
  return right === null ? left : Math.max(left, right);
}

/** `?rp_tab=telephony` — the page's own deep link, prefixed like every other reports query key. */
function readRequestedTab(): StaffReportTab | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const tab = new URLSearchParams(window.location.search).get('rp_tab');
  return tab === 'telephony' ? 'telephony' : null;
}

/** Every ISO date from `fromIso` to `toIso` inclusive, capped at `maxDays` — zone-independent, both ends already local dates. */
function datesBetween(
  fromIso: string,
  toIso: string,
  maxDays: number,
): { readonly dates: readonly string[]; readonly capped: boolean } {
  const dates: string[] = [];
  let cursor = fromIso;
  while (cursor <= toIso && dates.length < maxDays) {
    dates.push(cursor);
    cursor = shiftDateIso(cursor, 1);
  }
  return { dates, capped: cursor <= toIso };
}

function shiftDateIso(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
