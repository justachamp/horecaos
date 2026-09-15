import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ChartHeatRow, ChartValueFormatter } from '../../shared/ui/charts/chart-model';
import { HeatmapChart } from '../../shared/ui/charts/heatmap-chart';
import { KpiTile, KpiTileFormula } from '../../shared/ui/charts/kpi-tile';
import { ProvenanceBanner } from './provenance-banner';
import { formatCount } from './report-formatting';
import { ReportsFilterState } from './reports-filter-state';
import {
  CohortResponse,
  CustomerKpiResponse,
  FrequencyBand,
  MetricResponse,
  ProvenanceResponse,
  RecencyBand,
  ReportingApi,
  RfmGridResponse,
  RowResponse,
} from './reporting-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/** One KPI tile — a value already formatted, plus the "?" panel's formula, or null while the dictionary has not loaded. */
interface KpiViewModel {
  readonly key: string;
  readonly labelKey: MessageKey;
  readonly value: string;
  readonly formula: KpiTileFormula | null;
}

/** One row of 7.6a's new-vs-returning revenue split. */
interface CustomerTypeRow {
  readonly key: 'NEW' | 'RETURNING';
  readonly labelKey: MessageKey;
  readonly revenueSom: number;
  readonly sharePercent: number;
}

const RECENCY_ORDER: readonly RecencyBand[] = ['R1_RECENT', 'R2_LAPSING', 'R3_AT_RISK'];
const FREQUENCY_ORDER: readonly FrequencyBand[] = ['F1_SINGLE', 'F2_FEW', 'F3_FREQUENT'];

/** One cell of 7.6b's R×F grid, with both figures the heatmap's single-value shape cannot carry together. */
interface RfmCellViewModel {
  readonly frequency: FrequencyBand;
  readonly memberCount: number;
  readonly revenueSom: number;
}

/** One row of 7.6b's grid — one Recency band, every Frequency band's cell in order. */
interface RfmRowViewModel {
  readonly recency: RecencyBand;
  readonly cells: readonly RfmCellViewModel[];
}

/**
 * 7.6/7.6a/7.6b Customer analytics (`frontend-information-architecture.md`
 * §7.6, wave T13) — was `NotBuiltPage`: `MetricRegistry` had no
 * customer-grain definition, so this route refused every tile outright.
 *
 * **What is real here.** Six KPI tiles (`GET .../reporting/customer-kpis`)
 * each carry statistics.md §1.2's published-formula "?" panel, from the one
 * `GET .../reporting/metrics` call every report page makes — the credibility
 * argument against Delever's unstated LTV. `customers.ltv.v1` has no tile:
 * it is registered `sourceAvailable = false` and this page names the gap
 * once rather than rendering a number that quietly stops at the query
 * range. New-vs-returning revenue is `revenue.new_vs_returning.v1` over the
 * typed `GET .../queries` grouped by `CUSTOMER_TYPE` — unlike every other
 * shape this feature reads from a bespoke endpoint, this one really is
 * `/queries`, sourced from `fact_order` directly rather than
 * `agg_branch_day`. Cohorts render as `HeatmapChart` (T09's chart family),
 * one row per cohort month, one column per month offset since — a month a
 * cohort has not reached yet is `null`, the heatmap's own "too thin to
 * plot" cell, never a fabricated zero. The RFM cross-tab (7.6b) is a plain
 * 3×3 table: platform-fixed Recency/Frequency bands, member count and
 * revenue per cell — distinct from 5.3's segment builder, which sizes one
 * segment rather than showing the whole grid.
 *
 * **What is scoped down.** The acquisition-source chart and the
 * visits→registrations→cart→orders funnel are 7.6c, blocked on ADR 0043's
 * open legal input for behavioural telemetry — not attempted here.
 */
