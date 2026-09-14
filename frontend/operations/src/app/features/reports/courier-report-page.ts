import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChartCategory } from '../../shared/ui/charts/chart-model';
import { HistogramChart } from '../../shared/ui/charts/histogram-chart';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { DeliveryTariffsApi } from '../delivery/delivery-tariffs-api';
import { DeliveryZonesApi } from '../delivery/delivery-zones-api';
import { ProvenanceBanner } from './provenance-banner';
import {
  formatCount,
  formatDistanceKm,
  formatSecondsDuration,
  formatShare,
} from './report-formatting';
import { ReportsFilterState } from './reports-filter-state';
import {
  CourierBucketResponse,
  ExternalDeliveryCostRowResponse,
  ProvenanceResponse,
  ReportingApi,
} from './reporting-api';

/** `sla_bucket_set.v1` — see `branch-sla-report-page.ts`'s own doc; the same fixed six, over a courier's own span. */
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

/**
 * `courier.domain.MatchStatus`'s five values (ADR 0042), every status
 * `.../external-delivery-cost` can answer with. A closed `Record` rather than
 * a computed key string, the same reason `SLA_BUCKET_LABEL_KEYS` above is one:
 * `MessageKey` makes a missing translation a `tsc` error, and a computed
 * `` `reports...${status}` `` key would defeat that check silently.
 */
const RECONCILIATION_STATUS_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  PENDING: 'reports.couriers.externalCost.status.PENDING',
  MATCHED: 'reports.couriers.externalCost.status.MATCHED',
  VARIANCE: 'reports.couriers.externalCost.status.VARIANCE',
  UNBILLED: 'reports.couriers.externalCost.status.UNBILLED',
  UNMATCHED_LINE: 'reports.couriers.externalCost.status.UNMATCHED_LINE',
};

interface LeaderboardRow {
  readonly courierId: string;
  readonly name: string;
  readonly deliveryCount: number;
  readonly minDistanceMeters: number;
  readonly maxDistanceMeters: number;
  readonly avgDistanceMeters: number;
  readonly totalDistanceMeters: number;
  readonly avgTransitSeconds: number;
  readonly totalTransitSeconds: number;
  readonly onTimeShare: number | null;
}

interface CourierSlaRow {
  readonly courierId: string;
  readonly name: string;
  readonly buckets: Readonly<
    Record<string, { readonly count: number; readonly sharePercent: number }>
  >;
  readonly total: number;
}

interface TariffAuditRow {
  readonly key: string;
  readonly tariffName: string;
  readonly zoneName: string;
  readonly bandSequence: number | null;
  readonly courierName: string;
  readonly resolutionCount: number;
  readonly totalFinalFeeMinor: number;
  readonly currency: string;
}

