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
import { I18n, Locale } from '../../core/i18n/i18n';
import { LocaleSet } from '../../core/i18n/locale-set';
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
 * The catalog's own configured default locale (`CatalogSnapshotLoader`'s
 * `horecaos.catalog.default-locale`, `uz` — see `toCatalogLocale`'s doc and
 * `product-editor-page.ts`'s own `CATALOG_DEFAULT_LOCALE`). `treeNodes()`'s
 * label and every list read (`CategorySummary.name`) resolve to this locale
 * specifically, never the viewer's own console language — so the tree's
 * quick-rename gesture (`onRename`) writes here too: writing wherever the
 * operator's own UI happened to be set (the bug this wave fixes) left a
 * rename that visibly did nothing, because the tree never reads the locale
 * that write landed in.
 */
const CATALOG_DEFAULT_LOCALE = 'uz';

/** One locale row of the content editor's per-locale grid (row 10.12). */
interface LocaleContentDraft {
  readonly locale: Locale;
  name: string;
  description: string;
}

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
 * `setTranslation` from the detail pane's own per-locale grid, and both
 * preserve whichever half the operator did not touch).
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
 *
 * **Row 10.12.** The detail pane's content editor used to write a category's
 * name and description against whichever locale the *operator's own console
 * language* happened to be set to (`toCatalogLocale(i18n.locale())`) — a
 * Russian-speaking operator editing a Russian-console session got a `ru`
 * translation row regardless of which locales the brand actually supports or
 * shows customers, and the edit was invisible in the tree either way (the
 * tree always resolves `CATALOG_DEFAULT_LOCALE`, see below). The content
 * editor is now a per-locale grid over `LocaleSet.locales()` — the brand's
 * own supported set, default first, the same pattern `location-detail-pane.ts`
 * and `channel-setup-page.ts` already carry. `catalog.translations` is
 * upserted one `(entity, locale)` row at a time (`CatalogAuthoringService
 * .translate`, never a whole-set replace), so a locale the brand has since
 * stopped supporting is simply never written here — hidden from the grid,
 * never touched, never deleted, with no merge step required the way a
 * whole-array write (`location-detail-pane.ts`'s own `savePlace`) needs one.
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
  private readonly localeSet = inject(LocaleSet);

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

  protected readonly codeDraft = signal('');
  protected readonly savingDetail = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly uploadingPhoto = signal(false);

  /**
   * Row 10.12: the brand's own supported locales (default first), or the
   * platform triple when unconfigured — always including `uz-Latn` even
   * when the brand's own set does not, the same forced inclusion
   * `product-editor-page.ts`'s own `editingLocales` applies to
   * `CATALOG_DEFAULT_LOCALE`: `treeNodes()`'s label and every list read
   * resolve a category's name in that locale specifically (this file's own
   * doc), so a grid that could never write it would leave those reads
   * permanently falling back to the bare code for a brand that dropped it.
   */
  protected readonly knownLocales = computed<readonly Locale[]>(() => {
    const brandLocales = this.localeSet.locales();
    return brandLocales.includes('uz-Latn') ? brandLocales : [...brandLocales, 'uz-Latn'];
  });
  protected readonly localeContentDraft = signal<readonly LocaleContentDraft[]>([]);
  protected readonly savingContent = signal(false);
  protected readonly contentError = signal<string | null>(null);

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
    // Row 10.12: resolved alongside the brand scope, not after it — without
    // this, knownLocales() never advances past LocaleSet's platform
    // fallback (locale-set.ts's own doc; batch 12's fix on the location and
    // channel-setup editors showed how this gets missed).
    await Promise.all([this.brand.ensureLoaded(), this.localeSet.ensureLoaded()]);
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
    this.codeDraft.set(category?.code ?? '');
    this.detailError.set(null);
    this.loadLocaleContentDraft(category ?? null);
  }

  /** Row 10.12: one row per brand-supported locale, read from the category's own `translations` map — not the flattened `name`/`description`, which is only the catalog's configured default locale. */
  private loadLocaleContentDraft(category: CategorySummary | null): void {
    this.localeContentDraft.set(
      this.knownLocales().map((locale) => {
        const existing = category?.translations[toCatalogLocale(locale)];
        return { locale, name: existing?.name ?? '', description: existing?.description ?? '' };
      }),
    );
    this.contentError.set(null);
  }

  protected setLocaleName(locale: Locale, name: string): void {
    this.localeContentDraft.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, name } : row)),
    );
  }

  protected setLocaleDescription(locale: Locale, description: string): void {
    this.localeContentDraft.update((rows) =>
      rows.map((row) => (row.locale === locale ? { ...row, description } : row)),
    );
  }

  /** Row 10.12: whether this locale is the brand's own required default, for the grid's visible marker. */
  protected isDefaultLocale(locale: Locale): boolean {
    return this.localeSet.defaultLocale() === locale;
  }

  protected localeLabel(locale: Locale): string {
    switch (locale) {
      case 'ru':
        return this.i18n.t('settings.brandProfile.locale.ru');
      case 'uz-Latn':
        return this.i18n.t('settings.brandProfile.locale.uzLatn');
      case 'en':
        return this.i18n.t('settings.brandProfile.locale.en');
    }
  }

  /**
   * Writes every grid row whose name is non-blank, one `PUT .../translations`
   * per locale — `CatalogAuthoringController.setTranslation` takes exactly
   * one `(entity, locale)` pair and refuses a blank name (`@NotBlank`), so a
   * row the operator left empty is simply never sent: not a delete (there is
   * no delete endpoint for a translation), a no-op that leaves whatever that
   * locale already had untouched.
   */
  protected async saveContent(): Promise<void> {
    const scope = this.brand.scope();
    const category = this.selected();
    if (!scope || !category || this.savingContent()) {
      return;
    }
    const writes = this.localeContentDraft().filter((row) => row.name.trim() !== '');
    this.savingContent.set(true);
    this.contentError.set(null);
    try {
      for (const row of writes) {
        await firstValueFrom(
          this.api.setTranslation(scope, {
            entityType: 'CATEGORY',
            entityId: category.categoryId,
            locale: toCatalogLocale(row.locale),
            name: row.name.trim(),
            description: row.description.trim() || null,
          }),
        );
      }
      const catalogId = this.activeCatalogId();
      if (catalogId) {
        this.categories.set(await firstValueFrom(this.api.listCategories(scope, catalogId)));
        this.loadLocaleContentDraft(
          this.categories().find((c) => c.categoryId === category.categoryId) ?? null,
        );
      }
    } catch (error) {
      this.contentError.set(this.describeCategoryError(error));
    } finally {
      this.savingContent.set(false);
    }
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
          // CATALOG_DEFAULT_LOCALE, not the operator's own transient console
          // language: the tree and every other list read resolve a
          // category's name in the catalog's configured default locale (see
          // this file's own doc), so a create authored in whatever the
          // operator's UI happened to be set to could leave a brand-new
          // category showing its bare code instead of a name the moment a
          // different operator's session — or this one's, after a locale
          // switch — reloads the tree.
          locale: CATALOG_DEFAULT_LOCALE,
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

  /**
   * "Enter to rename" — name only, in `CATALOG_DEFAULT_LOCALE`, never the
   * operator's own console language (this file's own doc explains why: the
   * tree's label resolves that locale specifically, so writing anywhere else
   * left a rename that appeared to do nothing). Description is untouched,
   * resent as it already was in that same locale.
   */
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
          locale: CATALOG_DEFAULT_LOCALE,
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

  protected setCodeDraft(value: string): void {
    this.codeDraft.set(value);
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
