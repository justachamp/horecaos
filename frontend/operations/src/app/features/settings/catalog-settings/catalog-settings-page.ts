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
 * **`catalog.use_stock_logic` is now a real, editable field (batch 11, gap
 * map row 4.4c/4.4d).** `QUANTITY` tracking is enforced by `InventoryService
 * .evaluateAvailability` exactly when this flag is on for the tenant — off,
 * a `QUANTITY`-listed item behaves like `UNTRACKED` — so the earlier
 * read-only "not yet enforced" placeholder (wave P46's own trap against a
 * dead button) no longer applies; the switch does something real the moment
 * it is turned on. Same edit/save/revert shape as `catalog.qr_kiosk_price_plane`
 * below.
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

  protected readonly editingUseStockLogic = signal(false);
  protected readonly draftUseStockLogic = signal(false);
  protected readonly draftUseStockLogicReason = signal('');
  protected readonly savingUseStockLogic = signal(false);
  protected readonly saveUseStockLogicError = signal<string | null>(null);

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

  protected startEditingUseStockLogic(): void {
    const current = this.useStockLogic();
    this.draftUseStockLogic.set(Boolean(current?.value));
    this.draftUseStockLogicReason.set('');
    this.saveUseStockLogicError.set(null);
    this.editingUseStockLogic.set(true);
  }

  protected cancelEditingUseStockLogic(): void {
    this.editingUseStockLogic.set(false);
  }

  protected canSaveUseStockLogic(): boolean {
    return !this.savingUseStockLogic() && this.draftUseStockLogicReason().trim().length > 0;
  }

  protected async saveUseStockLogic(): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId || !this.canSaveUseStockLogic()) {
      return;
    }
    this.savingUseStockLogic.set(true);
    this.saveUseStockLogicError.set(null);
    try {
      await this.api.setValue(tenantId, USE_STOCK_LOGIC_CODE, {
        scopeType: 'TENANT',
        explicitNull: false,
        booleanValue: this.draftUseStockLogic(),
        expectedVersion: this.useStockLogic()?.currentVersionAtScope ?? null,
        reason: this.draftUseStockLogicReason().trim(),
      });
      await this.reloadUseStockLogic(tenantId);
      this.editingUseStockLogic.set(false);
    } catch (error) {
      this.saveUseStockLogicError.set(this.describe(error));
    } finally {
      this.savingUseStockLogic.set(false);
    }
  }

  /** Hands the tenant-wide override back to the platform default (off) — no separate reason prompt. */
  protected async revertUseStockLogic(): Promise<void> {
    const tenantId = this.tenantId;
    if (!tenantId) {
      return;
    }
    this.savingUseStockLogic.set(true);
    this.saveUseStockLogicError.set(null);
    try {
      await this.api.setValue(tenantId, USE_STOCK_LOGIC_CODE, {
        scopeType: 'TENANT',
        explicitNull: true,
        expectedVersion: this.useStockLogic()?.currentVersionAtScope ?? null,
        reason: this.i18n.t('settings.catalog.revertReason'),
      });
      await this.reloadUseStockLogic(tenantId);
    } catch (error) {
      this.saveUseStockLogicError.set(this.describe(error));
    } finally {
      this.savingUseStockLogic.set(false);
    }
  }

  private async reloadUseStockLogic(tenantId: string): Promise<void> {
    this.useStockLogic.set(await this.api.resolution(tenantId, USE_STOCK_LOGIC_CODE, 'TENANT'));
  }

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
    this.qrKioskPricePlane.set(
      await this.api.resolution(tenantId, QR_KIOSK_PRICE_PLANE_CODE, 'TENANT'),
    );
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
