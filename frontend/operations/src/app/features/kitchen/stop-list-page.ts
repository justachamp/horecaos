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
import { TimeZone, formatDateTime } from '../../core/format/datetime';
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

/** How long a keystroke in the search box waits before it becomes a server request — matches `products-page.ts`'s own debounce. */
const SEARCH_DEBOUNCE_MS = 300;

/** See `kitchen-queue-page.ts`'s identical constant — no location carries a timezone on this response yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/** `InventoryBulkAvailabilityService.MAX_ITEMS`, mirrored so the page can refuse an oversized selection before the round trip. */
const BULK_AVAILABILITY_MAX_ITEMS = 200;

/**
 * The bulk reason's own enumerated codes — `catalog.md`'s four-chip picker
 * (`Закончилось` · `Нет продукта` · `Оборудование` · `Другое`), matching
 * `InventoryController.BulkAvailabilityRequest.reasonCode`'s `@Pattern` and
 * `CatalogAuthoringController`'s own channel-exclusion `reasonCode`
 * convention: a short enumerated code an operator picks, never free text
 * they typed, per ADR 0029 — it lands verbatim in the ADR 0027 audit trail
 * and is echoed back on every read of this variant's stop reason.
 */
const BULK_REASON_CODES = ['OUT_OF_STOCK', 'NO_PRODUCT', 'EQUIPMENT', 'OTHER'] as const;

/**
 * `q-data-table`'s `[(filters)]` model, so Save View / Apply View
 * (`X.18`) has a real effect on this screen: the active tab and the search
 * box are what this page's own filter bar controls.
 */
interface StopListFilters {
  readonly tab: StopTab;
  readonly search: string;
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

/**
 * Mirrors `CatalogAuthoringController.VariantAvailabilityResponse`.
 * `stopSource`/`stopReasonCode`/`stopChangedAt` are wave P16's own addition
 * — gap map row 2.5b's explainer.
 */
interface VariantAvailabilityResponse {
  readonly variantId: string;
  readonly productName: string;
  readonly category?: string | null;
  readonly available: boolean;
  readonly trackingMode?: string | null;
  /** `MANUAL` | `POS` | `UNKNOWN`. */
  readonly stopSource: string;
  readonly stopReasonCode?: string | null;
  readonly stopChangedAt?: string | null;
}

/** Mirrors `CatalogAuthoringController.VariantAvailabilityCountsResponse` — the tab badges, exact over the whole catalog. */
interface VariantAvailabilityCountsResponse {
  readonly total: number;
  readonly available: number;
  readonly onStop: number;
}

/** Mirrors `InventoryController.BulkAvailabilityOutcome`. */
interface BulkAvailabilityOutcome {
  readonly variantId: string;
  readonly status: string;
  readonly changed: boolean;
  readonly problemCode?: string | null;
}

/** Mirrors `InventoryController.BulkAvailabilityResponse`. */
interface BulkAvailabilityResponse {
  readonly requestedCount: number;
  readonly appliedCount: number;
  readonly failedCount: number;
  readonly items: readonly BulkAvailabilityOutcome[];
}

/**
 * Stop list — IA 2.5, `docs/operations-spec/orders.md` §5.5's "стоп" chip.
 *
 * **Built.** Available/on-stop/all tabs and the read side reuse catalog.md
 * §4.6's own screen — `catalogPaths.variantsAtLocation`
 * (`CatalogAuthoringController`, control-plane surface, same cross-surface
 * situation `catalog-paths.ts` already documents for the rest of Catalog).
 * The single-row toggle is the audited `PUT .../inventory/variants/{id}/availability`
 * (`InventoryController`).
 *
 * **Wave P16 closed three gaps.** Bulk stop/unstop is now one round trip —
 * `POST .../inventory/variants/bulk-availability`, modelled on ADR 0039's
 * bulk contract with a per-item outcome — replacing the sequential loop of
 * one audited `PUT` per row that made a 300-item stop three hundred round
 * trips. The tab badges and the `ON_STOP` tab are exact over the whole
 * catalog (`GET .../variants/availability-counts`), not only the page
 * already loaded. And every row now carries a structured stop source
 * (`stopSource`/`stopReasonCode`/`stopChangedAt`), so an operator can tell a
 * kitchen stop from a POS push instead of only a boolean and a free-text
 * reason.
 *
 * **Not built, honestly**: stop scope beyond LOCATION (menu/terminal/brand
 * fan-out — `INVENTORY_ADJUST`/`INVENTORY_AVAILABILITY_MANAGE` are
 * LOCATION-scoped only, and a stop set here never reaches an aggregator
 * channel); the digest-cadence settings screen (the digest itself is built —
 * see `InventoryStopDigestSweeper` — but no console screen lets a manager
 * choose the chat or the cadence).
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
      { key: 'source', header: this.i18n.t('kitchen.stopList.column.source') },
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

  protected readonly bulkReasonCodes = BULK_REASON_CODES;

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
  protected readonly filters = signal<StopListFilters | null>({ tab: 'ALL', search: '' });
  protected readonly activeTab = computed<StopTab>(() => this.filters()?.tab ?? 'ALL');
  private readonly search = computed<string>(() => this.filters()?.search ?? '');
  /** The search box's own displayed value — updated on every keystroke, independent of the debounced `filters.search`, so typing never stutters. */
  protected readonly searchInputValue = signal('');
  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

