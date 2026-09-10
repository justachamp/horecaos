import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BrandView, LocationView, TenantsApi } from '../tenants/tenants-api';
import {
  BindingView,
  PlatformInstallationView,
  ProviderConnectDeclaration,
  ProviderEnvironment,
  ProvidersApi,
  acceptsCredential,
} from './providers-api';

/**
 * IA 3.3 Installations -- every provider installation across tenants.
 *
 * Staff can install a provider for a tenant against an approved endpoint,
 * bind it to a brand or branch (created suspended, activated once someone
 * confirms the place), check its connection with the restaurant's own
 * credential, and replace that credential. A credential is typed once, sent
 * through the write-only door, and cleared from the form; it is never shown
 * again. Merchants can connect providers themselves in the operations app.
 *
 * There is no error-rate column: nothing records one. The last connection
 * result and the last credential rotation are shown instead.
 */
@Component({
  selector: 'app-installations-explorer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TenantPicker],
  templateUrl: './installations-explorer.html',
  styleUrl: './installations-explorer.css',
})
export class InstallationsExplorer {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly acceptsCredential = acceptsCredential;
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

  protected readonly session = inject(SessionContextService);
  private readonly directory = inject(TenantDirectory);
  protected readonly busy = signal(false);
  protected readonly actionMessage = signal<string | null>(null);

  // ---------------------------------------------------------------- install
  protected readonly installing = signal(false);
  protected readonly environments = signal<readonly ProviderEnvironment[]>([]);
  private readonly declarations = signal<readonly ProviderConnectDeclaration[]>([]);
  protected readonly installTenantId = signal(this.directory.selected());
  protected readonly environmentCode = signal('');
  protected readonly displayName = signal('');
  /** Held only until it is sent through the door, then cleared. */
  protected readonly credential = signal('');
  protected readonly externalAccount = signal('');
  protected readonly installError = signal<string | null>(null);
  protected readonly environment = computed(
    () => this.environments().find((candidate) => candidate.code === this.environmentCode()) ?? null,
  );
  /** The name the provider gives its credential (Click's secret key, a bot token), when the build declares it. */
  protected readonly credentialField = computed(() => {
    const environment = this.environment();
    const declaration = this.declarations().find(
      (candidate) => candidate.providerType.toLowerCase() === environment?.providerType.toLowerCase(),
    );
    return declaration?.fields.find((field) => field.secret)?.key ?? null;
  });

  // ---------------------------------------------------------------- bind, rotate, settings
  protected readonly declaredCapabilities = signal<readonly string[]>([]);
  protected readonly bindBrandId = signal('');
  protected readonly bindLocationId = signal('');
  protected readonly bindCapabilities = signal<ReadonlySet<string>>(new Set());
  protected readonly rotateValue = signal('');
  protected readonly rotateReason = signal('');
  protected readonly clerkApproval = signal<boolean | null>(null);
  protected readonly bindLocations = computed(() =>
    this.locations().filter((location) => location.brandId === this.bindBrandId()),
  );

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
    this.actionMessage.set(null);
    this.bindBrandId.set('');
    this.bindLocationId.set('');
    this.bindCapabilities.set(new Set());
    this.rotateValue.set('');
    this.rotateReason.set('');
    this.clerkApproval.set(null);
    this.declaredCapabilities.set([]);
    void this.loadProviderDetail(installation);
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

  /** What a POS adapter declares it can do, and Clopos's acceptance setting; both optional extras. */
  private async loadProviderDetail(installation: PlatformInstallationView): Promise<void> {
    if (installation.category === 'POS') {
      const matrix = await this.api.capabilityMatrix().catch(() => []);
      const declared = matrix.find((row) => row.providerType.toLowerCase() === installation.providerType.toLowerCase());
      this.declaredCapabilities.set(declared?.declaredCapabilities ?? []);
    }
    if (installation.providerType.toLowerCase() === 'clopos') {
      const settings = await this.api.cloposSettings(installation.tenantId, installation.id).catch(() => null);
      this.clerkApproval.set(settings?.requireClerkApproval ?? null);
    }
  }

  // ---------------------------------------------------------------- install

