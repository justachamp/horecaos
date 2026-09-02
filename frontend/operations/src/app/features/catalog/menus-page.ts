import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CatalogApi } from './catalog-api';
import { VariantAvailabilityRow } from './catalog-domain';
import { InventoryApi } from './inventory-api';
import { PricingApi } from './pricing-api';

/**
 * catalog.md §4.5 — "Layer A" of the offering matrix, the layer the spec
 * itself says is "buildable now". Locations only: **Layer B (the per-channel
 * plane) is not built**, and the spec's own words justify staying out of it
 * — `offered_on_channel` has no table (`V0020` did not build it, ADR 0036) —
 * so this screen shows a `Каналы` toggle nowhere and says so once, in
 * `catalog.menus.channelsNote`, rather than rendering a control that would
 * always be a lie.
 *
 * **Two further simplifications against the spec, named honestly.** The only
 * read this wave's backend has for "what does a location sell" is
 * `CatalogApi.variantsAtLocation` (`CatalogAuthoringController`'s `GET
 * .../locations/{locationId}/variants`), and its own SQL joins on
 * `location_offerings.status = 'AVAILABLE'` and reports `pos.binary_available`
 * (`inventory.positions`) as `available` — so a row here is always something
 * the menu *offers*, and the boolean is the **inventory 86 flag**, not
 * catalog.md's three-state `location_offerings.status`. The cell toggle
 * therefore writes through `InventoryApi.setAvailability` (the audited
 * `PUT .../inventory/variants/{variantId}/availability`, waves 6/24's
 * `setAvailabilityAudited`) rather than `CatalogApi.setOffering` — writing the
 * *other* field would silently desync the toggle from what this read shows,
 * since `setOffering` cannot flip `binary_available` and a stopped row would
 * simply vanish from the next real fetch (the join excludes anything not
 * `AVAILABLE`) while the optimistic UI still showed it stopped. So this
 * matrix renders two states, not three: HIDDEN and UNAVAILABLE-at-the-offering-
 * level are indistinguishable here and both simply do not appear as rows —
 * catalog.md's own three-state cell is `4.2`'s Product editor and the not-yet-
 * built `4.6` stop list to own, not this screen with the read this wave has.
 * Second, there is no bulk stop/unstop endpoint (confirmed absent from
 * `InventoryController`/`CatalogAuthoringController`), so bulk actions are
 * omitted entirely rather than looping N individual calls behind one button
 * that pretends to be atomic.
 */
@Component({
  selector: 'q-menus-page',
  imports: [TPipe],
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
  protected readonly denied = signal(false);
  protected readonly noLocation = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly rows = signal<readonly VariantAvailabilityRow[]>([]);
  protected readonly prices = signal<Readonly<Record<string, number>>>({});
  protected readonly currency = signal<string | null>(null);
  protected readonly busyVariantIds = signal<ReadonlySet<string>>(new Set());

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.noLocation.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    try {
      const rows = await firstValueFrom(
        this.catalogApi.variantsAtLocation(scope, scope.locationId),
      );
      this.rows.set(rows);

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
}