  /** The stop list's own exact tab badges (wave P16) — `null` before the first load settles, or when the read failed (no wrong number). */
  protected readonly counts = signal<VariantAvailabilityCountsResponse | null>(null);

  /** Two-way bound to `q-data-table`'s own selection model — read here to gate the reason field, written here to clear the selection once a bulk action lands. */
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly busyVariantIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkReason = signal('');
  protected readonly notice = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.load(firstPage(50));
    void this.loadCounts();
  }

  private async load(state: CursorState, append = false): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const search = this.search().trim();
      const result = await firstValueFrom(
        this.api.page<VariantAvailabilityResponse>(
          catalogPaths.variantsAtLocation(toBrandScope(scope), scope.locationId),
          state,
          {
            locale: this.i18n.locale() === 'uz-Latn' ? 'uz' : this.i18n.locale(),
            search: search === '' ? undefined : search,
          },
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

  /**
   * The tab badges (gap map row 2.5) — one server-side aggregate, exact over
   * the whole catalog rather than the page {@link items} has loaded, and
   * following the same search term {@link load} does so the badges track a
   * typed search rather than the unfiltered catalog.
   */
  private async loadCounts(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const search = this.search().trim();
      const result = await firstValueFrom(
        this.api.get<VariantAvailabilityCountsResponse>(
          catalogPaths.variantAvailabilityCounts(toBrandScope(scope), scope.locationId),
          {
            params: {
              locale: this.i18n.locale() === 'uz-Latn' ? 'uz' : this.i18n.locale(),
              search: search === '' ? undefined : search,
            },
          },
        ),
      );
      this.counts.set(result.value);
    } catch {
      // The badges simply render "…" — see tabCount's own null contract.
      this.counts.set(null);
    }
  }

  protected async loadMore(): Promise<void> {
    this.loadingMore.set(true);
    await this.load(this.page(), true);
  }

  /** Immediate — a tab is a click, not a keystroke, and needs no debounce. The row list itself is already loaded; only the visible slice changes. */
  protected selectTab(tab: StopTab): void {
    this.filters.set({ tab, search: this.search() });
  }

  /**
   * Updates the box instantly so typing never waits on the network to feel
   * responsive, and re-fetches the row list and the tab badges only once
   * the debounce elapses with no further keystroke.
   */
  protected onSearchInput(value: string): void {
    this.searchInputValue.set(value);
    if (this.searchDebounceHandle !== null) {
      clearTimeout(this.searchDebounceHandle);
    }
    this.searchDebounceHandle = setTimeout(() => {
      this.searchDebounceHandle = null;
      this.filters.set({ tab: this.activeTab(), search: value });
      void this.load(firstPage(50));
      void this.loadCounts();
    }, SEARCH_DEBOUNCE_MS);
  }

  /**
   * The stop list's own tab badge (gap map row 2.5) — a server-side
   * aggregate over the whole catalog, not a count over `items()` that
   * undercounts until every page is loaded (the trap this row's own gap-map
   * entry names). `null` renders as "…" rather than a wrong number.
   *
   * <p>{@link visibleItems} only ever filters the client-side page {@link
   * items} has loaded so far — it has no server-side "on stop"/"available"
   * fetch of its own. So while {@link hasMore} is still true, the AVAILABLE
   * and ON_STOP badges would otherwise show an exact whole-catalog number
   * next to a visible list that is only whatever slice of that subset
   * happened to land in the pages loaded so far — the same "a real number
   * next to a list it can't back up" trap this row's badge exists to avoid
   * for the ALL tab. Those two badges stay "…" until the whole catalog is
   * loaded and the visible list can actually back the number up; ALL's own
   * badge is exempt because {@link visibleItems} for that tab is always
   * exactly the page loaded so far — the same, expected "N of the total
   * loaded" pagination story every list on this console tells.
   */
  protected tabCount(tab: StopTab): number | null {
    const counts = this.counts();
    if (!counts) {
      return null;
    }
    if (tab !== 'ALL' && this.hasMore()) {
      return null;
    }
    switch (tab) {
      case 'AVAILABLE':
        return counts.available;
      case 'ON_STOP':
        return counts.onStop;
      case 'ALL':
        return counts.total;
    }
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

  /**
   * One literal `t` key per code, not a concatenated `'kitchen.stopList.bulk.reason.' +
   * code` — `TPipe`'s `MessageKey` parameter type is what makes a typo in a
   * template a build error, and only a literal key participates in that check.
   */
  protected bulkReasonLabel(code: (typeof BULK_REASON_CODES)[number]): string {
    switch (code) {
      case 'OUT_OF_STOCK':
        return this.i18n.t('kitchen.stopList.bulk.reason.outOfStock');
      case 'NO_PRODUCT':
        return this.i18n.t('kitchen.stopList.bulk.reason.noProduct');
      case 'EQUIPMENT':
        return this.i18n.t('kitchen.stopList.bulk.reason.equipment');
      case 'OTHER':
        return this.i18n.t('kitchen.stopList.bulk.reason.other');
    }
  }

  // -------------------------------------------------------- P16: stop source

  /**
   * The explainer's own label (gap map row 2.5b) — only rendered for a row
   * currently on stop, since "why can't I sell this?" is the question the
   * row makes its centrepiece and an available dish has no such question to
   * answer.
   */
  protected stopSourceLabel(item: VariantAvailabilityResponse): string | null {
    if (item.available) {
      return null;
    }
    switch (item.stopSource) {
      case 'MANUAL':
        return this.i18n.t('kitchen.stopList.source.manual');
      case 'POS':
        return this.i18n.t('kitchen.stopList.source.pos');
      default:
        return this.i18n.t('kitchen.stopList.source.unknown');
    }
  }

  /** The reveal-on-hover detail: the raw reason code and when it happened — never shown as the primary label, only as a title attribute. */
  protected stopSourceDetail(item: VariantAvailabilityResponse): string | null {
    if (item.available || !item.stopChangedAt) {
      return null;
    }
    const when = formatDateTime(new Date(item.stopChangedAt), PLACEHOLDER_TIME_ZONE);
    return item.stopReasonCode
      ? this.i18n.t('kitchen.stopList.source.detail', { reason: item.stopReasonCode, when })
      : when;
  }

  // ------------------------------------------------------------ mutations

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
          row.variantId === item.variantId
            ? {
                ...row,
                available: !row.available,
                stopSource: 'MANUAL',
                stopReasonCode: SINGLE_TOGGLE_REASON,
              }
            : row,
        ),
      );
      void this.loadCounts();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.setBusy(item.variantId, false);
    }
  }

  /**
   * Bulk stop/unstop — one round trip to the batch endpoint (wave P16,
   * gap map row 2.5), replacing the sequential loop of one audited `PUT`
   * per row this page used to run (a 300-item stop used to be three
   * hundred round trips with a partial-failure count as the only report).
   * `toStop` decides the target state for every selected row alike, which
   * is what "bulk" means on this screen: one shared reason, applied to a
   * set an operator picked.
   *
   * No client-side "already in the target state" pre-filter is needed
   * here, unlike the old loop: `InventoryBulkAvailabilityService` already
   * reports such a row `APPLIED` with `changed: false` rather than erroring,
   * so sending every selected id — including one gone from the loaded page
   * — is both simpler and more correct than guessing locally.
   */
  protected async onBulkAction(event: BulkActionEvent): Promise<void> {
    const toStop = event.actionId === 'stop';
    const scope = this.location.scope();
    const reasonCode = this.bulkReason().trim();
    const ids = [...event.rowIds];
    if (!scope || !reasonCode || ids.length === 0) {
      return;
    }
    if (ids.length > BULK_AVAILABILITY_MAX_ITEMS) {
      this.notice.set(
        this.i18n.t('kitchen.stopList.bulk.tooMany', { max: BULK_AVAILABILITY_MAX_ITEMS }),
      );
      return;
    }
    this.busyVariantIds.update((current) => new Set([...current, ...ids]));
    try {
      const result = await firstValueFrom(
        this.api.post<
          { variantIds: string[]; available: boolean; reasonCode: string },
          BulkAvailabilityResponse
        >(
          operationsPaths.inventoryBulkAvailability(scope),
          command({ variantIds: ids, available: !toStop, reasonCode }),
        ),
      );
      const appliedIds = new Set(
        result.items
          .filter((outcome) => outcome.status === 'APPLIED')
          .map((outcome) => outcome.variantId),
      );
      this.items.update((current) =>
        current.map((row) =>
          appliedIds.has(row.variantId)
            ? { ...row, available: !toStop, stopSource: 'MANUAL', stopReasonCode: reasonCode }
            : row,
        ),
      );
      this.notice.set(
        result.failedCount > 0
          ? this.i18n.t('kitchen.stopList.bulk.partial', { failed: result.failedCount })
          : this.i18n.t('kitchen.stopList.bulk.done', { count: result.appliedCount }),
      );
      void this.loadCounts();
    } catch (error) {
      this.notice.set(this.describe(error));
    } finally {
      this.busyVariantIds.update((current) => {
        const next = new Set(current);
        for (const id of ids) {
          next.delete(id);
        }
        return next;
      });
      this.selectedIds.set(new Set());
    }
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
