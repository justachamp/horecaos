import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  TemplateRef,
  computed,
  contentChildren,
  effect,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { ActionMenu, ActionMenuItem } from '../action-menu';
import { SavedView, SavedViewsStore } from '../table/saved-views-store';
import { TableFilterStore } from '../table/table-filter-store';
import {
  BulkAction,
  BulkActionEvent,
  DataTableColumn,
  RowAction,
  RowActionEvent,
} from './data-table-types';

export interface QCellContext<T> {
  readonly $implicit: T;
}

/**
 * A column's cell content, projected by the caller:
 * `<ng-template qCell="price" let-row>{{ priceLabel(row) }}</ng-template>`.
 *
 * Mirrors CDK table's own `cdkCellDef` shape rather than inventing one, on
 * the theory that a pattern already familiar from Angular Material is
 * cheaper for the next reader than a bespoke one.
 */
@Directive({ selector: 'ng-template[qCell]' })
export class QCellDef<T> {
  readonly key = input.required<string>({ alias: 'qCell' });
  constructor(readonly templateRef: TemplateRef<QCellContext<T>>) {}
}

const INFINITE_SCROLL_THRESHOLD_PX = 48;

/**
 * `X.18` — the `<table class="table">` pattern every screen hand-wrote,
 * generalized: persisted per-tab filters and saved views (`viewId` opts in
 * to both), a column chooser, cursor paging **and** infinite scroll over the
 * same `rows`/`hasMore`/`loadMore` contract, a selection model with a
 * bulk-action bar, and a row action menu.
 *
 * Deliberately does not know what a row *is* — cell content is supplied per
 * column through a `qCell` template (see {@link QCellDef}), and paging is
 * driven by whatever the host page's own cursor loop already does. The one
 * thing this component owns is the chrome around the data, which is exactly
 * the part every hand-written table in this app re-implements slightly
 * differently.
 */
