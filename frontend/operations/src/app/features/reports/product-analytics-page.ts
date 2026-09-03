import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ProvenanceBanner } from './provenance-banner';
import { formatCount } from './report-formatting';
import { ReportsFilterState } from './reports-filter-state';
import { ProvenanceResponse, ReportingApi, VariantSalesRowResponse } from './reporting-api';

interface ProductRow {
  readonly key: string;
  readonly name: string;
  readonly quantity: number;
  readonly grossSom: number;
  readonly netSom: number;
  readonly deliveryQuantity: number | null;
  readonly deliveryNetSom: number | null;
  readonly pickupQuantity: number | null;
  readonly pickupNetSom: number | null;
  readonly revenueSharePercent: number;
}

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * 7.7 Product analytics (`frontend-information-architecture.md` §7.7,
 * `statistics.md` §2.7) — tier 2. Only the «Продажи» tab this wave; the
 * spec's other two tabs stay named rather than built.
 *
 * **What is real here.** `GET .../reporting/variant-sales` (added this wave),
 * straight off `reporting.fact_order_line` joined to its own order for the
 * delivery/pickup split — one row per product, summed over the range.
 * `revenueSharePercent` is computed client-side from the page's own returned
 * totals (row ÷ sum of the returned page), not a registry metric: it is a
 * display-only share of the same figures already on screen, the same
 * category of arithmetic `business-overview-page.ts`'s channel-mix bars
 * already perform.
 *
 * **What is scoped down.** ABC (cumulative revenue share, class thresholds)
 * and XYZ (coefficient of variation) are not built: `reporting` has no
 * `classification_run`/`classification_result` tables and no registered
 * metric for either — `statistics.md` §4 names both as blocked on the same
 * gap. Rather than a second empty tab, this screen names the gap once, inline.
 */
@Component({
  selector: 'q-product-analytics-page',
  imports: [TPipe, ProvenanceBanner],
  templateUrl: './product-analytics-page.html',
  styleUrl: './product-analytics-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductAnalyticsPage {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  private readonly filters = inject(ReportsFilterState);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly rows = signal<readonly ProductRow[]>([]);
  protected readonly provenance = signal<ProvenanceResponse | null>(null);
  protected readonly maybeMore = signal(false);
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
      const result = await this.api.variantSales(scope.tenantId, {
        from: range.from,
        to: range.to,
        limit: 200,
      });
      this.provenance.set(result.provenance);
      this.maybeMore.set(result.maybeMore);
      const totalNet = result.rows.reduce((sum, row) => sum + row.totalNetSom, 0);
      this.rows.set(result.rows.map((row) => toProductRow(row, totalNet)));
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }
}

function toProductRow(row: VariantSalesRowResponse, totalNet: number): ProductRow {
  return {
    key: row.variantId ?? row.productName,
    name: row.productName,
    quantity: row.totalQuantity,
    grossSom: row.totalGrossSom,
    netSom: row.totalNetSom,
    deliveryQuantity: row.deliveryQuantity,
    deliveryNetSom: row.deliveryNetSom,
    pickupQuantity: row.pickupQuantity,
    pickupNetSom: row.pickupNetSom,
    revenueSharePercent: totalNet <= 0 ? 0 : Math.round((row.totalNetSom / totalNet) * 100),
  };
}
