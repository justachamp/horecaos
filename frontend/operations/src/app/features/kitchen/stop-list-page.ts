import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import { operationsPaths } from '../../core/api/operations-paths';
import { CursorState, firstPage, nextPage } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { QCellDef, DataTable } from '../../shared/ui/data-table/data-table';
import {
  BulkAction,
  BulkActionEvent,
  DataTableColumn,
} from '../../shared/ui/data-table/data-table-types';
import { describeApiError } from '../orders/order-errors';

type StopTab = 'ALL' | 'AVAILABLE' | 'ON_STOP';

/**
 * `q-data-table`'s `[(filters)]` model, so Save View / Apply View
 * (`X.18`) has a real effect on this screen: the active tab is the only
 * thing this page's own filter bar controls, so it is the whole shape.
 */
interface StopListFilters {
  readonly tab: StopTab;
}

/**
 * `StateActionRequest`/`advanceReasonCode`'s own pattern (`order-actions.ts`):
 * no dialog collects a reason for the single-row toggle, so this is the
 * fixed, honest value sent — a real, auditable statement that an operator
 * toggled availability from the console, not a placeholder. Bulk actions
 * collect their own shared reason instead, because a bulk stop is
 * consequential enough to ask why.
 */
const SINGLE_TOGGLE_REASON = 'OPERATIONS_STOP_LIST_TOGGLE';

/** Mirrors `CatalogAuthoringController.VariantAvailabilityResponse`. */
interface VariantAvailabilityResponse {
  readonly variantId: string;
  readonly productName: string;
  readonly category?: string | null;
  readonly available: boolean;
  readonly trackingMode?: string | null;
}

/**
 * Stop list — IA 2.5, `docs/operations-spec/orders.md` §5.5's "стоп" chip.
 *
 * **Built.** Available/on-stop tabs and the read side reuse catalog.md
 * §4.6's own screen — `catalogPaths.variantsAtLocation`
 * (`CatalogAuthoringController`, control-plane surface, same cross-surface
 * situation `catalog-paths.ts` already documents for the rest of Catalog).
 * The toggle is the audited `PUT .../inventory/variants/{id}/availability`
 * (`InventoryController`, operations surface). Bulk is a client-side loop
 * over that same single-item, audited endpoint — every row is its own
 * `Idempotency-Key`d command and its own `inventory.movements` fact, which is
 * more honest than a server contract this wave did not find any evidence of
 * (`GET .../inventory/availability` only checks variant ids the caller
 * already knows; there is no batch mutation endpoint).
 *
 * **Not built, honestly** (see the wave's final report for the full backend
 * audit): stop scope beyond LOCATION (menu/terminal/brand fan-out —
 * `INVENTORY_ADJUST` is LOCATION-scoped only); stop source (manual only —
 * `reasonCode` is free text, not a structured POS-push/threshold/schedule
 * taxonomy); a unified "why can't I sell this?" explainer (only
 * `available: boolean` + free-text reason exist); stop-change digest
 * notification.
 */