@Component({
  selector: 'q-customer-analytics-page',
  imports: [TPipe, ProvenanceBanner, KpiTile, HeatmapChart],
  templateUrl: './customer-analytics-page.html',
  styleUrl: './customer-analytics-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerAnalyticsPage {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly requestedTo = computed(() => this.filters.range().to);

  private readonly metricsByCode = signal<ReadonlyMap<string, MetricResponse>>(new Map());
  private readonly kpis = signal<CustomerKpiResponse | null>(null);
  private readonly customerTypeRows = signal<readonly RowResponse[]>([]);
  protected readonly cohorts = signal<readonly CohortResponse[]>([]);
  protected readonly cohortWindowMonths = signal<number | null>(null);
  protected readonly cohortsRangeTooWide = signal(false);
  private readonly rfm = signal<RfmGridResponse | null>(null);

  protected readonly tiles = computed<readonly KpiViewModel[]>(() => {
    const kpis = this.kpis();
    if (!kpis) {
      return [];
    }
    return [
      this.tile(
        'newCustomers',
        'reports.customers.kpi.newCustomers',
        formatCount(kpis.newCustomers),
        'customers.new.v1',
      ),
      this.tile(
        'distinctCustomers',
        'reports.customers.kpi.distinctCustomers',
        formatCount(kpis.distinctCustomers),
        'customers.distinct.v1',
      ),
      this.tile(
        'repeatShare',
        'reports.customers.kpi.repeatShare',
        formatBasisPoints(kpis.repeatShareBasisPoints),
        'customers.repeat_share.v1',
      ),
      this.tile(
        'orderFrequency',
        'reports.customers.kpi.orderFrequency',
        kpis.orderFrequency === null ? '—' : kpis.orderFrequency.toFixed(2),
        'customers.order_frequency.v1',
      ),
      this.tile(
        'customerValue',
        'reports.customers.kpi.customerValue',
        kpis.customerValueSom === null ? '—' : this.formatMoneyValue(kpis.customerValueSom),
        'customers.value.v1',
      ),
      this.tile(
        'basketDepth',
        'reports.customers.kpi.basketDepth',
        kpis.basketDepth === null ? '—' : kpis.basketDepth.toFixed(1),
        'customers.basket_depth.v1',
      ),
    ];
  });

  /** 7.6: the "?" panel for the one built-but-tileless metric — LTV's own formula, shown as a note rather than a number. */
  protected readonly ltvFormula = computed<KpiTileFormula | null>(() =>
    this.formulaOf('customers.ltv.v1'),
  );

  protected readonly customerTypeTotal = computed(() =>
    this.customerTypeRows().reduce(
      (sum, row) => sum + (row.values['revenue.new_vs_returning.v1'] ?? 0),
      0,
    ),
  );

  protected readonly customerTypeTiles = computed<readonly CustomerTypeRow[]>(() => {
    const total = this.customerTypeTotal();
    const byType = new Map(
      this.customerTypeRows().map((row) => [
        row.customerType,
        row.values['revenue.new_vs_returning.v1'] ?? 0,
      ]),
    );
    return (['NEW', 'RETURNING'] as const).map((key) => {
      const revenueSom = byType.get(key) ?? 0;
      return {
        key,
        labelKey:
          key === 'NEW'
            ? 'reports.customers.newVsReturning.new'
            : 'reports.customers.newVsReturning.returning',
        revenueSom,
        sharePercent: total > 0 ? Math.round((revenueSom / total) * 100) : 0,
      };
    });
  });

  protected readonly newVsReturningFormula = computed<KpiTileFormula | null>(() =>
    this.formulaOf('revenue.new_vs_returning.v1'),
  );

  /** T09's heatmap needs a rectangular grid — every cohort padded to the widest cohort's offset, `null` past what it has reached. */
  protected readonly cohortHeatRows = computed<readonly ChartHeatRow[]>(() => {
    const cohorts = this.cohorts();
    if (cohorts.length === 0) {
      return [];
    }
    const maxOffset = Math.max(
      ...cohorts.flatMap((cohort) => cohort.points.map((point) => point.monthOffset)),
    );
    return cohorts.map((cohort) => {
      const byOffset = new Map(
        cohort.points.map((point) => [point.monthOffset, point.retainedBasisPoints]),
      );
      return {
        key: cohort.cohortMonth,
        label: cohort.cohortMonth.slice(0, 7),
        cells: Array.from({ length: maxOffset + 1 }, (_unused, offset) => {
          const basisPoints = byOffset.get(offset);
          return {
            key: String(offset),
            label: this.i18n.t('reports.customers.cohorts.monthOffset', { offset }),
            value: basisPoints === undefined || basisPoints === null ? null : basisPoints / 100,
          };
        }),
      };
    });
  });

  protected readonly rfmRows = computed<readonly RfmRowViewModel[]>(() => {
    const grid = this.rfm();
    const byKey = new Map(
      (grid?.cells ?? []).map((cell) => [`${cell.recencyBand}|${cell.frequencyBand}`, cell]),
    );
    return RECENCY_ORDER.map((recency) => ({
      recency,
      cells: FREQUENCY_ORDER.map((frequency) => {
        const cell = byKey.get(`${recency}|${frequency}`);
        return {
          frequency,
          memberCount: cell?.memberCount ?? 0,
          revenueSom: cell?.revenueSom ?? 0,
        };
      }),
    }));
  });

  protected readonly rfmTotalCustomers = computed(() => this.rfm()?.totalCustomers ?? 0);
  protected readonly frequencyBands = FREQUENCY_ORDER;

  /** {@link cohortHeatRows} already converts basis points to a whole percentage; this only appends the sign. */
  protected readonly cohortValueFormatter: ChartValueFormatter = (value) => `${Math.round(value)}%`;

  private scope: LocationScope | null = null;

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

  protected recencyLabel(band: RecencyBand): string {
    return this.i18n.t(`reports.customers.rfm.recency.${band}` as MessageKey);
  }

  protected frequencyLabel(band: FrequencyBand): string {
    return this.i18n.t(`reports.customers.rfm.frequency.${band}` as MessageKey);
  }

  private tile(key: string, labelKey: MessageKey, value: string, metricCode: string): KpiViewModel {
    return { key, labelKey, value, formula: this.formulaOf(metricCode) };
  }

  private formulaOf(metricCode: string): KpiTileFormula | null {
    const definition = this.metricsByCode().get(metricCode);
    if (!definition) {
      return null;
    }
    return {
      definition: definition.definition,
      inclusion: definition.includes,
      exclusion: definition.excludes,
      unit: definition.unit,
    };
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.scope = scope;

    const range = this.filters.range();
    const locationIds = this.filters.locationIds();
    const legalEntityIds = this.filters.legalEntityIds();
    const locationId = locationIds.length > 0 ? locationIds : undefined;
    const legalEntityId = legalEntityIds.length > 0 ? legalEntityIds : undefined;

    try {
      const [metrics, kpis, revenueByType, rfm] = await Promise.all([
        this.api.metrics(scope.tenantId).catch(() => [] as readonly MetricResponse[]),
        this.api.customerKpis(scope.tenantId, {
          from: range.from,
          to: range.to,
          locationId,
          legalEntityId,
        }),
        this.api.query(scope.tenantId, {
          from: range.from,
          to: range.to,
          metric: ['revenue.new_vs_returning.v1'],
          groupBy: ['CUSTOMER_TYPE', 'LEGAL_ENTITY'],
          locationId,
          legalEntityId,
        }),
        this.api.customerRfm(scope.tenantId, {
          from: range.from,
          to: range.to,
          locationId,
          legalEntityId,
        }),
      ]);

      this.metricsByCode.set(
        new Map(metrics.map((definition) => [definition.metricCode, definition])),
      );
      this.kpis.set(kpis);
      this.customerTypeRows.set(revenueByType.rows);
      this.rfm.set(rfm);
      this.provenance.set(kpis.provenance);

      // The cohort read has its own retention-window bound (12 months) and
      // is refused rather than truncated past it — a bound the other three
      // reads above do not share, so it is fetched separately and a refusal
      // here leaves the rest of the page standing.
      this.cohortsRangeTooWide.set(false);
      this.cohorts.set([]);
      this.cohortWindowMonths.set(null);
      try {
        const cohortResult = await this.api.customerCohorts(scope.tenantId, {
          from: range.from,
          to: range.to,
          locationId,
        });
        this.cohorts.set(cohortResult.cohorts);
        this.cohortWindowMonths.set(cohortResult.windowMonths);
      } catch (cohortError) {
        if (
          cohortError instanceof ApiError &&
          cohortError.problem?.['reason'] === 'COHORT_RANGE_TOO_WIDE'
        ) {
          this.cohortsRangeTooWide.set(true);
        } else if (!(cohortError instanceof ApiError)) {
          throw cohortError;
        }
      }

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

/** Basis points to a whole percentage — `null` (never `0%`) when there was nothing to share. */
function formatBasisPoints(basisPoints: number | null): string {
  if (basisPoints === null) {
    return '—';
  }
  return `${Math.round(basisPoints / 100)}%`;
}
