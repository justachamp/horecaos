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
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../shared/ui/confirm-dialog';
import { CatalogApi, fetchAllVariantsAtLocation } from './catalog-api';
import { VariantAvailabilityRow } from './catalog-domain';
import { InventoryApi } from './inventory-api';
import { PricingApi } from './pricing-api';

/** The five ways an operator can narrow the matrix — catalog.md §4.5's status tabs. */
export type MenuStatusFilter = 'ALL' | 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN' | 'NOT_ADDED';

const FULFILLMENT_MODES = ['DELIVERY', 'PICKUP', 'DINE_IN'] as const;

/**
 * Mirrors `BulkOfferingStatusRequest`'s own `@Size(max = 200)` on the
 * server (`CatalogAuthoringController`), and `bulk-price-change-page.ts`'s
 * identical `SELECTION_CAP`. `rows()` can hold up to 4000 variants
 * (`fetchAllVariantsAtLocation`'s own paging ceiling) — well past what a
 * single bulk stop/unstop request can carry — so the selection itself, not
 * just the fetch, has to stay under this ceiling or the endpoint refuses
 * the whole request with nothing applied.
 */
const SELECTION_CAP = 200;

/**
 * catalog.md §4.5 — "Layer A" of the offering matrix, widened by this wave to
 * fix the row's own named problem: **the matrix used to show only two
 * states for one location, so a variant hidden at the offering level was
 * indistinguishable from one that was never added.** `variantsAtLocation`
 * joined `location_offerings` `AVAILABLE`-only and reported nothing about
 * `HIDDEN` or "no row at all" at all — both were simply absent rows. The
 * backend now surfaces `offeringStatus` (`AVAILABLE`/`UNAVAILABLE`/`HIDDEN`,
 * or absent for "never added") on every row regardless of status, and this
 * page renders it as its own badge (`offeringStatusLabel`/`offeringStatusClass`),
 * separate from the existing `menus__cell` toggle below.
 *
 * **Locations only: Layer B (the per-channel plane) is still not built.**
 * `catalog.channel_offering_exclusions` (`V0020`) exists with a reader and no
 * writer — see the class doc this file used to carry, corrected in
 * catalog.md, and wave P45's own scope — so this screen still shows no
 * `Каналы` toggle and says so once, in `catalog.menus.channelsNote`, rather
 * than rendering a control that would be a lie.
 *
 * **The cell toggle keeps writing through the audited inventory endpoint,
 * not `CatalogApi.setOffering`, and that is still deliberate — see the
 * regression spec.** `row.available` is `inventory.positions.binary_available`,
 * the 86 flag, answering a genuinely different question from
 * `row.offeringStatus` (menu structure): a `HIDDEN` dish can still carry an
 * available stock item nobody is offered, and a dish nobody has stopped can
 * still be missing from the menu entirely. Rendering both, distinctly, is the
 * whole point of this wave rather than collapsing them back into one boolean.
 *
 * **Filter, search, per-order-type column, and bulk stop/unstop are new.**
 * `search`/`status` are sent to `variantsAtLocation` itself (SQL-level, not a
 * client-side filter over an unbounded fetch) and reset the fetched page set
 * on every change; `bulkSetOfferingStatus` is the endpoint this screen never
 * had, gated behind `q-confirm-dialog` the same way an irreversible-feeling
 * bulk action is gated everywhere else in this console.
 */
