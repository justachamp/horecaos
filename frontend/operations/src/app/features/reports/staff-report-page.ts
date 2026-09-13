import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { CallCentreApi, CallHourStat } from '../orders/call-centre-api';
import { ProvenanceBanner } from './provenance-banner';
import {
  formatAverage,
  formatCount,
  formatSecondsDuration,
  formatShare,
} from './report-formatting';
import { ReportsFilterState } from './reports-filter-state';
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

/** One operator's summed call activity across the range — see {@link loadTelephony}. */
interface TelephonyRow {
  readonly operatorPrincipalId: string;
  readonly offeredCount: number;
  readonly answeredCount: number;
  readonly missedCount: number;
  readonly transferredCount: number;
  readonly talkDurationSeconds: number;
}

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
 * code; a staff row renders `STAFF` and a truncated Keycloak subject, never a
 * bare UUID with no explanation, because the staff-identity ADR that would
 * resolve a real name has not landed.
 *
 * **Telephony (7.5b) is not blocked** on live telephony existing:
 * `CallStatsController` already serves offered/answered/missed/transferred
 * and talk seconds per hour per operator off `reporting.fact_call_hour`,
 * written by the same day-close pipeline, and had zero consumers before this
 * tab. Two of the three KPIs the IA names genuinely have no source yet and say
 * so on screen rather than rendering a wrong or invented number: answer speed
 * has no ring/wait column in V0149, and call-to-order conversion needs `1.6`'s
 * provenance wiring (`T-W01`) to start populating the write-once
 * `call-provenance` column.
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

  protected readonly tabs = TAB_DEFINITIONS;
  protected readonly activeTab = signal<StaffReportTab>('leaderboard');

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
  }

  protected selectTab(tab: StaffReportTab): void {
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
  protected formatAverageValue = formatAverage;
  protected formatDuration = formatSecondsDuration;

  protected formatHandling(seconds: number | null): string {
    return seconds === null ? '—' : formatSecondsDuration(seconds);
  }

  /** Never the full Keycloak subject in a table cell — see `order-rows-table.ts`'s `shortId` for the same move. */
  protected shortSubject(subject: string): string {
    return subject.slice(0, 8);
  }

  protected answerRate(row: TelephonyRow): string {
    return formatShare(row.answeredCount, row.offeredCount);
  }

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
      const existing = byOperator.get(hour.operatorPrincipalId) ?? {
        operatorPrincipalId: hour.operatorPrincipalId,
        offeredCount: 0,
        answeredCount: 0,
        missedCount: 0,
        transferredCount: 0,
        talkDurationSeconds: 0,
      };
      byOperator.set(hour.operatorPrincipalId, {
        operatorPrincipalId: hour.operatorPrincipalId,
        offeredCount: existing.offeredCount + hour.offeredCount,
        answeredCount: existing.answeredCount + hour.answeredCount,
        missedCount: existing.missedCount + hour.missedCount,
        transferredCount: existing.transferredCount + hour.transferredCount,
        talkDurationSeconds: existing.talkDurationSeconds + hour.talkDurationSeconds,
      });
    }
  }
  return [...byOperator.values()].sort((a, b) => b.offeredCount - a.offeredCount);
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