interface ExternalCostRow {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly orderTotalMinor: number;
  readonly currency: string;
  readonly chargedDeliveryMinor: number;
  readonly shipmentId: string;
  readonly providerType: string | null;
  readonly providerBilledMinor: number | null;
  readonly varianceMinor: number | null;
  readonly reconciliationStatus: string;
  readonly reconcileActionAvailable: boolean;
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * 7.4 Courier reports (`frontend-information-architecture.md` §7.4,
 * `operations-gap-map.md` T11 / ADR 0125) — tier 2, the first console reader
 * of `reporting.fact_delivery`.
 *
 * Four reads, four sections on one page — the same "one page, several tables"
 * shape `branch-sla-report-page.ts` already uses for 7.3/7.3a:
 *
 * - **7.4** the leaderboard, `.../couriers/leaderboard`.
 * - **7.4a** the courier scope of the SLA distribution, `.../couriers/sla-buckets`.
 * - **7.4b** the delivery-sum-by-tariff audit, `.../couriers/tariff-audit` — not
 *   business-day grained, so it ignores the shared filter bar's period pills
 *   only in the sense that "today" reads today's resolutions rather than a
 *   closed business day; it still honours the same `[from, to]` the other
 *   sections do.
 * - **7.4c** per-order external-delivery cost, `.../couriers/external-delivery-cost`,
 *   with the per-line reconcile action the row names.
 *
 * No response here carries a courier's protected name (ADR 0029/ADR 0042):
 * every row is keyed on `courierId` and resolved through `CouriersApi.roster`'s
 * own `displayReference`, the same P19 reveal every other courier-facing
 * screen in this console already uses. Tariff and zone names resolve the
 * same way, through `DeliveryTariffsApi`/`DeliveryZonesApi`.
 *
 * The 7.4c reconcile action asks a fixed confirmation rather than collecting
 * free text: `ConfirmDialog` has no reason field of its own, and building one
 * for a single one-click action was scoped out of this wave — the audit fact
 * still records who and when, with a constant, honestly-labelled reason.
 */
@Component({
  selector: 'q-courier-report-page',
  imports: [TPipe, ProvenanceBanner, HistogramChart, ConfirmDialog],
  templateUrl: './courier-report-page.html',
  styleUrl: './courier-report-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierReportPage {
  private readonly api = inject(ReportingApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly tariffsApi = inject(DeliveryTariffsApi);
  private readonly zonesApi = inject(DeliveryZonesApi);
  private readonly location = inject(CurrentLocation);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly leaderboardRows = signal<readonly LeaderboardRow[]>([]);
  protected readonly slaRows = signal<readonly CourierSlaRow[]>([]);
  protected readonly tariffRows = signal<readonly TariffAuditRow[]>([]);
  protected readonly costRows = signal<readonly ExternalCostRow[]>([]);
  protected readonly costTotalVarianceMinor = signal(0);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly slaBucketSetVersion = 1;
  protected readonly slaBucketCodes = SLA_BUCKETS;
  protected readonly requestedTo = computed(() => this.filters.range().to);

  protected readonly reconcileTarget = signal<ExternalCostRow | null>(null);
  protected readonly reconcileBusy = signal(false);

  /** Every courier that appears anywhere on this page, over `sla_bucket_set.v1`'s six buckets. */
  protected readonly slaHistogram = computed<readonly ChartCategory[]>(() => {
    const totals = new Map<SlaBucketCode, number>(SLA_BUCKETS.map((code) => [code, 0]));
    for (const row of this.slaRows()) {
      for (const code of SLA_BUCKETS) {
        totals.set(code, (totals.get(code) ?? 0) + row.buckets[code].count);
      }
    }
    return SLA_BUCKETS.map((code) => ({
      key: code,
      label: this.bucketLabel(code),
      value: totals.get(code) ?? 0,
    }));
  });

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected formatMoneyValue(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected readonly formatCountValue = formatCount;
  protected readonly formatDistanceValue = formatDistanceKm;

  protected formatDuration(seconds: number): string {
    return formatSecondsDuration(seconds);
  }

  protected formatOnTimeShare(share: number | null): string {
    return share === null ? '—' : `${Math.round(share * 100)}%`;
  }

  protected bucketLabel(bucket: SlaBucketCode): string {
    return this.i18n.t(SLA_BUCKET_LABEL_KEYS[bucket]);
  }

  protected reconciliationLabel(status: string): string {
    const key = RECONCILIATION_STATUS_LABEL_KEYS[status] as MessageKey | undefined;
    return key ? this.i18n.t(key) : status;
  }

  protected askReconcile(row: ExternalCostRow): void {
    this.reconcileTarget.set(row);
  }

  protected cancelReconcile(): void {
    this.reconcileTarget.set(null);
  }

  protected async confirmReconcile(): Promise<void> {
    const row = this.reconcileTarget();
    const tenantId = this.tenantId;
    if (!row || !tenantId) {
      return;
    }
    this.reconcileBusy.set(true);
    try {
      await this.couriersApi.reconcileExternalDeliveryCost(
        tenantId,
        row.shipmentId,
        'Operator confirmed the external-delivery cost reconciliation from the reports screen.',
      );
      this.reconcileTarget.set(null);
      await this.loadExternalCost(tenantId);
    } finally {
      this.reconcileBusy.set(false);
    }
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.tenantId = scope.tenantId;
    try {
      const range = this.filters.range();

      const [roster, tariffs, zones, leaderboard, sla, tariffAudit, externalCost] =
        await Promise.all([
          this.couriersApi.roster(scope.tenantId),
          this.tariffsApi.list(scope),
          this.zonesApi.list(scope),
          this.api.courierLeaderboard(scope.tenantId, { from: range.from, to: range.to }),
          this.api.courierSlaBuckets(scope.tenantId, { from: range.from, to: range.to }),
          this.api.courierTariffAudit(scope.tenantId, { from: range.from, to: range.to }),
          this.api.courierExternalDeliveryCost(scope.tenantId, { from: range.from, to: range.to }),
        ]);

      const nameByCourier = new Map<string, string>(
        roster.map((r: RosterEntryResponse) => [r.courierId, r.displayReference]),
      );
      const nameByTariff = new Map<string, string>(tariffs.map((t) => [t.tariffId, t.name]));
      const nameByZone = new Map<string, string>(
        zones.map((z) => [z.zoneId, z.displayNameRu || z.displayNameEn || z.code]),
      );
      const courierName = (id: string): string => nameByCourier.get(id) ?? id.slice(0, 8);

      this.provenance.set(leaderboard.provenance);

      this.leaderboardRows.set(
        leaderboard.rows
          .map((row) => ({
            courierId: row.courierId,
            name: courierName(row.courierId),
            deliveryCount: row.deliveryCount,
            minDistanceMeters: row.minDistanceMeters,
            maxDistanceMeters: row.maxDistanceMeters,
            avgDistanceMeters: row.avgDistanceMeters,
            totalDistanceMeters: row.totalDistanceMeters,
            avgTransitSeconds: row.avgTransitHours * 3600,
            totalTransitSeconds: row.totalTransitSeconds,
            onTimeShare: row.onTimeShare,
          }))
          .sort((a, b) => b.totalDistanceMeters - a.totalDistanceMeters),
      );

      this.slaRows.set(buildSlaRows(sla.buckets, courierName));

      this.tariffRows.set(
        tariffAudit.rows
          .map((row) => ({
            key: `${row.tariffId}:${row.tariffVersion}:${row.zoneId ?? ''}:${row.bandSequence ?? ''}:${row.courierId ?? ''}`,
            tariffName: nameByTariff.get(row.tariffId) ?? row.tariffId.slice(0, 8),
            zoneName: row.zoneId ? (nameByZone.get(row.zoneId) ?? row.zoneId.slice(0, 8)) : '—',
            bandSequence: row.bandSequence,
            courierName: row.courierId ? courierName(row.courierId) : '—',
            resolutionCount: row.resolutionCount,
            totalFinalFeeMinor: row.totalFinalFeeMinor,
            currency: row.currency,
          }))
          .sort((a, b) => b.totalFinalFeeMinor - a.totalFinalFeeMinor),
      );

      this.costTotalVarianceMinor.set(externalCost.totalVarianceMinor);
      this.costRows.set(externalCost.rows.map(toCostRow));

      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  /** Reloads only 7.4c after a reconcile action — the other three sections are untouched by it. */
  private async loadExternalCost(tenantId: string): Promise<void> {
    const range = this.filters.range();
    const externalCost = await this.api.courierExternalDeliveryCost(tenantId, {
      from: range.from,
      to: range.to,
    });
    this.costTotalVarianceMinor.set(externalCost.totalVarianceMinor);
    this.costRows.set(externalCost.rows.map(toCostRow));
  }
}

function toCostRow(row: ExternalDeliveryCostRowResponse): ExternalCostRow {
  return {
    orderId: row.orderId,
    publicOrderNumber: row.publicOrderNumber,
    orderTotalMinor: row.orderTotalMinor,
    currency: row.currency,
    chargedDeliveryMinor: row.chargedDeliveryMinor,
    shipmentId: row.shipmentId,
    providerType: row.providerType,
    providerBilledMinor: row.providerBilledMinor,
    varianceMinor: row.varianceMinor,
    reconciliationStatus: row.reconciliationStatus,
    reconcileActionAvailable: row.reconcileActionAvailable,
  };
}

function buildSlaRows(
  buckets: readonly CourierBucketResponse[],
  courierName: (id: string) => string,
): readonly CourierSlaRow[] {
  const byCourier = new Map<string, Map<string, { count: number; sharePercent: number }>>();
  for (const bucket of buckets) {
    const forCourier = byCourier.get(bucket.courierId) ?? new Map();
    const existing = forCourier.get(bucket.bucketCode);
    forCourier.set(bucket.bucketCode, {
      count: (existing?.count ?? 0) + bucket.orderCount,
      sharePercent: Math.round(bucket.shareBasisPoints / 100),
    });
    byCourier.set(bucket.courierId, forCourier);
  }

  return [...byCourier.entries()]
    .map(([courierId, bucketMap]) => {
      const resolved: Record<string, { count: number; sharePercent: number }> = {};
      let total = 0;
      for (const code of SLA_BUCKETS) {
        const entry = bucketMap.get(code) ?? { count: 0, sharePercent: 0 };
        resolved[code] = entry;
        total += entry.count;
      }
      return { courierId, name: courierName(courierId), buckets: resolved, total };
    })
    .sort((a, b) => b.total - a.total);
}
