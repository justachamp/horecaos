import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { BrandView, LocationView, TenantsApi } from '../tenants/tenants-api';
import { BindingView, PlatformInstallationView, ProvidersApi } from './providers-api';

/**
 * IA 3.3 Installations explorer -- every `(tenant, provider, branch)`
 * installation at platform scope.
 *
 * No error-rate column: nothing in the schema records one. `lastConnectionStatus`
 * and `lastSecretRotatedAt` are the closest real signals, and this screen
 * shows those rather than a fabricated rate.
 *
 * Manage opens one installation for incident work: check its connection
 * with the restaurant's own credential, see which brands and locations it
 * serves, and suspend or reactivate one of those with a reason. Connecting a
 * provider in the first place is the merchant's own, in the operations app.
 */
@Component({
  selector: 'app-installations-explorer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './installations-explorer.html',
  styleUrl: './installations-explorer.css',
})
export class InstallationsExplorer {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(ProvidersApi);
  private readonly tenantsApi = inject(TenantsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly installations = signal<readonly PlatformInstallationView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  protected readonly openId = signal<string | null>(null);
  protected readonly bindings = signal<readonly BindingView[]>([]);
  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly manageError = signal<string | null>(null);
  protected readonly checking = signal(false);
  protected readonly checkResult = signal<string | null>(null);
  protected readonly changingBinding = signal<{ id: string; action: 'suspend' | 'activate' } | null>(null);
  protected readonly bindingReason = signal('');
  protected readonly bindingMessage = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.api.listInstallations();
      this.installations.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.listInstallations(cursor);
      this.installations.update((existing) => [...existing, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async toggle(installation: PlatformInstallationView): Promise<void> {
    if (this.openId() === installation.id) {
      this.openId.set(null);
      return;
    }
    this.openId.set(installation.id);
    this.bindings.set([]);
    this.manageError.set(null);
    this.checkResult.set(null);
    this.bindingMessage.set(null);
    this.changingBinding.set(null);
    try {
      const [bindings, brands] = await Promise.all([
        this.api.bindings(installation.tenantId, installation.id),
        this.tenantsApi.getBrands(installation.tenantId),
      ]);
      this.bindings.set(bindings);
      this.brands.set(brands);
      const perBrand = await Promise.all(brands.map((b) => this.tenantsApi.getLocations(installation.tenantId, b.id)));
      this.locations.set(perBrand.flat());
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    }
  }

  /** A binding's place by name: the brand, and the location when it names one. */
  protected placeOf(binding: BindingView): string {
    const brand = this.brands().find((b) => b.id === binding.brandId)?.displayName ?? binding.brandId ?? '';
    if (binding.locationId === null) {
      return `${brand} · ${this.i18n.t('installationsExplorer.bindings.wholeBrand')}`;
    }
    const location = this.locations().find((l) => l.id === binding.locationId)?.displayName ?? binding.locationId;
    return `${brand} › ${location}`;
  }

  protected async checkConnection(installation: PlatformInstallationView): Promise<void> {
    this.checking.set(true);
    this.manageError.set(null);
    this.checkResult.set(null);
    try {
      const result = await this.api.checkConnection(installation.tenantId, installation);
      const status = result.connectionStatus ?? 'SUCCEEDED';
      this.checkResult.set(this.i18n.t('installationsExplorer.check.result', { status }));
      this.installations.update((rows) =>
        rows.map((row) => (row.id === installation.id ? { ...row, lastConnectionStatus: status } : row)),
      );
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.checking.set(false);
    }
  }

  protected askBindingChange(binding: BindingView): void {
    this.changingBinding.set({ id: binding.id, action: binding.status === 'ACTIVE' ? 'suspend' : 'activate' });
    this.bindingReason.set('');
    this.bindingMessage.set(null);
  }

  protected async confirmBindingChange(installation: PlatformInstallationView): Promise<void> {
    const change = this.changingBinding();
    const reason = this.bindingReason().trim();
    if (change === null || reason.length === 0) {
      return;
    }
    this.manageError.set(null);
    try {
      const result =
        change.action === 'suspend'
          ? await this.api.suspendBinding(installation.tenantId, installation.id, change.id, reason)
          : await this.api.activateBinding(installation.tenantId, installation.id, change.id, reason);
      this.bindingMessage.set(
        this.i18n.t(result.changed ? 'installationsExplorer.bindings.changed' : 'installationsExplorer.bindings.noChange'),
      );
      this.changingBinding.set(null);
      this.bindings.set(await this.api.bindings(installation.tenantId, installation.id));
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    }
  }
}