  protected async openInstall(): Promise<void> {
    this.installing.set(!this.installing());
    this.installError.set(null);
    if (!this.installing() || this.environments().length > 0) {
      return;
    }
    try {
      const [environments, declarations] = await Promise.all([
        this.api.environments(),
        this.api.listProviders().catch(() => []),
      ]);
      this.environments.set(environments);
      this.declarations.set(declarations);
    } catch (error) {
      this.installError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected canInstall(): boolean {
    return (
      !this.busy() &&
      this.installTenantId().length > 0 &&
      this.environment() !== null &&
      this.displayName().trim().length > 0
    );
  }

  /**
   * Writes the credential through the door first, when one was typed, and
   * installs with the reference that comes back. The typed value is cleared
   * before either call, so it does not outlive the request even on failure.
   */
  protected async install(event: Event): Promise<void> {
    event.preventDefault();
    const environment = this.environment();
    if (!this.canInstall() || environment === null) {
      return;
    }
    const tenantId = this.installTenantId();
    const value = this.credential();
    this.credential.set('');
    this.busy.set(true);
    this.installError.set(null);
    this.actionMessage.set(null);
    try {
      const secretReference =
        value.trim().length > 0 && acceptsCredential(environment.category)
          ? await this.api.writeCredential(tenantId, environment.category, environment.providerType, value)
          : undefined;
      await this.api.install(tenantId, {
        category: environment.category,
        providerType: environment.providerType,
        environmentCode: environment.code,
        displayName: this.displayName().trim(),
        secretReference,
        externalAccountReference: this.externalAccount().trim() || undefined,
      });
      this.installing.set(false);
      this.displayName.set('');
      this.externalAccount.set('');
      this.environmentCode.set('');
      this.actionMessage.set(this.i18n.t('installationsExplorer.install.done', { tenant: this.directory.nameOf(tenantId) }));
      await this.load();
    } catch (error) {
      this.installError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  // ---------------------------------------------------------------- bind

  protected toggleCapability(code: string, on: boolean): void {
    this.bindCapabilities.update((current) => {
      const next = new Set(current);
      if (on) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  protected async bind(installation: PlatformInstallationView): Promise<void> {
    if (this.busy() || this.bindBrandId().length === 0) {
      return;
    }
    this.busy.set(true);
    this.manageError.set(null);
    const capabilities = [...this.bindCapabilities()];
    try {
      await this.api.bind(installation.tenantId, installation.id, {
        brandId: this.bindBrandId(),
        locationId: this.bindLocationId() || undefined,
        capabilities,
        // Each capability this binding takes on is its place's main one; the server
        // refuses a second main provider for the same place and capability.
        primaryCapabilities: capabilities,
      });
      this.bindBrandId.set('');
      this.bindLocationId.set('');
      this.bindCapabilities.set(new Set());
      this.bindingMessage.set(this.i18n.t('installationsExplorer.bind.done'));
      this.bindings.set(await this.api.bindings(installation.tenantId, installation.id));
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  // ---------------------------------------------------------------- rotate & settings

  protected async rotate(installation: PlatformInstallationView): Promise<void> {
    const value = this.rotateValue();
    const reason = this.rotateReason().trim();
    if (this.busy() || value.trim().length === 0 || reason.length === 0) {
      return;
    }
    this.rotateValue.set('');
    this.busy.set(true);
    this.manageError.set(null);
    try {
      await this.api.rotateCredential(installation.tenantId, installation.id, value, reason);
      this.rotateReason.set('');
      this.bindingMessage.set(this.i18n.t('installationsExplorer.rotate.done'));
      const now = new Date().toISOString();
      this.installations.update((rows) =>
        rows.map((row) => (row.id === installation.id ? { ...row, lastSecretRotatedAt: now } : row)),
      );
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  protected async setClerkApproval(installation: PlatformInstallationView, required: boolean): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.manageError.set(null);
    try {
      await this.api.setCloposSettings(installation.tenantId, installation.id, required);
      this.clerkApproval.set(required);
      this.bindingMessage.set(this.i18n.t('installationsExplorer.clopos.saved'));
    } catch (error) {
      this.manageError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
