import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { ApiError } from '../../core/api/problem-details';
import { ChannelSystemType, InventoryApi, StockPosition } from './inventory-api';

/** `tenant.sales_channels.system_type`'s closed set — the threshold form's own options. */
const CHANNEL_TYPES: readonly ChannelSystemType[] = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
];

/**
 * The console's per-location stock page (gap map row 4.4c, batch 11):
 * quantities, set on-hand, the daily default, and per-channel-type stop
 * thresholds for every `QUANTITY`-tracked item at this location.
 *
 * **Scoped to `QUANTITY` items alone.** A BINARY item's on/off toggle
 * already has a real home (`menus-page.ts`'s cell toggle, the stop list, and
 * the product editor's Availability tab) — this screen exists for the
 * columns only a QUANTITY item carries (on-hand, reserved, remaining, the
 * daily default, and channel thresholds), which none of those screens have
 * anywhere to show. `InventoryApi.listPositions` returns every tracking
 * mode; this page filters to `QUANTITY` client-side rather than asking the
 * backend for a second, narrower endpoint.
 *
 * **The daily reset's own time is not authored here.** It fires at the
 * tenant's business-day boundary (ADR 0043) — a single, tenant-wide setting
 * already editable at Settings → Reference data, not a per-location or
 * per-item time this page would duplicate. This page only shows each item's
 * own default and the business date it was last reset on, and links out to
 * that setting rather than re-implementing it.
 */
@Component({
  selector: 'q-stock-page',
  imports: [TPipe],
  templateUrl: './stock-page.html',
  styleUrl: './stock-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StockPage implements OnInit {
  private readonly inventoryApi = inject(InventoryApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly channelTypes = CHANNEL_TYPES;

  protected readonly firstLoadComplete = signal(false);
  protected readonly noLocation = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);

  protected readonly positions = signal<readonly StockPosition[]>([]);

  protected readonly editingVariantId = signal<string | null>(null);
  protected readonly draftOnHand = signal('');
  protected readonly draftOnHandReason = signal('');
  protected readonly savingOnHand = signal(false);
  protected readonly onHandError = signal<string | null>(null);

  protected readonly draftDefault = signal('');
  protected readonly draftDefaultReason = signal('');
  protected readonly savingDefault = signal(false);
  protected readonly defaultError = signal<string | null>(null);

  protected readonly draftThresholdChannel = signal<ChannelSystemType>('AGGREGATOR');
  protected readonly draftThresholdValue = signal('');
  protected readonly draftThresholdReason = signal('');
  protected readonly savingThreshold = signal(false);
  protected readonly thresholdError = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  protected quantityRows(): readonly StockPosition[] {
    return this.positions().filter((row) => row.trackingMode === 'QUANTITY');
  }

  protected startEdit(row: StockPosition): void {
    this.editingVariantId.set(row.variantId);
    this.draftOnHand.set(String(row.onHandQuantity));
    this.draftOnHandReason.set('');
    this.onHandError.set(null);
    this.draftDefault.set(row.defaultQuantity === null ? '' : String(row.defaultQuantity));
    this.draftDefaultReason.set('');
    this.defaultError.set(null);
    this.draftThresholdChannel.set('AGGREGATOR');
    this.draftThresholdValue.set('');
    this.draftThresholdReason.set('');
    this.thresholdError.set(null);
  }

  protected closeEdit(): void {
    this.editingVariantId.set(null);
  }

  protected canSaveOnHand(): boolean {
    const quantity = Number(this.draftOnHand());
    return (
      !this.savingOnHand() &&
      this.draftOnHand().trim().length > 0 &&
      Number.isFinite(quantity) &&
      quantity >= 0 &&
      this.draftOnHandReason().trim().length > 0
    );
  }

  protected async saveOnHand(row: StockPosition): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canSaveOnHand()) {
      return;
    }
    this.savingOnHand.set(true);
    this.onHandError.set(null);
    try {
      await firstValueFrom(
        this.inventoryApi.setOnHand(
          scope,
          row.variantId,
          Number(this.draftOnHand()),
          this.draftOnHandReason().trim(),
        ),
      );
      await this.load();
      this.startEdit(this.positions().find((p) => p.variantId === row.variantId) ?? row);
    } catch (error) {
      this.onHandError.set(this.describe(error));
    } finally {
      this.savingOnHand.set(false);
    }
  }

  protected canSaveDefault(): boolean {
    if (this.savingDefault() || this.draftDefaultReason().trim().length === 0) {
      return false;
    }
    if (this.draftDefault().trim().length === 0) {
      return true; // clearing the default is a valid save
    }
    const quantity = Number(this.draftDefault());
    return Number.isFinite(quantity) && quantity >= 0;
  }

  protected async saveDefault(row: StockPosition): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canSaveDefault()) {
      return;
    }
    this.savingDefault.set(true);
    this.defaultError.set(null);
    try {
      const raw = this.draftDefault().trim();
      await firstValueFrom(
        this.inventoryApi.setQuantityDefault(
          scope,
          row.variantId,
          raw.length === 0 ? null : Number(raw),
          this.draftDefaultReason().trim(),
        ),
      );
      await this.load();
      this.startEdit(this.positions().find((p) => p.variantId === row.variantId) ?? row);
    } catch (error) {
      this.defaultError.set(this.describe(error));
    } finally {
      this.savingDefault.set(false);
    }
  }

  protected canSaveThreshold(): boolean {
    const quantity = Number(this.draftThresholdValue());
    return (
      !this.savingThreshold() &&
      this.draftThresholdValue().trim().length > 0 &&
      Number.isFinite(quantity) &&
      quantity >= 0 &&
      this.draftThresholdReason().trim().length > 0
    );
  }

  protected async addThreshold(row: StockPosition): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canSaveThreshold()) {
      return;
    }
    this.savingThreshold.set(true);
    this.thresholdError.set(null);
    try {
      await firstValueFrom(
        this.inventoryApi.setChannelStopThreshold(
          scope,
          row.variantId,
          this.draftThresholdChannel(),
          Number(this.draftThresholdValue()),
          this.draftThresholdReason().trim(),
        ),
      );
      this.draftThresholdValue.set('');
      this.draftThresholdReason.set('');
      await this.load();
      this.startEdit(this.positions().find((p) => p.variantId === row.variantId) ?? row);
    } catch (error) {
      this.thresholdError.set(this.describe(error));
    } finally {
      this.savingThreshold.set(false);
    }
  }

  protected async removeThreshold(row: StockPosition, channelType: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.savingThreshold.set(true);
    this.thresholdError.set(null);
    try {
      await firstValueFrom(
        this.inventoryApi.clearChannelStopThreshold(
          scope,
          row.variantId,
          channelType as ChannelSystemType,
          'NO_LONGER_NEEDED',
        ),
      );
      await this.load();
      this.startEdit(this.positions().find((p) => p.variantId === row.variantId) ?? row);
    } catch (error) {
      this.thresholdError.set(this.describe(error));
    } finally {
      this.savingThreshold.set(false);
    }
  }

  private async load(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.noLocation.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const rows = await firstValueFrom(this.inventoryApi.listPositions(scope));
      this.positions.set(rows);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.lastError.set(this.describe(error));
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
