import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { CursorState, Page, firstPage, nextPage, resetOnFilterChange } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import {
  CategorySummary,
  PriceBookMatrixRow,
  PriceBookSummary,
  listResolutionLocale,
} from './catalog-domain';
import { perQuantumLabel } from './per-quantum';
import { PricingApi } from './pricing-api';

/** The matrix loads and "load more"s this many rows a call, matching `variantsAtLocation`'s own default page. */
const PAGE_SIZE = 100;

/**
 * IA 4.8a's price-book matrix — before this a book's variant prices could
 * only be seen one product at a time, through the product editor's own
 * per-variant price cell in a loop with no way to compare against what the
 * brand actually charges today.
 *
 * **The read**: `PriceAuthoringController`'s new `GET .../matrix`, cursor-
 * paginated, brand-scoped, filterable by category and by "differs from
 * base" (a row where this book's price and the brand's live base price are
 * not the same, including a row priced at brand scope but not yet in this
 * book).
 *
 * **The write** reuses the existing single-variant `PUT
 * .../variant-prices/{id}` (`PricingApi.setVariantPrice`) rather than a new
 * bulk endpoint — a matrix cell save is exactly the edit the product editor
 * already makes, one at a time, and this screen's own value is showing many
 * of them next to their base price, not batching the write. Each save sends
 * the row's own `bookPriceVersion` as `If-Match`; a `409` means someone else
 * changed that specific price since this row was read, and the row is
 * re-read from a fresh first page rather than guessed at locally.
 *
 * **Export (row 4.8a's own "otherwise say notDone"): not built.**
 * `ReportExportRequest` has no `brandId` or `priceBookId` field — every
 * report the export centre knows today is tenant-wide and date-ranged
 * (`ReportExportController.ReportExportRequest`) — so a book-scoped export
 * needs the shared request contract widened, not one registry entry. That is
 * not the trivial addition the brief conditions this on.
 */
