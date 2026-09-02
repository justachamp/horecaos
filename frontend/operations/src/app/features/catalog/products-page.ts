import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { CursorState, firstPage, nextPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CatalogApi } from './catalog-api';
import { CatalogSummary, ProductSummary } from './catalog-domain';
import { CreateProductDialog, CreateProductSubmission } from './create-product-dialog';
import { describeApiError } from '../orders/order-errors';

type StatusTab = 'ALL' | 'ACTIVE' | 'DRAFT' | 'ARCHIVED' | 'NO_MXIK';

/**
 * catalog.md §4.1 — the brand product library.
 *
 * **Scope, deliberately, against the backend as it exists today.** The spec's
 * full ergonomics (server-side debounced search, the severity sort, the
 * category rail, row actions Дублировать/Архивировать/Стоп-во-всех-филиалах,
 * bulk actions) assume endpoints this wave's backend audit did not find:
 * `CatalogAuthoringController` has no product search/filter query params, no
 * duplicate endpoint, no archive/status-change endpoint, and no bulk
 * endpoints at all (`docs/operations-spec/catalog.md`'s own "Data the backend
 * does not have yet" table names several of these). This screen therefore
 * does client-side search/filter over the loaded page rather than a
 * server-side one, omits row/bulk actions with no backing endpoint entirely
 * (Togora §2n: omit, do not disable) rather than rendering a button that
 * always fails, and its `Открыть полностью` drawer step from the spec's
 * layout is skipped — a row click goes straight to the full editor. See the
 * wave's final report for the complete list of deferrals.
 */
@Component({
  selector: 'q-products-page',
  imports: [TPipe, CreateProductDialog],
  templateUrl: './products-page.html',
  styleUrl: './products-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductsPage implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly brand = inject(CurrentBrand);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly activeCatalogId = signal<string | null>(null);
  protected readonly products = signal<readonly ProductSummary[]>([]);
  protected readonly page = signal<CursorState>(firstPage(50));
  protected readonly hasMore = signal(false);

  protected readonly activeTab = signal<StatusTab>('ALL');
  protected readonly search = signal('');

  protected readonly createDialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

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
    const result = await firstValueFrom(this.api.listProducts(scope, catalogId, state));
    this.products.set(append ? [...this.products(), ...result.items] : result.items);
    this.hasMore.set(result.nextCursor !== null);
    this.page.set(nextPage(state, result) ?? state);
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

  protected selectTab(tab: StatusTab): void {
    this.activeTab.set(tab);
  }

  protected onSearchInput(value: string): void {
    this.search.set(value);
  }

  protected visibleProducts(): readonly ProductSummary[] {
    const tab = this.activeTab();
    const query = this.search().trim().toLowerCase();
    return this.products()
      .filter((product) => {
        switch (tab) {
          case 'ACTIVE':
            return product.status === 'ACTIVE';
          case 'DRAFT':
            return product.status === 'DRAFT';
          case 'ARCHIVED':
            return product.status === 'ARCHIVED';
          case 'NO_MXIK':
            return !product.hasMxik;
          default:
            return true;
        }
      })
      .filter(
        (product) =>
          query === '' ||
          product.name.toLowerCase().includes(query) ||
          product.code.toLowerCase().includes(query),
      )
      .sort(compareBySeverity);
  }

  protected tabCount(tab: StatusTab): number {
    const products = this.products();
    switch (tab) {
      case 'ACTIVE':
        return products.filter((product) => product.status === 'ACTIVE').length;
      case 'DRAFT':
        return products.filter((product) => product.status === 'DRAFT').length;
      case 'ARCHIVED':
        return products.filter((product) => product.status === 'ARCHIVED').length;
      case 'NO_MXIK':
        return products.filter((product) => !product.hasMxik).length;
      default:
        return products.length;
    }
  }

  protected openProduct(productId: string): void {
    void this.router.navigate(['/catalog/products', productId]);
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
      this.createError.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.creating.set(false);
    }
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
