import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ProvenanceBanner } from './provenance-banner';
import { formatCount, formatShare } from './report-formatting';
import { deriveAverageCheck, sumAcrossDays } from './report-rollup';
import { ReportsFilterState } from './reports-filter-state';
import { BucketResponse, ProvenanceResponse, ReportingApi } from './reporting-api';

/**
 * `sla_bucket_set.v1` — the platform-fixed, versioned six (`SlaBucketSet.java`).
 * Codes mirror the Java constants verbatim: `UNDER_30`, `M30_35`, `M35_40`,
 * `M40_50`, `M50_60`, `OVER_60` — half-open minute intervals
 * `[0,30) [30,35) [35,40) [40,50) [50,60) [60,∞)`.
 */
const SLA_BUCKETS = ['UNDER_30', 'M30_35', 'M35_40', 'M40_50', 'M50_60', 'OVER_60'] as const;

type SlaBucketCode = (typeof SLA_BUCKETS)[number];

/** `TPipe` needs a literal `MessageKey`, never a concatenated string — see its own doc. */
const SLA_BUCKET_LABEL_KEYS: Readonly<Record<SlaBucketCode, MessageKey>> = {
  UNDER_30: 'reports.branches.sla.bucket.UNDER_30',
  M30_35: 'reports.branches.sla.bucket.M30_35',
  M35_40: 'reports.branches.sla.bucket.M35_40',
  M40_50: 'reports.branches.sla.bucket.M40_50',
  M50_60: 'reports.branches.sla.bucket.M50_60',
  OVER_60: 'reports.branches.sla.bucket.OVER_60',
};

interface BranchRow {
  readonly locationId: string;
  readonly name: string;
  readonly orderCount: number;
  readonly grossSom: number;
  readonly averageCheckSom: number | null;
  readonly cancelledCount: number;
  readonly cancelShare: string;
  readonly prepMedianSeconds: number | null;
}

