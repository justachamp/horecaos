import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import { CatalogSummary, CategorySummary, toCatalogLocale } from './catalog-domain';
import { CreateCategoryDialog, CreateCategorySubmission } from './create-category-dialog';

/** One category plus its indent depth, for a flat tree rendering (no drag-drop yet — see the page's own doc). */
interface CategoryNode {
  readonly category: CategorySummary;
  readonly depth: number;
}

/**
 * catalog.md §4.3 — the category tree and its per-category form.
 *
 * **Scope, deliberately.** The spec's drag-and-drop reordering needs a
 * `TreeView with drag-reorder` component the design-system gap list
 * (`docs/frontend-information-architecture.md` Part 4) names as not built
 * anywhere in this monorepo yet. This renders the same tree as an indented,
 * keyboard-navigable list instead — `sort_order` is still what orders it,
 * just not draggable in this wave. There is also no endpoint to change a
 * category's own `sort_order`/`parentCategoryId` after creation
 * (`CatalogAuthoringController` has no such PUT), so reordering/re-parenting
 * an *existing* category is not offered at all — only where it sits when
 * created.
 */
@Component({
  selector: 'q-categories-page',
  imports: [TPipe, CreateCategoryDialog],
  templateUrl: './categories-page.html',
  styleUrl: './categories-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CategoriesPage implements OnInit {
  private readonly api = inject(CatalogApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly catalogs = signal<readonly CatalogSummary[]>([]);
  protected readonly activeCatalogId = signal<string | null>(null);
  protected readonly categories = signal<readonly CategorySummary[]>([]);
  protected readonly selectedId = signal<string | null>(null);

  protected readonly createDialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly selected = computed<CategorySummary | null>(
    () => this.categories().find((category) => category.categoryId === this.selectedId()) ?? null,
  );

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

  /** A flat, indented rendering of the tree — see the class doc for why this is not a real `TreeView`. */
  protected nodes(): readonly CategoryNode[] {
    const byParent = new Map<string | null, CategorySummary[]>();
    for (const category of this.categories()) {
      const key = category.parentCategoryId ?? null;
      const siblings = byParent.get(key) ?? [];
      siblings.push(category);
      byParent.set(key, siblings);
    }
    for (const siblings of byParent.values()) {
      siblings.sort((a, b) => a.sortOrder - b.sortOrder);
    }

    const nodes: CategoryNode[] = [];
    const visit = (parentId: string | null, depth: number, guard: Set<string>): void => {
      for (const category of byParent.get(parentId) ?? []) {
        // A cycle is a publication blocker (`CATEGORY_TREE_HAS_CYCLE`), not
        // something this render may assume away — an infinite loop here
        // would hang the tab, not merely mis-render it.
        if (guard.has(category.categoryId)) {
          continue;
        }
        nodes.push({ category, depth });
        visit(category.categoryId, depth + 1, new Set(guard).add(category.categoryId));
      }
    };
    visit(null, 0, new Set());
    return nodes;
  }

  protected select(categoryId: string): void {
    this.selectedId.set(categoryId);
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
}
