import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { formatMoney } from '../../core/format/money';
import { firstPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { DataGrid } from '../../shared/ui/data-grid/data-grid';
import { DataGridColumn } from '../../shared/ui/data-grid/data-grid-types';
import { MoneyOrPercent, MoneyOrPercentKind } from '../../shared/ui/money-or-percent';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import {
  BulkPriceChangeItemOutcome,
  BulkPriceChangeReport,
  VariantAvailabilityRow,
} from './catalog-domain';
import { PricingApi } from './pricing-api';

type Direction = 'INCREASE' | 'DECREASE';
type RowStatus = 'PENDING' | 'WOULD_APPLY' | 'WOULD_FAIL' | 'APPLIED' | 'FAILED';

/** One grid row — the variant joined against its current price, the calculator's own output, and the last server outcome. */
interface BulkRow {
  readonly variantId: string;
  readonly productName: string;
  readonly category: string;
  readonly currentAmountMinor: number | null;
  readonly newAmountMinor: number | null;
  readonly status: RowStatus;
  readonly problemCode: string | null;
}

/** Every priceable variant this cap admits into one call — matches `resolvedPrices`/`bulkApply`'s own `@Size(max = 200)`. */
const SELECTION_CAP = 200;

function computeNewAmount(
  current: number,
  kind: MoneyOrPercentKind,
  amountMinor: number,
  basisPoints: number,
  direction: Direction,
): number {
  const delta = kind === 'AMOUNT' ? amountMinor : Math.round((current * basisPoints) / 10_000);
  const signedDelta = direction === 'DECREASE' ? -delta : delta;
  return Math.max(0, current + signedDelta);
}

/**
 * IA 4.8b — bulk price change (Прейскурант), `q-data-grid`'s consumer.
 *
 * **Built this wave.** Raising every price 10% meant editing each variant by
 * hand in the product editor, one `PUT .../variant-prices/{id}` at a time,
 * with no preview of what the change would cost and no way to scope it to a
 * category. This screen filters a location's sellable variants (reusing
 * `CatalogApi.variantsAtLocation`) by category and free text, joins them
 * against current amounts through the read primitive that already existed —
 * `GET .../resolved/prices`, capped at 200 ids, which is why this screen's
 * own selection is capped there too — computes a new amount per row with
 * `q-money-or-percent`'s percent/absolute choice and a direction toggle, and
 * calls the new server-side `POST .../prices/bulk-apply` once for the whole
 * batch: a "Предпросмотр" dry run reports what every row would cost without
 * writing anything, and "Применить" commits it, item by item, with a
 * per-row outcome — never the N racing, atomicity-free `PUT`s composing this
 * client-side would have produced.
 *
 * **`q-data-grid` here is a preview surface, not an edit surface.** Its
 * columns render current/new/status and are not editable: the calculator
 * computes every row's new amount from one shared percent or amount, and an
 * operator who wants a different rule for one dish still uses the product
 * editor's own price cell. Wiring the grid's own cell-edit-and-batch-save
 * affordance to a single shared formula applied to two hundred rows at once
 * would fight the component's own model (built for hand-editing individual
 * cells) rather than use it, so this screen drives the batch call directly
 * and uses the grid only for its virtualized, ARIA-grid rendering of a
 * selection that can run into the hundreds.
 */
@Component({
  selector: 'q-bulk-price-change-page',
  imports: [TPipe, DataGrid, MoneyOrPercent],
  templateUrl: './bulk-price-change-page.html',
  styleUrl: './bulk-price-change-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BulkPriceChangePage implements OnInit {
  private readonly catalogApi = inject(CatalogApi);
  private readonly pricingApi = inject(PricingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly priceBookId = signal<string | null>(null);
  protected readonly currency = signal<string | null>(null);
  protected readonly variants = signal<readonly VariantAvailabilityRow[]>([]);
  protected readonly currentPrices = signal<Readonly<Record<string, number>>>({});

  protected readonly categoryFilter = signal('');
  protected readonly search = signal('');

  protected readonly direction = signal<Direction>('INCREASE');
  protected readonly calculatorKind = signal<MoneyOrPercentKind>('PERCENT');
  protected readonly calculatorAmountMinor = signal(0);
  protected readonly calculatorBasisPoints = signal(1_000);

  protected readonly previewing = signal(false);
  protected readonly applying = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly lastReport = signal<BulkPriceChangeReport | null>(null);
  protected readonly outcomeByVariant = signal<ReadonlyMap<string, BulkPriceChangeItemOutcome>>(
    new Map(),
  );

  protected readonly categoryOptions = computed<readonly string[]>(() => {
    const set = new Set<string>();
    for (const row of this.variants()) {
      if (row.category) {
        set.add(row.category);
      }
    }
    return [...set].sort((a, b) => a.localeCompare(b));
  });

  /** The whole reason this page exists to be recomputed: every row's new price, freshly, whenever anything it depends on changes. */
  protected readonly gridRows = computed<readonly BulkRow[]>(() => {
    const category = this.categoryFilter();
    const query = this.search().trim().toLowerCase();
    const prices = this.currentPrices();
    const outcomes = this.outcomeByVariant();
    const report = this.lastReport();
    const kind = this.calculatorKind();
    const amountMinor = this.calculatorAmountMinor();
    const basisPoints = this.calculatorBasisPoints();
    const direction = this.direction();

    return this.variants()
      .filter((row) => !category || row.category === category)
      .filter((row) => !query || row.productName.toLowerCase().includes(query))
      .map((row) => {
        const current = prices[row.variantId] ?? null;
        const newAmount =
          current === null
            ? null
            : computeNewAmount(current, kind, amountMinor, basisPoints, direction);
        const outcome = outcomes.get(row.variantId);
        let status: RowStatus = 'PENDING';
        if (outcome) {
          status = outcome.applied
            ? report?.dryRun
              ? 'WOULD_APPLY'
              : 'APPLIED'
            : report?.dryRun
              ? 'WOULD_FAIL'
              : 'FAILED';
        }
        return {
          variantId: row.variantId,
          productName: row.productName,
          category: row.category ?? '—',
          currentAmountMinor: current,
          newAmountMinor: newAmount,
          status,
          problemCode: outcome?.problemCode ?? null,
        };
      });
  });

  protected readonly columns = computed<readonly DataGridColumn<BulkRow>[]>(() => {
    this.i18n.locale();
    return [
      {
        key: 'product',
        header: this.i18n.t('catalog.priceBulk.column.product'),
        getValue: (row) => row.productName,
      },
      {
        key: 'category',
        header: this.i18n.t('catalog.priceBulk.column.category'),
        getValue: (row) => row.category,
      },
      {
        key: 'current',
        header: this.i18n.t('catalog.priceBulk.column.current'),
        numeric: true,
        getValue: (row) => this.moneyLabel(row.currentAmountMinor),
      },
      {
        key: 'new',
        header: this.i18n.t('catalog.priceBulk.column.new'),
        numeric: true,
        getValue: (row) => this.moneyLabel(row.newAmountMinor),
      },
      {
        key: 'status',
        header: this.i18n.t('catalog.priceBulk.column.status'),
        getValue: (row) => this.statusLabel(row),
      },
    ];
  });

  protected readonly rowIdFn = (row: BulkRow): string => row.variantId;

  async ngOnInit(): Promise<void> {
    await Promise.all([this.brand.ensureLoaded(), this.location.ensureLoaded()]);
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const brandScope = this.brand.scope();
    const locationScope = this.location.scope();
    if (!brandScope || !locationScope) {
      this.denied.set(this.brand.denied() || this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const page = await firstValueFrom(
        this.catalogApi.variantsAtLocation(
          brandScope,
          locationScope.locationId,
          firstPage(SELECTION_CAP),
        ),
      );
      this.variants.set(page.items);

      const variantIds = page.items.map((row) => row.variantId);
      if (variantIds.length > 0) {
        const resolved = await firstValueFrom(
          this.pricingApi.resolvedVariantPrices(brandScope, locationScope.locationId, variantIds),
        );
        this.priceBookId.set(resolved.priceBookId ?? null);
        this.currency.set(resolved.currency ?? null);
        this.currentPrices.set(resolved.amountsMinor);
      } else {
        this.priceBookId.set(null);
        this.currency.set(null);
        this.currentPrices.set({});
      }
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reloadPrices(): Promise<void> {
    const brandScope = this.brand.scope();
    const locationScope = this.location.scope();
    const variantIds = this.variants().map((row) => row.variantId);
    if (!brandScope || !locationScope || variantIds.length === 0) {
      return;
    }
    const resolved = await firstValueFrom(
      this.pricingApi.resolvedVariantPrices(brandScope, locationScope.locationId, variantIds),
    );
    this.currentPrices.set(resolved.amountsMinor);
  }

  protected canRunBulk(): boolean {
    return (
      !this.previewing() &&
      !this.applying() &&
      this.priceBookId() !== null &&
      this.gridRows().some((row) => row.currentAmountMinor !== null)
    );
  }

  protected async preview(): Promise<void> {
    await this.runBulkApply(true);
  }

  protected async apply(): Promise<void> {
    await this.runBulkApply(false);
  }

  private async runBulkApply(dryRun: boolean): Promise<void> {
    const scope = this.brand.scope();
    const priceBookId = this.priceBookId();
    if (!scope || !priceBookId || !this.canRunBulk()) {
      return;
    }
    const items = this.gridRows()
      .filter((row): row is BulkRow & { newAmountMinor: number } => row.newAmountMinor !== null)
      .map((row) => ({
        priceableType: 'VARIANT' as const,
        priceableId: row.variantId,
        amountMinor: row.newAmountMinor,
      }));
    if (items.length === 0) {
      return;
    }

    if (dryRun) {
      this.previewing.set(true);
    } else {
      this.applying.set(true);
    }
    this.actionError.set(null);
    try {
      const report = await firstValueFrom(
        this.pricingApi.bulkApplyPrices(scope, priceBookId, items, dryRun),
      );
      this.lastReport.set(report);
      this.outcomeByVariant.set(new Map(report.items.map((item) => [item.priceableId, item])));
      if (!dryRun) {
        await this.reloadPrices();
      }
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      if (dryRun) {
        this.previewing.set(false);
      } else {
        this.applying.set(false);
      }
    }
  }

  protected moneyLabel(amountMinor: number | null): string {
    const currency = this.currency();
    if (amountMinor === null || !currency) {
      return '—';
    }
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }

  protected statusLabel(row: BulkRow): string {
    const suffix = row.problemCode ? `: ${row.problemCode}` : '';
    switch (row.status) {
      case 'PENDING':
        return '';
      case 'WOULD_APPLY':
        return this.i18n.t('catalog.priceBulk.status.wouldApply');
      case 'WOULD_FAIL':
        return this.i18n.t('catalog.priceBulk.status.wouldFail') + suffix;
      case 'APPLIED':
        return this.i18n.t('catalog.priceBulk.status.applied');
      case 'FAILED':
        return this.i18n.t('catalog.priceBulk.status.failed') + suffix;
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
