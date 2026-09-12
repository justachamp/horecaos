import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { InheritedField } from '../../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../../orders/order-errors';
import { ConfigurationApi, ConfigurationResolutionView } from '../configuration-api';

const USE_STOCK_LOGIC_CODE = 'catalog.use_stock_logic';
const QR_KIOSK_PRICE_PLANE_CODE = 'catalog.qr_kiosk_price_plane';

/**
 * 4.4d Catalog base settings — tenant-wide switches, not a catalog-row
 * concern (wave P46, gap map row `4.4d`). Both keys are `TENANT`-only
 * (`InventoryConfigurationKeys.CATALOG_USE_STOCK_LOGIC` and its sibling), so
 * this page ignores the shell's brand/location scope bar entirely and always
 * reads and writes at `TENANT` — the same reason `feature.*` flags are
 * platform/tenant-only, and unlike every field on `order-policy-page`, which
 * follows the scope bar because settings.md §10.3 asks it to.
 *
 * **`catalog.use_stock_logic` renders read-only, with the reason.** `QUANTITY`
 * tracking is not implemented (`InventoryService.UnsupportedTrackingModeException`
 * refuses it regardless of this flag — see that class's own doc) and the
 * wave's own trap names the alternative directly: "registering a key whose
 * enforcement does not exist is worse than no key... if it cannot be
 * honoured yet, ship it disabled with the reason." An editable `Изменить`
 * button here would be exactly the P30 "dead button that looks actionable"
 * anti-pattern the gap map calls out elsewhere, so this field is a plain
 * read-out rather than a `q-inherited-field` wired to a form nothing backs.
 *
 * **`catalog.qr_kiosk_price_plane` is a real, editable field.** Nothing about
 * it contradicts today's behaviour the way `use_stock_logic` does — a tenant
 * may still point a QR/kiosk channel at the hall's price plane by hand
 * through Sales channels (`SalesChannel.pricePlaneChannelId`) regardless of
 * this switch, so authoring the intent ahead of the day something reads it
 * automatically is honest, not a promise the platform cannot keep.
 */
@Component({
  selector: 'q-catalog-settings-page',
  imports: [TPipe, InheritedField],
  templateUrl: './catalog-settings-page.html',
  styleUrl: './catalog-settings-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CatalogSettingsPage {
  private readonly api = inject(ConfigurationApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly useStockLogic = signal<ConfigurationResolutionView | null>(null);
  protected readonly qrKioskPricePlane = signal<ConfigurationResolutionView | null>(null);

  protected readonly editingQrKiosk = signal(false);
  protected readonly draftQrKiosk = signal(false);
  protected readonly draftReason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  /** Matches the backend's `settableScopes` for both keys — see `InventoryConfigurationKeys`. */
  protected readonly tenantOnlyScopes = ['PLATFORM', 'TENANT'] as const;

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected yesNo(value: unknown): string {
    return value ? this.i18n.t('settings.catalog.yes') : this.i18n.t('settings.catalog.no');
  }

  /** Bound once so `[formatValue]` gets a stable reference rather than a new closure every change-detection pass. */
  protected readonly formatYesNo = (value: unknown): string => this.yesNo(value);

  protected startEditingQrKiosk(): void {
    const current = this.qrKioskPricePlane();
    this.draftQrKiosk.set(Boolean(current?.value));
    this.draftReason.set('');
    this.saveError.set(null);
    this.editingQrKiosk.set(true);
  }

  protected cancelEditingQrKiosk(): void {
    this.editingQrKiosk.set(false);
  }

  protected canSaveQrKiosk(): boolean {
    return !this.saving() && this.draftReason().trim().length > 0;
  }

  protected async saveQrKiosk(): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId || !this.canSaveQrKiosk()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      await this.api.setValue(tenantId, QR_KIOSK_PRICE_PLANE_CODE, {
        scopeType: 'TENANT',
        explicitNull: false,
        booleanValue: this.draftQrKiosk(),
        expectedVersion: this.qrKioskPricePlane()?.currentVersionAtScope ?? null,
        reason: this.draftReason().trim(),
      });
      await this.reloadQrKiosk(tenantId);
      this.editingQrKiosk.set(false);
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  /** Hands the tenant-wide override back to the platform default — no separate reason prompt, matching a quick revert. */
  protected async revertQrKiosk(): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      await this.api.setValue(tenantId, QR_KIOSK_PRICE_PLANE_CODE, {
        scopeType: 'TENANT',
        explicitNull: true,
        expectedVersion: this.qrKioskPricePlane()?.currentVersionAtScope ?? null,
        reason: this.i18n.t('settings.catalog.revertReason'),
      });
      await this.reloadQrKiosk(tenantId);
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private async reloadQrKiosk(tenantId: string): Promise<void> {
    this.qrKioskPricePlane.set(await this.api.resolution(tenantId, QR_KIOSK_PRICE_PLANE_CODE, 'TENANT'));
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.denied.set(this.tenant.denied());
      this.loading.set(false);
      return;
    }
    this.tenantId = tenantId;
    try {
      const [useStockLogic, qrKioskPricePlane] = await Promise.all([
        this.api.resolution(tenantId, USE_STOCK_LOGIC_CODE, 'TENANT'),
        this.api.resolution(tenantId, QR_KIOSK_PRICE_PLANE_CODE, 'TENANT'),
      ]);
      this.useStockLogic.set(useStockLogic);
      this.qrKioskPricePlane.set(qrKioskPricePlane);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
