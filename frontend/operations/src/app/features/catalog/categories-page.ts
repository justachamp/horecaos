import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { firstPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import {
  TreeView,
  TreeViewMove,
  TreeViewNode,
  TreeViewRename,
  TreeViewTone,
} from '../../shared/ui/tree-view';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import { CatalogSummary, CategorySummary, ProductSummary, toCatalogLocale } from './catalog-domain';
import { CreateCategoryDialog, CreateCategorySubmission } from './create-category-dialog';
import { MediaApi } from './media-api';

/**
 * catalog.md §4.3 — the category tree, its per-category form, and the
 * problems both used to have.
 *
 * **Categories were write-once before this wave.** `JdbcCatalogStore` had
 * only `INSERT INTO catalog.categories`; nothing let an operator rename a
 * category's code, re-parent it, re-order it, or archive it once created —
 * the only fix for a mis-parented category was to create another one. This
 * page now drives `CatalogApi.updateCategory`/`archiveCategory` through
 * `q-tree-view`'s drag-reorder and keyboard gestures (`onMove`) and its own
 * "Enter to rename" (`onRename`, name only — description still goes through
 * `setTranslation` from the detail pane's own field, and both preserve
 * whichever half the operator did not touch).
 *
 * **A tree, not an indented list.** `docs/frontend-information-architecture.md`
 * Part 4 named `SortableList / TreeView with drag-reorder` as unbuilt
 * anywhere in this monorepo (`X.23`); `q-tree-view` in `shared/ui/` is that
 * component, built here and shaped to be reused by modifier groups, website
 * menu ordering and the rest of that row's named consumers later. Its own
 * doc explains the cycle path this page used to render nothing for at all —
 * a category tree cannot itself be edited into a cycle any more (`updateCategory`
 * refuses one, surfaced as `CATEGORY_TREE_HAS_CYCLE` — see {@link describeCategoryError}),
 * but a tree built from data this page did not author deserves to show the
 * problem rather than silently drop rows.
 *
 * **sortOrder is a plain integer column, not a fractional rank.** A move
 * inserts the moved category at the target position and then renormalises
 * only the siblings whose position actually shifted — the same "no gaps,
 * no renumbering scheme" simplicity `submitCreate` below already assumed
 * for a brand-new category's own sortOrder.
 */
@Component({
  selector: 'q-categories-page',
  imports: [TPipe, CreateCategoryDialog, TreeView, ConfirmDialog, EmptyState],
  templateUrl: './categories-page.html',
  styleUrl: './categories-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CategoriesPage implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly mediaApi = inject(MediaApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly activeCatalogId = signal<string | null>(null);
  protected readonly categories = signal<readonly CategorySummary[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly products = signal<readonly ProductSummary[]>([]);

  protected readonly createDialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly treeBusy = signal(false);
  protected readonly treeError = signal<string | null>(null);

  protected readonly descriptionDraft = signal('');
  protected readonly codeDraft = signal('');
  protected readonly savingDetail = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly uploadingPhoto = signal(false);

  protected readonly archiveTarget = signal<CategorySummary | null>(null);
  protected readonly archiving = signal(false);

  protected readonly selected = computed<CategorySummary | null>(
    () => this.categories().find((category) => category.categoryId === this.selectedId()) ?? null,
  );

  /** `q-tree-view`'s own node shape — one per category, tone by status. */
  protected readonly treeNodes = computed<readonly TreeViewNode[]>(() =>
    this.categories().map((category) => ({
      id: category.categoryId,
      parentId: category.parentCategoryId ?? null,
      label: category.name,
      sortOrder: category.sortOrder,
      tone: this.toneFor(category.status),
      count: category.productCount,
    })),
  );

  protected readonly selectedProducts = computed<readonly ProductSummary[]>(() => {
    const category = this.selected();
    if (!category) {
      return [];
    }
    // Derived from the products read, per the wave brief: there is no
    // dedicated "products in this category" endpoint, and category
    // membership is only carried on ProductSummary as translated names, not
    // ids — a limitation honestly named rather than hidden, matching this
    // codebase's own convention for a scoped simplification.
    return this.products().filter((product) => product.categoryNames.includes(category.name));
  });

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
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
        this.categories.set(await firstValueFrom(this.api.listCategories(scope, first)));
        const productPage = await firstValueFrom(
          this.api.listProducts(scope, first, firstPage(200)),
        );
        this.products.set(productPage.items);
      }
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  private toneFor(status: string): TreeViewTone {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'DRAFT':
        return 'info';
      case 'ARCHIVED':
        return 'danger';
      default:
        return 'none';
    }
  }

  protected select(categoryId: string): void {
    this.selectedId.set(categoryId);
    const category = this.categories().find((c) => c.categoryId === categoryId);
    this.descriptionDraft.set(category?.description ?? '');
    this.codeDraft.set(category?.code ?? '');
    this.detailError.set(null);
  }

  protected categoryName(categoryId: string | null | undefined): string {
    if (!categoryId) {
      return this.i18n.t('catalog.categories.form.parentNone');
    }
    return this.categories().find((category) => category.categoryId === categoryId)?.name ?? '—';
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

  protected async submitCreate(submission: CreateCategorySubmission): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    if (!scope || !catalogId) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    try {
      await firstValueFrom(
        this.api.createCategory(scope, catalogId, {
          parentCategoryId: submission.parentCategoryId,
          code: submission.code,
          name: submission.name,
          locale: toCatalogLocale(this.i18n.locale()),
          sortOrder: this.categories().filter(
            (c) => (c.parentCategoryId ?? null) === (submission.parentCategoryId ?? null),
          ).length,
        }),
      );
      this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
      this.createDialogOpen.set(false);
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

  // ------------------------------------------------------------ tree gestures

  /**
   * Reparents and/or re-sorts a category. Inserts it at the requested
   * position among {@link TreeViewMove.newParentId}'s other children, then
   * renumbers only the siblings whose position actually shifted — `sortOrder`
   * is a plain integer column, so this never sends a fractional rank.
   */
  protected async onMove(move: TreeViewMove): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    const moved = this.categories().find((category) => category.categoryId === move.id);
    if (!scope || !catalogId || !moved) {
      return;
    }

    const siblings = this.categories()
      .filter(
        (category) =>
          category.categoryId !== move.id &&
          (category.parentCategoryId ?? null) === (move.newParentId ?? null),
      )
      .slice()
      .sort((a, b) => a.sortOrder - b.sortOrder);
    const clampedIndex = Math.max(0, Math.min(move.newIndex, siblings.length));
    const finalOrder = siblings.slice();
    finalOrder.splice(clampedIndex, 0, moved);

    this.treeBusy.set(true);
    this.treeError.set(null);
    try {
      await firstValueFrom(
        this.api.updateCategory(scope, catalogId, move.id, {
          parentCategoryId: move.newParentId,
          code: moved.code,
          sortOrder: clampedIndex,
        }),
      );
      for (const [index, category] of finalOrder.entries()) {
        if (category.categoryId !== move.id && category.sortOrder !== index) {
          await firstValueFrom(
            this.api.updateCategory(scope, catalogId, category.categoryId, {
              parentCategoryId: category.parentCategoryId ?? null,
              code: category.code,
              sortOrder: index,
            }),
          );
        }
      }
      this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
    } catch (error) {
      this.treeError.set(this.describeCategoryError(error));
      // Discards any optimistic drift; renders the tree exactly as the
      // server has it after a refused or partially-applied move.
      this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
    } finally {
      this.treeBusy.set(false);
    }
  }

  /** "Enter to rename" — name only. Description is untouched, resent as it already was. */
  protected async onRename(rename: TreeViewRename): Promise<void> {
    const scope = this.brand.scope();
    const category = this.categories().find((c) => c.categoryId === rename.id);
    if (!scope || !category) {
      return;
    }
    this.treeBusy.set(true);
    this.treeError.set(null);
    try {
      await firstValueFrom(
        this.api.setTranslation(scope, {
          entityType: 'CATEGORY',
          entityId: rename.id,
          locale: toCatalogLocale(this.i18n.locale()),
          name: rename.name,
          description: category.description ?? null,
        }),
      );
      this.categories.set(
        this.categories().map((c) =>
          c.categoryId === rename.id ? { ...c, name: rename.name } : c,
        ),
      );
    } catch (error) {
      this.treeError.set(this.describeCategoryError(error));
    } finally {
      this.treeBusy.set(false);
    }
  }

  private describeCategoryError(error: unknown): string {
    if (error instanceof ApiError && error.problem?.['findingCode'] === 'CATEGORY_TREE_HAS_CYCLE') {
      return this.i18n.t('catalog.editor.finding.CATEGORY_TREE_HAS_CYCLE');
    }
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  // ------------------------------------------------------------ detail pane

  protected setDescriptionDraft(value: string): void {
    this.descriptionDraft.set(value);
  }

  protected setCodeDraft(value: string): void {
    this.codeDraft.set(value);
  }

  /** Saves the description through `setTranslation`, resending the unchanged name. */
  protected async saveDescription(): Promise<void> {
    const scope = this.brand.scope();
    const category = this.selected();
    if (!scope || !category) {
      return;
    }
    this.savingDetail.set(true);
    this.detailError.set(null);
    try {
      await firstValueFrom(
        this.api.setTranslation(scope, {
          entityType: 'CATEGORY',
          entityId: category.categoryId,
          locale: toCatalogLocale(this.i18n.locale()),
          name: category.name,
          description: this.descriptionDraft().trim() || null,
        }),
      );
      this.categories.set(
        this.categories().map((c) =>
          c.categoryId === category.categoryId
            ? { ...c, description: this.descriptionDraft().trim() || null }
            : c,
        ),
      );
    } catch (error) {
      this.detailError.set(this.describeCategoryError(error));
    } finally {
      this.savingDetail.set(false);
    }
  }

  /** Saves the code through `updateCategory`, leaving parentCategoryId and sortOrder exactly as they are. */
  protected async saveCode(): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    const category = this.selected();
    const code = this.codeDraft().trim();
    if (!scope || !catalogId || !category || !code) {
      return;
    }
    this.savingDetail.set(true);
    this.detailError.set(null);
    try {
      await firstValueFrom(
        this.api.updateCategory(scope, catalogId, category.categoryId, {
          parentCategoryId: category.parentCategoryId ?? null,
          code,
          sortOrder: category.sortOrder,
        }),
      );
      this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
    } catch (error) {
      this.detailError.set(this.describeCategoryError(error));
    } finally {
      this.savingDetail.set(false);
    }
  }

  protected async uploadPhoto(file: File): Promise<void> {
    const scope = this.brand.scope();
    const category = this.selected();
    if (!scope || !category) {
      return;
    }
    this.uploadingPhoto.set(true);
    this.detailError.set(null);
    try {
      const asset = await firstValueFrom(
        this.mediaApi.upload(scope.tenantId, 'BRAND', scope.brandId, 'PUBLIC', file),
      );
      await firstValueFrom(
        this.api.attachMedia(scope, 'CATEGORY', category.categoryId, asset.assetId, {
          role: 'PRIMARY',
          sortOrder: 0,
        }),
      );
    } catch (error) {
      this.detailError.set(this.describeCategoryError(error));
    } finally {
      this.uploadingPhoto.set(false);
    }
  }

  protected onPhotoSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      void this.uploadPhoto(file);
    }
    input.value = '';
  }

  // ------------------------------------------------------------ archive

  protected requestArchive(category: CategorySummary): void {
    this.archiveTarget.set(category);
  }

  protected cancelArchive(): void {
    this.archiveTarget.set(null);
  }

  protected async confirmArchive(): Promise<void> {
    const scope = this.brand.scope();
    const catalogId = this.activeCatalogId();
    const category = this.archiveTarget();
    if (!scope || !catalogId || !category) {
      return;
    }
    this.archiving.set(true);
    try {
      await firstValueFrom(this.api.archiveCategory(scope, catalogId, category.categoryId));
      this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
      this.archiveTarget.set(null);
    } catch (error) {
      this.detailError.set(this.describeCategoryError(error));
    } finally {
      this.archiving.set(false);
    }
  }
}