interface SlaRow {
  readonly locationId: string;
  readonly name: string;
  readonly buckets: Readonly<Record<string, { readonly count: number; readonly sharePercent: number }>>;
  readonly total: number;
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error' | 'singleLocation';

/**
 * 7.3 Branch & SLA reports (`frontend-information-architecture.md` §7.3,
 * `statistics.md` §2.3) — tier 2.
 *
 * **What is real here.** Table A is `GET .../reporting/queries` grouped by
 * `LOCATION`, the same shape `business-overview-page.ts`'s own top-five
 * branch table already proves, extended to every branch and to cancellation
 * share; `Ср. время приготовления` is one `.../preparation-time` call per
 * branch — that endpoint has no `groupBy` (a median cannot be composed from
 * per-slice medians, per its own doc), and at pilot scale a handful of
 * branches is a handful of calls, not a fan-out problem. Table B is
 * `GET .../reporting/sla-buckets`, already grouped by branch — no new
 * aggregation, this screen is the first console reader of it.
 *
 * **What is scoped down.** `Ср. время доставки` is not a column: `fact_order`
 * carries no delivery-specific timing and ADR 0042's delivery facts are not
 * in `reporting` yet (`delivery_cost_variance.v1`'s own registry entry says
 * `sourceAvailable: false`). Hidden for a single-location tenant, per the
 * spec's own instruction — the bucket distribution alone would be meaningful
 * for one branch, but is not worth a whole screen for it.
 */
@Component({
  selector: 'q-branch-sla-report-page',
  imports: [TPipe, ProvenanceBanner],
  templateUrl: './branch-sla-report-page.html',
  styleUrl: './branch-sla-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchSlaReportPage {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly locationsApi = inject(LocationsApi);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly branchRows = signal<readonly BranchRow[]>([]);
  protected readonly slaRows = signal<readonly SlaRow[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  /** `sla_bucket_set.v1` is the only version this build defines — see `SLA_BUCKETS`'s own doc. */
  protected readonly slaBucketSetVersion = 1;

  protected readonly slaBucketCodes = SLA_BUCKETS;
  protected readonly requestedTo = computed(() => this.filters.range().to);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected formatMoneyValue(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }

  protected formatCountValue = formatCount;

  protected bucketLabel(bucket: SlaBucketCode): string {
    return this.i18n.t(SLA_BUCKET_LABEL_KEYS[bucket]);
  }

  protected formatPrep(seconds: number | null): string {
    if (seconds === null) {
      return '—';
    }
    const minutes = Math.round(seconds / 60);
    return this.i18n.t('reports.branches.minutesShort', { minutes });
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
      const locations = await this.locationsApi.list(scope);
      if (locations.length <= 1) {
        this.state.set('singleLocation');
        return;
      }
      const nameById = new Map<string, string>(locations.map((loc: LocationView) => [loc.id, loc.displayName]));
      const range = this.filters.range();

      const [query, sla, prepByLocation] = await Promise.all([
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['revenue.gross.v1', 'orders.count.v1', 'orders.cancelled.v1'],
          groupBy: ['LOCATION'],
        }),
        this.api.slaBuckets(scope.tenantId, { from: range.from, to: range.to }),
        Promise.all(
          locations.map(async (loc) => {
            const result = await this.api.preparationTime(scope.tenantId, {
              from: range.from,
              to: range.to,
              locationId: [loc.id],
            });
            return [loc.id, result.medianSeconds] as const;
          }),
        ),
      ]);

      this.provenance.set(query.provenance);
      const prepById = new Map(prepByLocation);

      const buckets = sumAcrossDays(query.rows, (row) => row.locationId ?? '', [
        'revenue.gross.v1',
        'orders.count.v1',
        'orders.cancelled.v1',
      ]);

      this.branchRows.set(
        locations
          .map((loc) => {
            const values = buckets.get(loc.id) ?? {
              'revenue.gross.v1': 0,
              'orders.count.v1': 0,
              'orders.cancelled.v1': 0,
            };
            const orderCount = values['orders.count.v1'];
            const cancelled = values['orders.cancelled.v1'];
            return {
              locationId: loc.id,
              name: nameById.get(loc.id) ?? loc.id,
              orderCount,
              grossSom: values['revenue.gross.v1'],
              averageCheckSom: deriveAverageCheck(values['revenue.gross.v1'], orderCount),
              cancelledCount: cancelled,
              cancelShare: formatShare(cancelled, orderCount + cancelled),
              prepMedianSeconds: prepById.get(loc.id) ?? null,
            };
          })
          .sort((a, b) => b.grossSom - a.grossSom),
      );

      this.slaRows.set(buildSlaRows(sla.buckets, nameById));
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }
}

function buildSlaRows(
  buckets: readonly BucketResponse[],
  nameById: ReadonlyMap<string, string>,
): readonly SlaRow[] {
  const byLocation = new Map<string, Map<string, { count: number; sharePercent: number }>>();
  for (const bucket of buckets) {
    const forLocation = byLocation.get(bucket.locationId) ?? new Map();
    const existing = forLocation.get(bucket.bucketCode);
    forLocation.set(bucket.bucketCode, {
      count: (existing?.count ?? 0) + bucket.orderCount,
      sharePercent: Math.round(bucket.shareBasisPoints / 100),
    });
    byLocation.set(bucket.locationId, forLocation);
  }

  return [...byLocation.entries()]
    .map(([locationId, bucketMap]) => {
      const buckets: Record<string, { count: number; sharePercent: number }> = {};
      let total = 0;
      for (const code of SLA_BUCKETS) {
        const entry = bucketMap.get(code) ?? { count: 0, sharePercent: 0 };
        buckets[code] = entry;
        total += entry.count;
      }
      return { locationId, name: nameById.get(locationId) ?? locationId, buckets, total };
    })
    .sort((a, b) => b.total - a.total);
}