@Component({
  selector: 'q-price-book-matrix-page',
  imports: [TPipe, RouterLink],
  templateUrl: './price-book-matrix-page.html',
  styleUrl: './price-book-matrix-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PriceBookMatrixPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly pricingApi = inject(PricingApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);
  private readonly localeSet = inject(LocaleSet);

  /**
   * Row 10.12: the catalog locale the matrix resolves product and category
   * names in -- the brand's own default when it has configured a set, the
   * server's configured locale otherwise (`listResolutionLocale`). It used to
   * follow the operator's console language, so the same row read differently
   * to two operators of one brand.
   */
  private readonly listLocale = computed<string>(() =>
    listResolutionLocale(this.localeSet.isConfigured(), this.localeSet.defaultLocale()),
  );

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly notFound = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly book = signal<PriceBookSummary | null>(null);
  protected readonly categories = signal<readonly CategorySummary[]>([]);
  protected readonly rows = signal<readonly PriceBookMatrixRow[]>([]);

  protected readonly categoryFilter = signal('');
  protected readonly differsFromBaseOnly = signal(false);

  private pageState: CursorState = firstPage(PAGE_SIZE);
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);

  /** One draft amount per variant, keyed by id — the input's own text, not yet saved. */
  protected readonly draftValues = signal<Readonly<Record<string, string>>>({});
  protected readonly savingRowId = signal<string | null>(null);
  protected readonly rowError = signal<Readonly<Record<string, string>>>({});

  private priceBookId = '';

  async ngOnInit(): Promise<void> {
    await Promise.all([this.brand.ensureLoaded(), this.localeSet.ensureLoaded()]);
    const priceBookId = this.route.snapshot.paramMap.get('priceBookId');
    const scope = this.brand.scope();
    if (!scope || !priceBookId) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    this.priceBookId = priceBookId;

    try {
      this.book.set(await firstValueFrom(this.pricingApi.readPriceBook(scope, priceBookId)));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        this.notFound.set(true);
      } else if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(this.describe(error));
      } else {
        throw error;
      }
      this.loading.set(false);
      return;
    }

    try {
      const catalogs = await firstValueFrom(this.catalogApi.listCatalogs(scope));
      const firstCatalogId = catalogs[0]?.catalogId ?? null;
      if (firstCatalogId) {
        this.categories.set(
          await firstValueFrom(this.catalogApi.listCategories(scope, firstCatalogId)),
        );
      }
    } catch {
      // The category filter degrades to "no options"; the matrix itself
      // still loads and every row still shows whatever category it carries.
    }

    await this.reload();
    this.loading.set(false);
  }

  protected onCategoryFilterChange(value: string): void {
    this.categoryFilter.set(value);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.reload();
  }

  protected onDiffersFromBaseChange(checked: boolean): void {
    this.differsFromBaseOnly.set(checked);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.reload();
  }

  protected async loadMore(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.loadingMore() || !this.hasMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await firstValueFrom(
        this.pricingApi.matrix(scope, this.priceBookId, this.pageState, this.filters()),
      );
      this.rows.update((current) => [...current, ...page.items]);
      this.seedDrafts(page.items, true);
      this.advanceCursor(page);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected editValueFor(row: PriceBookMatrixRow): string {
    const draft = this.draftValues()[row.variantId];
    if (draft !== undefined) {
      return draft;
    }
    return row.bookPriceMinor === null || row.bookPriceMinor === undefined
      ? ''
      : String(row.bookPriceMinor);
  }

  protected onEditInput(variantId: string, value: string): void {
    this.draftValues.update((current) => ({ ...current, [variantId]: value }));
  }

  protected moneyLabel(amountMinor: number | null | undefined, currency: string): string {
    if (amountMinor === null || amountMinor === undefined) {
      return '—';
    }
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }

  /** «per 100 g» beside the prices of a variant sold by weight (ADR 0137); null for every other. */
  protected unitLabel(row: PriceBookMatrixRow): string | null {
    return perQuantumLabel(this.i18n, row.catchweightQuantumGrams);
  }

  protected deltaLabel(row: PriceBookMatrixRow): string {
    if (row.deltaMinor === null || row.deltaMinor === undefined) {
      return '—';
    }
    const sign = row.deltaMinor > 0 ? '+' : '';
    return (
      sign +
      formatMoney({ amountMinor: row.deltaMinor, currency: row.currency }, this.i18n.locale())
    );
  }

  protected errorFor(variantId: string): string | null {
    return this.rowError()[variantId] ?? null;
  }

  protected async savePrice(row: PriceBookMatrixRow): Promise<void> {
    const scope = this.brand.scope();
    const raw = this.editValueFor(row).replace(/[^\d]/g, '');
    const amountMinor = Number.parseInt(raw, 10);
    if (!scope || this.savingRowId() !== null || !Number.isFinite(amountMinor)) {
      return;
    }
    this.savingRowId.set(row.variantId);
    this.setRowError(row.variantId, null);
    try {
      await firstValueFrom(
        this.pricingApi.setVariantPrice(
          scope,
          this.priceBookId,
          row.variantId,
          amountMinor,
          row.bookPriceVersion,
        ),
      );
      this.draftValues.update((current) => {
        const next = { ...current };
        delete next[row.variantId];
        return next;
      });
      // The write moved this row's own version, so the local copy is stale
      // the instant it succeeds — a fresh first page is the honest way to
      // show the version this screen can send back as `If-Match` next time,
      // the same "re-read rather than guess" `product-editor-page.ts` skips
      // only because it never needs a version at all.
      await this.reload();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        this.setRowError(row.variantId, this.i18n.t('catalog.priceMatrix.conflict'));
        await this.reload();
      } else {
        this.setRowError(row.variantId, this.describe(error));
      }
    } finally {
      this.savingRowId.set(null);
    }
  }

  private setRowError(variantId: string, message: string | null): void {
    this.rowError.update((current) => {
      const next = { ...current };
      if (message === null) {
        delete next[variantId];
      } else {
        next[variantId] = message;
      }
      return next;
    });
  }

  private filters(): { categoryId?: string; differsFromBase?: boolean; locale?: string } {
    const categoryId = this.categoryFilter();
    return {
      ...(categoryId ? { categoryId } : {}),
      ...(this.differsFromBaseOnly() ? { differsFromBase: true } : {}),
      locale: this.listLocale(),
    };
  }

  private async reload(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.pageState = firstPage(PAGE_SIZE);
    this.loadError.set(null);
    try {
      const page = await firstValueFrom(
        this.pricingApi.matrix(scope, this.priceBookId, this.pageState, this.filters()),
      );
      this.rows.set(page.items);
      this.seedDrafts(page.items, false);
      this.advanceCursor(page);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  private advanceCursor(page: Page<PriceBookMatrixRow>): void {
    const next = nextPage(this.pageState, page);
    this.hasMore.set(next !== null);
    if (next) {
      this.pageState = next;
    }
  }

  /**
   * Drops draft edits for the rows just (re)loaded, so a stale typed amount
   * never lingers once the server's own value is back in view. `merge` keeps
   * drafts for rows already on screen (`loadMore`); a fresh {@link reload}
   * replaces the whole page and clears every draft.
   */
  private seedDrafts(rows: readonly PriceBookMatrixRow[], merge: boolean): void {
    this.draftValues.update((current) => {
      const next: Record<string, string> = merge ? { ...current } : {};
      for (const row of rows) {
        delete next[row.variantId];
      }
      return next;
    });
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