@Component({
  selector: 'q-menus-page',
  imports: [TPipe, ConfirmDialog],
  templateUrl: './menus-page.html',
  styleUrl: './menus-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MenusPage implements OnInit {
  private readonly catalogApi = inject(CatalogApi);
  private readonly pricingApi = inject(PricingApi);
  private readonly inventoryApi = inject(InventoryApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly reloading = signal(false);
  protected readonly denied = signal(false);
  protected readonly noLocation = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly rows = signal<readonly VariantAvailabilityRow[]>([]);
  protected readonly prices = signal<Readonly<Record<string, number>>>({});
  protected readonly currency = signal<string | null>(null);
  protected readonly busyVariantIds = signal<ReadonlySet<string>>(new Set());

  protected readonly search = signal('');
  protected readonly statusFilter = signal<MenuStatusFilter>('ALL');
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());
  protected readonly bulkTarget = signal<'AVAILABLE' | 'UNAVAILABLE' | null>(null);
  protected readonly bulkBusy = signal(false);

  protected readonly fulfillmentModes = FULFILLMENT_MODES;
  protected readonly statusFilterOptions: readonly MenuStatusFilter[] = [
    'ALL',
    'AVAILABLE',
    'UNAVAILABLE',
    'HIDDEN',
    'NOT_ADDED',
  ];

  protected readonly allSelected = computed(
    () =>
      this.rows().length > 0 && this.rows().every((row) => this.selectedIds().has(row.variantId)),
  );
  protected readonly selectedCount = computed(() => this.selectedIds().size);
  /** Whether {@link SELECTION_CAP} actually bit — the visible reason the selection stopped short of "all". */
  protected readonly selectionCapped = computed(() => this.rows().length > SELECTION_CAP);
  /**
   * The header checkbox's own checked state: every row when there are
   * {@link SELECTION_CAP} or fewer, or the capped selection itself once
   * there are more — {@link allSelected} alone can never be true past the
   * cap (not every row is selected), which would otherwise leave the
   * checkbox permanently unchecked and {@link toggleSelectAll} with no way
   * to clear a capped selection except one row at a time.
   */
  protected readonly selectAllChecked = computed(
    () => this.allSelected() || (this.selectionCapped() && this.selectedCount() >= SELECTION_CAP),
  );

  private searchDebounce: ReturnType<typeof setTimeout> | null = null;

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.noLocation.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    await this.load();
    this.firstLoadComplete.set(true);
  }

  private async load(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.reloading.set(true);
    try {
      const filters = {
        search: this.search().trim() || undefined,
        status: this.statusFilter() === 'ALL' ? undefined : this.statusFilter(),
      };
      const rows = await fetchAllVariantsAtLocation(
        this.catalogApi,
        scope,
        scope.locationId,
        filters,
      );
      this.rows.set(rows);
      // A stale selection referring to a row the new filter dropped would let
      // a bulk action silently act on variants no longer even visible.
      this.selectedIds.set(new Set());

      if (rows.length > 0) {
        const resolved = await firstValueFrom(
          this.pricingApi.resolvedVariantPrices(
            scope,
            scope.locationId,
            rows.map((row) => row.variantId),
          ),
        );
        this.prices.set(resolved.amountsMinor);
        this.currency.set(resolved.currency ?? null);
      } else {
        this.prices.set({});
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
      this.reloading.set(false);
    }
  }

  // ------------------------------------------------------------ filter / search

  protected onSearchInput(value: string): void {
    this.search.set(value);
    if (this.searchDebounce !== null) {
      clearTimeout(this.searchDebounce);
    }
    this.searchDebounce = setTimeout(() => void this.load(), 300);
  }

  protected filterTabLabel(option: MenuStatusFilter): string {
    switch (option) {
      case 'ALL':
        return this.i18n.t('catalog.menus.filter.all');
      case 'AVAILABLE':
        return this.i18n.t('catalog.menus.status.AVAILABLE');
      case 'UNAVAILABLE':
        return this.i18n.t('catalog.menus.status.UNAVAILABLE');
      case 'HIDDEN':
        return this.i18n.t('catalog.menus.status.HIDDEN');
      case 'NOT_ADDED':
        return this.i18n.t('catalog.menus.status.NONE');
    }
  }

  protected setStatusFilter(status: MenuStatusFilter): void {
    if (this.statusFilter() === status) {
      return;
    }
    this.statusFilter.set(status);
    void this.load();
  }

  // ------------------------------------------------------------ price / cell toggle

  protected priceLabel(variantId: string): string {
    const amountMinor = this.prices()[variantId];
    const currency = this.currency();
    if (amountMinor === undefined || !currency) {
      return this.i18n.t('catalog.editor.variants.noPrice');
    }
    return formatMoney({ amountMinor, currency }, this.i18n.locale());
  }

  protected statusLabel(row: VariantAvailabilityRow): string {
    return row.available
      ? this.i18n.t('catalog.menus.status.AVAILABLE')
      : this.i18n.t('catalog.menus.status.UNAVAILABLE');
  }

  protected isBusy(variantId: string): boolean {
    return this.busyVariantIds().has(variantId);
  }

  /**
   * Unchanged by this wave, and deliberately so — see the class doc and the
   * regression spec ("through the audited inventory endpoint, not the
   * offering one").
   */
  protected async toggle(row: VariantAvailabilityRow): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.isBusy(row.variantId)) {
      return;
    }
    this.setBusy(row.variantId, true);
    const nextAvailable = !row.available;
    try {
      await firstValueFrom(this.inventoryApi.setAvailability(scope, row.variantId, nextAvailable));
      this.rows.set(
        this.rows().map((current) =>
          current.variantId === row.variantId ? { ...current, available: nextAvailable } : current,
        ),
      );
    } catch (error) {
      if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.setBusy(row.variantId, false);
    }
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

  // ------------------------------------------------------------ offering status / fulfilment modes

  protected offeringStatusLabel(row: VariantAvailabilityRow): string {
    switch (row.offeringStatus) {
      case 'AVAILABLE':
        return this.i18n.t('catalog.menus.status.AVAILABLE');
      case 'UNAVAILABLE':
        return this.i18n.t('catalog.menus.status.UNAVAILABLE');
      case 'HIDDEN':
        return this.i18n.t('catalog.menus.status.HIDDEN');
      default:
        return this.i18n.t('catalog.menus.status.NONE');
    }
  }

  protected offeringStatusClass(row: VariantAvailabilityRow): string {
    switch (row.offeringStatus) {
      case 'AVAILABLE':
        return 'menus__offering--available';
      case 'UNAVAILABLE':
        return 'menus__offering--unavailable';
      case 'HIDDEN':
        return 'menus__offering--hidden';
      default:
        // No location_offerings row at all — "never added", the exact state
        // this wave makes distinguishable from HIDDEN for the first time.
        return 'menus__offering--not-added';
    }
  }

  protected hasFulfillmentMode(row: VariantAvailabilityRow, mode: string): boolean {
    return (row.fulfillmentModes ?? []).includes(mode);
  }

  protected fulfillmentModeLabel(mode: string): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('catalog.menus.fulfillment.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('catalog.menus.fulfillment.PICKUP');
      case 'DINE_IN':
        return this.i18n.t('catalog.menus.fulfillment.DINE_IN');
      default:
        return mode;
    }
  }

  // ------------------------------------------------------------ bulk selection

  protected isSelected(variantId: string): boolean {
    return this.selectedIds().has(variantId);
  }

  protected toggleSelected(variantId: string): void {
    this.selectedIds.update((current) => {
      const next = new Set(current);
      if (next.has(variantId)) {
        next.delete(variantId);
      } else if (next.size < SELECTION_CAP) {
        next.add(variantId);
      }
      // At the cap already: the click is a no-op rather than a silent
      // overflow past what POST .../bulk-offering-status can carry in one
      // request (see SELECTION_CAP's own doc).
      return next;
    });
  }

  /** Selects up to {@link SELECTION_CAP} rows — see its own doc for why "all" cannot mean literally every row. */
  protected toggleSelectAll(): void {
    this.selectedIds.set(
      this.selectAllChecked()
        ? new Set()
        : new Set(this.rows().slice(0, SELECTION_CAP).map((row) => row.variantId)),
    );
  }

  protected requestBulk(status: 'AVAILABLE' | 'UNAVAILABLE'): void {
    if (this.selectedCount() === 0) {
      return;
    }
    this.bulkTarget.set(status);
  }

  protected cancelBulk(): void {
    this.bulkTarget.set(null);
  }

  protected async confirmBulk(): Promise<void> {
    const scope = this.location.scope();
    const status = this.bulkTarget();
    if (!scope || !status) {
      return;
    }
    this.bulkBusy.set(true);
    try {
      await firstValueFrom(
        this.catalogApi.bulkSetOfferingStatus(scope, scope.locationId, {
          variantIds: [...this.selectedIds()],
          status,
        }),
      );
      this.bulkTarget.set(null);
      await this.load();
    } catch (error) {
      if (error instanceof ApiError) {
        this.lastError.set(error);
      } else {
        throw error;
      }
    } finally {
      this.bulkBusy.set(false);
    }
  }
}
