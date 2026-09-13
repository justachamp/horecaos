import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { CursorState, firstPage, nextPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { QCellDef, DataTable } from '../../shared/ui/data-table/data-table';
import {
  BulkAction,
  BulkActionEvent,
  DataTableColumn,
  RowAction,
  RowActionEvent,
} from '../../shared/ui/data-table/data-table-types';
import { AddToCategoryDialog, AddToCategorySubmission } from './add-to-category-dialog';
import { CatalogApi, ProductListFilters } from './catalog-api';
import { CatalogStatus, CatalogSummary, CategorySummary, ProductSummary } from './catalog-domain';
import { CreateProductDialog, CreateProductSubmission } from './create-product-dialog';
import { FiscalWorkbenchPanel } from './fiscal-workbench-panel';
import { describeApiError } from '../orders/order-errors';

type StatusTab = 'ALL' | 'ACTIVE' | 'DRAFT' | 'ARCHIVED' | 'NO_MXIK';

/** How long a keystroke in the search box waits before it becomes a server request. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * `q-data-table`'s filter model, so Save View / Apply View (`X.18`) has a
 * real effect on this screen — both the status tab and the free-text search
 * are what this page's own filter bar controls.
 */
interface ProductFilters {
  readonly tab: StatusTab;
  readonly search: string;
}

/**
 * catalog.md §4.1/§4.1a — the brand product library and the fiscal workbench.
 *
 * **Search and the status tabs are server-side (P21).** They used to be
 * computed client-side over whatever page of products was already loaded,
 * which is exactly wrong for the 1000+ item catalogue this row is written
 * for: a dish sitting past the loaded pages could not be found by search or
 * by tab. Every tab switch and every debounced keystroke now re-fetches from
 * `GET .../catalogs/{catalogId}/products?query=&status=`
 * (`CatalogQueryController`) instead. Tab *counts* pay for that: an exact
 * count across a filter this page has not fully paged through does not
 * exist without loading everything, so {@link tabCount} answers `null`
 * (rendered as no badge) for a tab that is not the one currently open, and
 * for the open one until its own `hasMore` goes false — the same "wrong
 * number is worse than none" rule `stop-list-page.ts`'s own `tabCount`
 * already lives by.
 *
 * **The row actions now exist**: duplicate, archive/restore, add to
 * category, stop in all branches, copy id, and copy the (computed, not
 * stored) public share slug. Selection and the bulk-action bar are `P03`'s
 * `q-data-table` primitives — bulk archive and bulk stop-in-all-branches are
 * each a client-side loop over their own audited single-product endpoint,
 * the same "no batch endpoint exists, so N independent calls" shape
 * `stop-list-page.ts`'s own bulk stop/unstop already uses.
 *
 * **The fiscal workbench (`4.1a`)** is `q-fiscal-workbench-panel`, opened
 * from the toolbar: "N of M priceable nodes unclassified" and a
 * `q-data-grid` fill-down over ИКПУ/package code/fiscal unit/fiscal name
 * across every unclassified `VARIANT`, `MODIFIER_OPTION` and `FEE` node.
 * **Trap, honoured here**: the `NO_MXIK` tab above is product-level
 * (`ProductSummary.hasMxik` — does *any* variant carry an ИКПУ) and the
 * workbench's count is node-level; they read differently by design and
 * neither view relabels the other's number as its own.
 */
@Component({
  selector: 'q-products-page',
  imports: [
    TPipe,
    CreateProductDialog,
    AddToCategoryDialog,
    FiscalWorkbenchPanel,
    InlineAlert,
    DataTable,
    QCellDef,
  ],
  templateUrl: './products-page.html',
  styleUrl: './products-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductsPage implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly brand = inject(CurrentBrand);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly columns = computed<readonly DataTableColumn[]>(() => {
    this.i18n.locale();
    return [
      { key: 'name', header: this.i18n.t('catalog.products.column.name') },
      { key: 'code', header: this.i18n.t('catalog.products.column.code') },
      { key: 'variants', header: this.i18n.t('catalog.products.column.variants'), numeric: true },
      { key: 'categories', header: this.i18n.t('catalog.products.column.categories') },
      { key: 'mxik', header: this.i18n.t('catalog.products.column.mxik') },
      { key: 'status', header: this.i18n.t('catalog.products.column.status') },
    ];
  });

  protected readonly rowActions = computed<readonly RowAction<ProductSummary>[]>(() => {
    this.i18n.locale();
    return [
      {
        id: 'duplicate',
        label: this.i18n.t('catalog.products.action.duplicate'),
        disabled: this.isBusyRow,
      },
      {
        id: 'archive',
        label: this.i18n.t('catalog.products.action.archive'),
        disabled: (product) => product.status === 'ARCHIVED' || this.isBusyRow(product),
        destructive: true,
      },
      {
        id: 'restore',
        label: this.i18n.t('catalog.products.action.restore'),
        disabled: (product) => product.status !== 'ARCHIVED' || this.isBusyRow(product),
      },
      {
        id: 'addToCategory',
        label: this.i18n.t('catalog.products.action.addToCategory'),
        disabled: this.isBusyRow,
      },
      {
        id: 'stopInAllBranches',
        label: this.i18n.t('catalog.products.action.stopInAllBranches'),
        disabled: this.isBusyRow,
      },
      { id: 'copyId', label: this.i18n.t('catalog.products.action.copyId') },
      { id: 'copyShareSlug', label: this.i18n.t('catalog.products.action.copyShareSlug') },
    ];
  });

  protected readonly bulkActions = computed<readonly BulkAction[]>(() => {
    this.i18n.locale();
    return [
      { id: 'archive', label: this.i18n.t('catalog.products.bulk.archive'), destructive: true },
      { id: 'stopInAllBranches', label: this.i18n.t('catalog.products.bulk.stopInAllBranches') },
    ];
  });

  protected readonly rowIdFn = (product: ProductSummary): string => product.productId;

  /** For `q-fiscal-workbench-panel`'s `[scope]` input — the template cannot read the private `brand` field directly. */
  protected readonly brandScope = computed<BrandScope | null>(() => this.brand.scope());

  /** `q-data-table`'s `scopeKey` — so a shared terminal's persisted filters and saved views never leak from one brand into another. */
  protected readonly scopeKey = computed<string | null>(() => {
    const scope = this.brand.scope();
    return scope ? `${scope.tenantId}:${scope.brandId}` : null;
  });

  protected readonly firstLoadComplete = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly activeCatalogId = signal<string | null>(null);
  protected readonly products = signal<readonly ProductSummary[]>([]);
  protected readonly page = signal<CursorState>(firstPage(50));
  protected readonly hasMore = signal(false);

  /** Rows are already server-filtered; only the severity order is applied here. */
  protected readonly sortedProducts = computed<readonly ProductSummary[]>(() =>
    [...this.products()].sort(compareBySeverity),
  );

  /**
   * `q-data-table`'s filter model — bound as `[filters]` + `(filtersChange)`
   * rather than the `[(filters)]` shorthand, deliberately: this page's own
   * {@link selectTab}/{@link onSearchInput} already know when *they* commit a
   * change and fetch for it directly, so only {@link onExternalFiltersChange}
   * needs to react to `q-data-table` changing this model on its own —
   * restoring a persisted filter from storage on mount, or a saved view's own
   * Apply button. A blanket "fetch whenever this changes" `effect` cannot
   * tell those two sources apart and either double-fetches on every
   * user-driven change or (once de-duplicated) risks swallowing a real one,
   * depending on exactly when `q-data-table`'s own mount-time restore effect
   * happens to flush relative to this page's.
   */
  protected readonly filters = signal<ProductFilters | null>({ tab: 'ALL', search: '' });
  protected readonly activeTab = computed<StatusTab>(() => this.filters()?.tab ?? 'ALL');
  protected readonly search = computed<string>(() => this.filters()?.search ?? '');

  /**
   * The search box's own displayed value — updated on every keystroke,
   * independent of {@link filters}'s debounced `search`, so typing never
   * waits on the network to feel responsive.
   */
  protected readonly searchInputValue = signal('');

  /**
   * The tab/search pair the page has already fetched (or is about to),
   * keyed as `${tab}::${search}`. Guards every fetch trigger — explicit or
   * external — against firing for a change that turns out not to be one,
   * such as `q-data-table` restoring "nothing was saved" (`null`) into a
   * value that resolves to the exact same default this page started with.
   * Pre-seeded to that default so a mount-time restore lands on a match
   * rather than looking like the first-ever change.
   */
  private lastFetchedKey: string = 'ALL::';

  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly busyProductIds = signal<ReadonlySet<string>>(new Set());
  protected readonly notice = signal<string | null>(null);

  protected readonly categories = signal<readonly CategorySummary[]>([]);
  protected readonly addToCategoryOpen = signal(false);
  protected readonly addToCategoryBusy = signal(false);
  protected readonly addToCategoryError = signal<string | null>(null);
  private addToCategoryTargetId: string | null = null;

  protected readonly workbenchOpen = signal(false);

  protected readonly createDialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    await this.loadCatalogsAndProducts();
  }

  private async loadCatalogsAndProducts(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    try {
      const catalogs = await firstValueFrom(this.api.listCatalogs(scope));
      this.catalogs.set(catalogs);
      const first = catalogs[0]?.catalogId ?? null;
      this.activeCatalogId.set(first);
      if (first) {
        await this.loadProducts(scope, first, firstPage(50));
      }
      this.denied.set(false);
      this.lastError.set(null);
    } catch (error) {
      this.handleLoadError(error);
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  private async loadProducts(
    scope: BrandScope,
    catalogId: string,
    state: CursorState,
    append = false,
  ): Promise<void> {
    const result = await firstValueFrom(
      this.api.listProducts(scope, catalogId, state, this.serverFilters()),
    );
    this.products.set(append ? [...this.products(), ...result.items] : result.items);
    this.hasMore.set(result.nextCursor !== null);
    this.page.set(nextPage(state, result) ?? state);
  }

  private serverFilters(): ProductListFilters {
    const tab = this.activeTab();
    const search = this.search().trim();
    return {
      query: search === '' ? undefined : search,
      status: tab === 'ALL' ? undefined : tab,
    };
  }

  protected async loadMore(): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!scope || !catalogId) {
      return;
    }
    this.loadingMore.set(true);
    try {
      await this.loadProducts(scope, catalogId, this.page(), true);
    } catch (error) {
      this.handleLoadError(error);
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async switchCatalog(catalogId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.activeCatalogId.set(catalogId);
    await this.refetch();
  }

  /** Re-fetches the active catalog's first page under the current tab/search — every mutation and every filter change funnels through this. */
  private async refetch(): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!scope || !catalogId) {
      return;
    }
    this.firstLoadComplete.set(false);
    try {
      await this.loadProducts(scope, catalogId, firstPage(50));
    } catch (error) {
      this.handleLoadError(error);
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  private handleLoadError(error: unknown): void {
    if (error instanceof ApiError) {
      if (error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else {
        this.lastError.set(error);
      }
    } else {
      throw error;
    }
  }

  /** Immediate — a tab is a click, not a keystroke, and needs no debounce. */
  protected selectTab(tab: StatusTab): void {
    if (this.searchDebounceHandle !== null) {
      clearTimeout(this.searchDebounceHandle);
      this.searchDebounceHandle = null;
    }
    const next: ProductFilters = { tab, search: this.filters()?.search ?? '' };
    this.filters.set(next);
    this.commitFetch(next);
  }

  /**
   * Updates the box instantly (see {@link searchInputValue}) so typing never
   * waits on the network to feel responsive, and commits to {@link filters}
   * — fetching for it — only once the debounce elapses with no further
   * keystroke.
   */
  protected onSearchInput(value: string): void {
    this.searchInputValue.set(value);
    if (this.searchDebounceHandle !== null) {
      clearTimeout(this.searchDebounceHandle);
    }
    this.searchDebounceHandle = setTimeout(() => {
      this.searchDebounceHandle = null;
      const next: ProductFilters = { tab: this.filters()?.tab ?? 'ALL', search: value };
      this.filters.set(next);
      this.commitFetch(next);
    }, SEARCH_DEBOUNCE_MS);
  }

  /**
   * `q-data-table`'s `(filtersChange)` — fired only when the table changes
   * `filters` on its own: restoring a persisted value from storage on
   * mount, or a saved view's own Apply button. Never fired for this page's
   * own {@link selectTab}/{@link onSearchInput}, which already know they are
   * the source and fetch directly through {@link commitFetch}.
   */
  protected onExternalFiltersChange(value: ProductFilters | null): void {
    this.filters.set(value);
    this.searchInputValue.set(value?.search ?? '');
    this.commitFetch(value);
  }

  /** De-duplicated re-fetch: a no-op unless `value` actually differs from the tab/search this page has already fetched. */
  private commitFetch(value: ProductFilters | null): void {
    const key = `${value?.tab ?? 'ALL'}::${value?.search ?? ''}`;
    if (key === this.lastFetchedKey) {
      return;
    }
    this.lastFetchedKey = key;
    void this.refetch();
  }

  /**
   * `null` while this tab's own rows are not fully loaded, or while it is
   * not the tab currently open at all — a wrong count across a 1000+ item
   * catalogue is worse than none, and this page only ever holds one status's
   * worth of rows in memory at a time.
   */
  protected tabCount(tab: StatusTab): number | null {
    if (tab !== this.activeTab()) {
      return null;
    }
    return this.hasMore() ? null : this.products().length;
  }

  protected openProduct(productId: string): void {
    void this.router.navigate(['/catalog/products', productId]);
  }

  protected onRowClick(product: ProductSummary): void {
    this.openProduct(product.productId);
  }

  protected severityCaption(product: ProductSummary): string | null {
    if (product.variantCount === 0) {
      return this.i18n.t('catalog.products.severity.noVariant');
    }
    if (!product.hasMxik) {
      return this.i18n.t('catalog.products.severity.noMxik');
    }
    return null;
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return this.i18n.t('catalog.status.ACTIVE');
      case 'DRAFT':
        return this.i18n.t('catalog.status.DRAFT');
      case 'ARCHIVED':
        return this.i18n.t('catalog.status.ARCHIVED');
      default:
        return status;
    }
  }

  protected isBusy(productId: string): boolean {
    return this.busyProductIds().has(productId);
  }

  /**
   * Bound as a field, not a method: `q-data-table` calls a `RowAction.disabled`
   * function as a bare reference (`action.disabled(row)`), and only an arrow
   * function keeps `this` lexical across that call.
   */
  private readonly isBusyRow = (product: ProductSummary): boolean => this.isBusy(product.productId);

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  // ---------------------------------------------------------------- create

  protected openCreateDialog(): void {
    this.createError.set(null);
    this.createDialogOpen.set(true);
  }

  protected closeCreateDialog(): void {
    this.createDialogOpen.set(false);
  }

  protected async submitCreate(submission: CreateProductSubmission): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!scope || !catalogId) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    try {
      const created = await firstValueFrom(
        this.api.createProduct(scope, catalogId, {
          code: submission.code,
          name: submission.name,
          locale: submission.locale,
        }),
      );
      this.createDialogOpen.set(false);
      void this.router.navigate(['/catalog/products', created.productId]);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.creating.set(false);
    }
  }

  // ------------------------------------------------------------ row actions

  protected onRowAction(event: RowActionEvent<ProductSummary>): void {
    switch (event.actionId) {
      case 'duplicate':
        void this.duplicateProduct(event.row);
        return;
      case 'archive':
        void this.applyStatus(event.row, 'ARCHIVED');
        return;
      case 'restore':
        void this.applyStatus(event.row, 'ACTIVE');
        return;
      case 'addToCategory':
        void this.openAddToCategory(event.row);
        return;
      case 'stopInAllBranches':
        void this.applyStopInAllBranches(event.row);
        return;
      case 'copyId':
        void this.copyToClipboard(event.row.productId, 'catalog.products.notice.idCopied');
        return;
      case 'copyShareSlug':
        void this.copyToClipboard(event.row.shareSlug, 'catalog.products.notice.slugCopied');
        return;
      default:
        return;
    }
  }

  private async duplicateProduct(product: ProductSummary): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.setBusy(product.productId, true);
    try {
      const created = await firstValueFrom(this.api.duplicateProduct(scope, product.productId));
      void this.router.navigate(['/catalog/products', created.productId]);
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(product.productId, false);
    }
  }

  private async applyStatus(product: ProductSummary, status: CatalogStatus): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.setBusy(product.productId, true);
    try {
      await firstValueFrom(this.api.setProductStatus(scope, product.productId, status));
      this.notice.set(
        this.i18n.t(
          status === 'ARCHIVED'
            ? 'catalog.products.notice.archived'
            : 'catalog.products.notice.restored',
        ),
      );
      // The active tab may filter on status, so the row's own place in the
      // list (or its disappearance from it) has to come from the server,
      // not from patching the row in place.
      await this.refetch();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(product.productId, false);
    }
  }

  private async applyStopInAllBranches(product: ProductSummary): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.setBusy(product.productId, true);
    try {
      const result = await firstValueFrom(this.api.stopInAllBranches(scope, product.productId));
      this.notice.set(
        this.i18n.t('catalog.products.notice.stopped', { count: result.locationsChanged }),
      );
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(product.productId, false);
    }
  }

  private async copyToClipboard(text: string, noticeKey: MessageKey): Promise<void> {
    try {
      await navigator.clipboard?.writeText(text);
    } catch {
      // Denied or unavailable (a non-secure context, a locked-down kiosk
      // profile). The notice still names what would have been copied, so
      // the operator can select it by hand from the row.
    }
    this.notice.set(this.i18n.t(noticeKey));
  }

  // -------------------------------------------------------- add to category

  protected async openAddToCategory(product: ProductSummary): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!scope || !catalogId) {
      return;
    }
    this.addToCategoryTargetId = product.productId;
    this.addToCategoryError.set(null);
    this.addToCategoryOpen.set(true);
    this.addToCategoryBusy.set(true);
    try {
      const categories = await firstValueFrom(this.api.listCategories(scope, catalogId));
      this.categories.set(categories);
    } catch (error) {
      this.addToCategoryError.set(this.describe(error));
    } finally {
      this.addToCategoryBusy.set(false);
    }
  }

  protected closeAddToCategory(): void {
    this.addToCategoryOpen.set(false);
    this.addToCategoryTargetId = null;
  }

  protected async submitAddToCategory(submission: AddToCategorySubmission): Promise<void> {
    const scope = this.brand.scope();
    const productId = this.addToCategoryTargetId;
    if (!scope || !productId) {
      return;
    }
    const category = this.categories().find((c) => c.categoryId === submission.categoryId);
    this.addToCategoryBusy.set(true);
    this.addToCategoryError.set(null);
    try {
      await firstValueFrom(
        this.api.placeInCategory(
          scope,
          submission.categoryId,
          productId,
          category?.productCount ?? 0,
        ),
      );
      this.addToCategoryOpen.set(false);
      this.addToCategoryTargetId = null;
      this.notice.set(this.i18n.t('catalog.products.notice.addedToCategory'));
      await this.refetch();
    } catch (error) {
      this.addToCategoryError.set(this.describe(error));
    } finally {
      this.addToCategoryBusy.set(false);
    }
  }

  // ----------------------------------------------------------- bulk actions

  protected async onBulkAction(event: BulkActionEvent): Promise<void> {
    const ids = event.rowIds;
    if (ids.length === 0) {
      return;
    }
    if (event.actionId === 'archive') {
      await this.bulkLoop(ids, (id) => this.setStatusQuiet(id, 'ARCHIVED'));
      await this.refetch();
    } else if (event.actionId === 'stopInAllBranches') {
      await this.bulkLoop(ids, (id) => this.stopInAllBranchesQuiet(id));
    }
    this.selectedIds.set(new Set());
  }

  private async bulkLoop(ids: readonly string[], op: (id: string) => Promise<void>): Promise<void> {
    let failed = 0;
    for (const id of ids) {
      this.setBusy(id, true);
      try {
        await op(id);
      } catch {
        failed++;
      } finally {
        this.setBusy(id, false);
      }
    }
    this.notice.set(
      failed > 0
        ? this.i18n.t('catalog.products.bulk.partial', { failed })
        : this.i18n.t('catalog.products.bulk.done', { count: ids.length }),
    );
  }

  private async setStatusQuiet(productId: string, status: CatalogStatus): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    await firstValueFrom(this.api.setProductStatus(scope, productId, status));
  }

  private async stopInAllBranchesQuiet(productId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    await firstValueFrom(this.api.stopInAllBranches(scope, productId));
  }

  // -------------------------------------------------------- fiscal workbench

  protected openWorkbench(): void {
    this.workbenchOpen.set(true);
  }

  protected closeWorkbench(): void {
    this.workbenchOpen.set(false);
    // A classification made in the workbench can change a product's
    // NO_MXIK-tab membership (product-level hasMxik), so the list is
    // refreshed once the operator is done rather than left to look stale.
    void this.refetch();
  }

  // --------------------------------------------------------------- helpers

  private setBusy(productId: string, busy: boolean): void {
    this.busyProductIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(productId);
      } else {
        next.delete(productId);
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

function compareBySeverity(a: ProductSummary, b: ProductSummary): number {
  const severity = (product: ProductSummary): number => {
    if (product.variantCount === 0) {
      return 0;
    }
    if (!product.hasMxik) {
      return 1;
    }
    if (product.status === 'DRAFT') {
      return 2;
    }
    return product.status === 'ARCHIVED' ? 4 : 3;
  };
  const diff = severity(a) - severity(b);
  return diff !== 0 ? diff : a.name.localeCompare(b.name);
}