@Component({
  selector: 'q-stop-list-page',
  imports: [TPipe, DataTable, QCellDef],
  templateUrl: './stop-list-page.html',
  styleUrl: './stop-list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopListPage implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly columns = computed<readonly DataTableColumn[]>(() => {
    this.i18n.locale(); // re-translate on a locale switch — a plain field would not.
    return [
      { key: 'product', header: this.i18n.t('kitchen.stopList.column.product') },
      { key: 'category', header: this.i18n.t('kitchen.stopList.column.category') },
      { key: 'status', header: this.i18n.t('kitchen.stopList.column.status') },
      { key: 'toggle', header: '', hideable: false },
    ];
  });

  protected readonly bulkActions = computed<readonly BulkAction[]>(() => {
    this.i18n.locale();
    return [
      { id: 'stop', label: this.i18n.t('kitchen.stopList.action.stop') },
      { id: 'unstop', label: this.i18n.t('kitchen.stopList.action.unstop') },
    ];
  });

  protected readonly rowIdFn = (row: VariantAvailabilityResponse): string => row.variantId;

  /** `q-data-table`'s `scopeKey` — so a shared terminal's persisted filters and saved views never leak from one location into another. */
  protected readonly scopeKey = computed<string | null>(() => {
    const scope = this.location.scope();
    return scope ? `${scope.tenantId}:${scope.brandId}:${scope.locationId}` : null;
  });

  protected readonly firstLoadComplete = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly items = signal<readonly VariantAvailabilityResponse[]>([]);
  protected readonly page = signal<CursorState>(firstPage(50));
  protected readonly hasMore = signal(false);

  /** Two-way bound to `q-data-table`'s `[(filters)]` — persisted per-tab filters and saved views both round-trip through this signal. */
  protected readonly filters = signal<StopListFilters | null>({ tab: 'ALL' });
  protected readonly activeTab = computed<StopTab>(() => this.filters()?.tab ?? 'ALL');
  /** Two-way bound to `q-data-table`'s own selection model — read here to gate the reason field, written here to clear the selection once a bulk action lands. */
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly busyVariantIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkReason = signal('');
  protected readonly notice = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.load(firstPage(50));
  }

  private async load(state: CursorState, append = false): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.page<VariantAvailabilityResponse>(
          catalogPaths.variantsAtLocation(toBrandScope(scope), scope.locationId),
          state,
          { locale: this.i18n.locale() === 'uz-Latn' ? 'uz' : this.i18n.locale() },
        ),
      );
      this.items.set(append ? [...this.items(), ...result.items] : result.items);
      this.hasMore.set(result.nextCursor !== null);
      this.page.set(nextPage(state, result) ?? state);
      this.denied.set(false);
      this.lastError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
      this.loadingMore.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    this.loadingMore.set(true);
    await this.load(this.page(), true);
  }

  protected selectTab(tab: StopTab): void {
    this.filters.set({ tab });
  }

  /**
   * `null` while more pages remain unloaded — a count over `items()` alone
   * undercounts until the operator has paged to the end, and a wrong number
   * is worse than none (the trap this wave's own gap-map entry names).
   */
  protected tabCount(tab: StopTab): number | null {
    if (this.hasMore()) {
      return null;
    }
    const items = this.items();
    if (tab === 'AVAILABLE') {
      return items.filter((item) => item.available).length;
    }
    if (tab === 'ON_STOP') {
      return items.filter((item) => !item.available).length;
    }
    return items.length;
  }

  protected visibleItems(): readonly VariantAvailabilityResponse[] {
    const tab = this.activeTab();
    return this.items().filter((item) => {
      if (tab === 'AVAILABLE') {
        return item.available;
      }
      if (tab === 'ON_STOP') {
        return !item.available;
      }
      return true;
    });
  }

  protected isBusy(variantId: string): boolean {
    return this.busyVariantIds().has(variantId);
  }

  protected onBulkReasonInput(value: string): void {
    this.bulkReason.set(value);
  }

  /** One row's stop/unstop, the audited single-item toggle. */
  protected async toggleOne(item: VariantAvailabilityResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.setBusy(item.variantId, true);
    try {
      await firstValueFrom(
        this.api.put<{ available: boolean; reasonCode: string }, void>(
          operationsPaths.inventoryVariantAvailability(scope, item.variantId),
          command({ available: !item.available, reasonCode: SINGLE_TOGGLE_REASON }),
        ),
      );
      this.items.update((current) =>
        current.map((row) =>
          row.variantId === item.variantId ? { ...row, available: !row.available } : row,
        ),
      );
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(item.variantId, false);
    }
  }

  /**
   * Bulk stop/unstop — a client-side loop over the selected rows, each its
   * own audited call. `toStop` decides the target state for every selected
   * row alike, which is what "bulk" means on this screen: one shared reason,
   * applied to a set an operator picked, never a mixed-outcome guess.
   *
   * `q-data-table`'s bulk-action bar has no field of its own for a reason,
   * so the reason stays this page's own toolbar control, gated on
   * `selectedIds()` being non-empty; the action id it emits (`'stop'` /
   * `'unstop'`) is all this handler needs from the event itself.
   */
  protected async onBulkAction(event: BulkActionEvent): Promise<void> {
    const toStop = event.actionId === 'stop';
    const scope = this.location.scope();
    const reasonCode = this.bulkReason().trim();
    const ids = event.rowIds;
    if (!scope || !reasonCode || ids.length === 0) {
      return;
    }
    let failed = 0;
    for (const variantId of ids) {
      const item = this.items().find((row) => row.variantId === variantId);
      if (!item || item.available !== toStop) {
        // Already in the target state, or gone from the loaded page — skip
        // rather than send a no-op mutation.
        continue;
      }
      this.setBusy(variantId, true);
      try {
        await firstValueFrom(
          this.api.put<{ available: boolean; reasonCode: string }, void>(
            operationsPaths.inventoryVariantAvailability(scope, variantId),
            command({ available: !toStop, reasonCode }),
          ),
        );
        this.items.update((current) =>
          current.map((row) =>
            row.variantId === variantId ? { ...row, available: !toStop } : row,
          ),
        );
      } catch {
        failed++;
      } finally {
        this.setBusy(variantId, false);
      }
    }
    this.selectedIds.set(new Set());
    this.notice.set(
      failed > 0
        ? this.i18n.t('kitchen.stopList.bulk.partial', { failed })
        : this.i18n.t('kitchen.stopList.bulk.done', { count: ids.length }),
    );
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  private setBusy(variantId: string, busy: boolean): void {
    this.busyVariantIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(variantId);
      } else {
        next.delete(variantId);
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

function toBrandScope(scope: { readonly tenantId: string; readonly brandId: string }): BrandScope {
  return { tenantId: scope.tenantId, brandId: scope.brandId };
}