@Component({
  selector: 'q-data-table',
  imports: [NgTemplateOutlet, TPipe, ActionMenu],
  templateUrl: './data-table.html',
  styleUrl: './data-table.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataTable<T, F = Record<string, unknown>> {
  private readonly filterStore = inject(TableFilterStore);
  private readonly savedViewsStore = inject(SavedViewsStore);

  /** Stable per-screen key. Persisted filters, the column chooser, and saved views are all no-ops without it. */
  readonly viewId = input<string | null>(null);

  /**
   * The current tenant/brand/location, folded into every storage key
   * alongside `viewId` — without it, a shared terminal's next operator (a
   * shift change) or a support session under a different tenant (ADR 0081)
   * would silently see whichever location's filters and saved views happened
   * to save last, since `localStorage` is per-browser, not per-scope. A host
   * page with no scope concept of its own may leave this unset; the storage
   * key then falls back to the bare `viewId`, unchanged from before this
   * existed.
   */
  readonly scopeKey = input<string | null>(null);

  readonly columns = input.required<readonly DataTableColumn[]>();
  readonly rows = input.required<readonly T[]>();
  readonly rowId = input.required<(row: T) => string>();

  /** Two-way. The host page owns *how* a filter change re-fetches; this component only persists the value. */
  readonly filters = model<F | null>(null);

  readonly selectable = input(true);
  readonly bulkActions = input<readonly BulkAction[]>([]);
  /** Disables every bulk-action button without hiding the bar — for a host page that gates the action on something of its own (a reason field, a confirmation). */
  readonly bulkActionsDisabled = input(false);
  readonly rowActions = input<readonly RowAction<T>[]>([]);

  readonly hasMore = input(false);
  readonly loading = input(false);
  readonly pagingMode = input<'paged' | 'infinite'>('paged');

  readonly bulkAction = output<BulkActionEvent>();
  readonly rowAction = output<RowActionEvent<T>>();
  readonly loadMore = output<void>();
  /** A row was clicked anywhere outside its selection checkbox and row-menu button — the "click through to detail" pattern most tables in this app already use. */
  readonly rowClick = output<T>();

  protected readonly cellDefs = contentChildren(QCellDef);

  /**
   * Two-way, like `filters` — a host page binds `[(selectedIds)]` when it
   * needs to read the live selection (to gate a reason field on "is
   * anything selected") or clear it itself after a bulk action completes.
   * A one-way `selectionChange` output could tell the host what changed but
   * could never let the host change it back.
   */
  readonly selectedIds = model<ReadonlySet<string>>(new Set());
  protected readonly hiddenColumnKeys = signal<ReadonlySet<string>>(new Set());
  protected readonly chooserOpen = signal(false);
  protected readonly viewsMenuOpen = signal(false);
  protected readonly openRowMenuId = signal<string | null>(null);
  protected readonly newViewName = signal('');

  protected readonly visibleColumns = computed(() =>
    this.columns().filter((column) => !this.hiddenColumnKeys().has(column.key)),
  );

  protected readonly allOnPageSelected = computed(() => {
    const rows = this.rows();
    const rowIdFn = this.rowId();
    return rows.length > 0 && rows.every((row) => this.selectedIds().has(rowIdFn(row)));
  });

  protected readonly selectedCount = computed(() => this.selectedIds().size);

  /**
   * `SavedViewsStore.list()` reads `localStorage` directly rather than through
   * a signal, so `savedViews` below would otherwise never recompute after
   * `saveCurrentView()`/`removeView()` — bumped by both so the views menu
   * shows what was just saved or removed without a full reload.
   */
  private readonly savedViewsVersion = signal(0);

  /** `viewId`, folded together with `scopeKey` — see {@link scopeKey}. `null` until a screen key exists at all. */
  protected readonly storageKey = computed<string | null>(() => {
    const id = this.viewId();
    if (!id) {
      return null;
    }
    const scope = this.scopeKey();
    return scope ? `${scope}::${id}` : id;
  });

  protected readonly savedViews = computed<readonly SavedView<F>[]>(() => {
    const key = this.storageKey();
    this.savedViewsVersion();
    return key ? this.savedViewsStore.list<F>(key) : [];
  });

  /**
   * Guards the restore-on-init effect below from re-running for the same
   * storage key, and the persist effect from running before it. Keyed on
   * {@link storageKey}, not the bare `viewId` — a scope change (an operator
   * switching location without leaving the screen) must re-run this exactly
   * like a fresh mount would, so the newly-current scope's own filters load
   * instead of leaving the previous scope's filters on screen.
   */
  private restoredKey: string | null = null;

  constructor() {
    effect(() => {
      const key = this.storageKey();
      if (!key || key === this.restoredKey) {
        return;
      }
      this.restoredKey = key;
      // Unconditional, not "only if something was found": on a live scope
      // switch (the operator picks a different location without leaving the
      // screen) the previous scope's filters are still sitting in `filters()`,
      // and a scope with nothing saved yet must land on its own defaults —
      // `null` here, which every host page already treats as "no filter" —
      // not silently keep showing the last scope's choice.
      this.filters.set(this.filterStore.load<F | null>(this.filtersKey(key), null));
      this.hiddenColumnKeys.set(
        new Set(this.filterStore.load<readonly string[]>(this.columnsKey(key), [])),
      );
    });

    effect(() => {
      const key = this.storageKey();
      const current = this.filters();
      if (key && key === this.restoredKey) {
        this.filterStore.save(this.filtersKey(key), current);
      }
    });
  }

  protected cellTemplate(key: string): TemplateRef<QCellContext<T>> | null {
    return this.cellDefs().find((def) => def.key() === key)?.templateRef ?? null;
  }

  protected rowIdOf = (row: T): string => this.rowId()(row);
  protected trackByRowId = (_: number, row: T): string => this.rowIdOf(row);

  protected colspan(): number {
    return (
      this.visibleColumns().length +
      (this.selectable() ? 1 : 0) +
      (this.rowActions().length > 0 ? 1 : 0)
    );
  }

  // ---------------------------------------------------------------- selection

  protected isSelected(id: string): boolean {
    return this.selectedIds().has(id);
  }

  protected toggleRow(id: string): void {
    this.selectedIds.update((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected toggleAllOnPage(): void {
    const rows = this.rows();
    const rowIdFn = this.rowId();
    const allSelected = this.allOnPageSelected();
    this.selectedIds.update((current) => {
      const next = new Set(current);
      rows.forEach((row) => {
        if (allSelected) {
          next.delete(rowIdFn(row));
        } else {
          next.add(rowIdFn(row));
        }
      });
      return next;
    });
  }

  protected clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  protected runBulkAction(actionId: string): void {
    if (this.bulkActionsDisabled()) {
      return;
    }
    const rowIds = [...this.selectedIds()];
    if (rowIds.length === 0) {
      return;
    }
    this.bulkAction.emit({ actionId, rowIds });
  }

  // ------------------------------------------------------------- row actions

  /**
   * `q-action-menu` (ADR 0101) owns opening/closing and outside-click
   * dismissal itself; this component only tracks *which* row's menu is the
   * open one, since only one may be open across the whole table.
   */
  protected onRowMenuOpenChange(id: string, open: boolean): void {
    if (open) {
      this.openRowMenuId.set(id);
    } else if (this.openRowMenuId() === id) {
      this.openRowMenuId.set(null);
    }
  }

  protected runRowAction(actionId: string, row: T): void {
    this.openRowMenuId.set(null);
    this.rowAction.emit({ actionId, row });
  }

  protected isRowActionDisabled(action: RowAction<T>, row: T): boolean {
    return action.disabled?.(row) ?? false;
  }

  protected rowMenuItems(row: T): readonly ActionMenuItem[] {
    return this.rowActions().map((action) => ({
      id: action.id,
      label: action.label,
      destructive: action.destructive,
      disabled: this.isRowActionDisabled(action, row),
    }));
  }

  // ----------------------------------------------------------- column chooser

  protected toggleChooser(): void {
    this.chooserOpen.set(!this.chooserOpen());
  }

  protected isColumnVisible(key: string): boolean {
    return !this.hiddenColumnKeys().has(key);
  }

  protected toggleColumn(key: string): void {
    this.hiddenColumnKeys.update((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
    const storageKey = this.storageKey();
    if (storageKey) {
      this.filterStore.save(this.columnsKey(storageKey), [...this.hiddenColumnKeys()]);
    }
  }

  // -------------------------------------------------------------- saved views

  protected toggleViewsMenu(): void {
    this.viewsMenuOpen.set(!this.viewsMenuOpen());
  }

  protected onNewViewNameInput(value: string): void {
    this.newViewName.set(value);
  }

  protected saveCurrentView(): void {
    const key = this.storageKey();
    const name = this.newViewName().trim();
    if (!key || !name) {
      return;
    }
    this.savedViewsStore.save(key, name, this.filters());
    this.savedViewsVersion.update((version) => version + 1);
    this.newViewName.set('');
  }

  protected applyView(view: SavedView<F>): void {
    this.filters.set(view.filters);
    this.viewsMenuOpen.set(false);
  }

  protected removeView(savedViewId: string, event: Event): void {
    event.stopPropagation();
    const key = this.storageKey();
    if (key) {
      this.savedViewsStore.remove(key, savedViewId);
      this.savedViewsVersion.update((version) => version + 1);
    }
  }

  // ----------------------------------------------------------------- paging

  protected onScroll(event: Event): void {
    if (this.pagingMode() !== 'infinite' || !this.hasMore() || this.loading()) {
      return;
    }
    const target = event.target as HTMLElement;
    const distanceFromBottom = target.scrollHeight - target.scrollTop - target.clientHeight;
    if (distanceFromBottom <= INFINITE_SCROLL_THRESHOLD_PX) {
      this.loadMore.emit();
    }
  }

  protected onLoadMoreClick(): void {
    this.loadMore.emit();
  }

  private filtersKey(storageKey: string): string {
    return `${storageKey}.filters`;
  }

  private columnsKey(storageKey: string): string {
    return `${storageKey}.hiddenColumns`;
  }
}
