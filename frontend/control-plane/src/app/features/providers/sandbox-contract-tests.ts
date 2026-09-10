import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { PlatformInstallationView, ProviderEnvironment, ProvidersApi } from './providers-api';

/**
 * IA 3.5 Sandbox & contract tests -- trying an adapter against a provider's
 * test endpoint before a restaurant depends on it.
 *
 * Lists every installation pointed at a non-production provider endpoint and
 * runs the same connection check a live installation gets, so an adapter
 * change can be tried on a sandbox tenant first. Replaying recorded provider
 * traffic is the contract-test suite's job and runs on every build, not from
 * this screen.
 */
@Component({
  selector: 'app-sandbox-contract-tests',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './sandbox-contract-tests.html',
  styleUrl: './sandbox-contract-tests.css',
})
export class SandboxContractTests {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(ProvidersApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly environments = signal<readonly ProviderEnvironment[]>([]);
  protected readonly installations = signal<readonly PlatformInstallationView[]>([]);
  protected readonly checking = signal<string | null>(null);
  protected readonly results = signal<Readonly<Record<string, string>>>({});

  /** Installations whose endpoint is a sandbox, which is what may be tried freely. */
  protected readonly sandboxInstallations = computed(() => {
    const sandbox = new Set(this.environments().filter((e) => !e.production).map((e) => e.code));
    return this.installations().filter((installation) => sandbox.has(installation.environmentCode));
  });

  protected readonly sandboxEnvironments = computed(() => this.environments().filter((e) => !e.production));

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [environments, installations] = await Promise.all([
        this.api.environments(),
        this.api.listInstallations(null, 200),
      ]);
      this.environments.set(environments);
      this.installations.set(installations.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  /** What the last check here found, else what the installation last reported. */
  protected connection(installation: PlatformInstallationView): string {
    const here = this.results()[installation.id] as string | undefined;
    return here ?? installation.lastConnectionStatus ?? this.i18n.t('installationsExplorer.neverChecked');
  }

  protected async check(installation: PlatformInstallationView): Promise<void> {
    if (this.checking() !== null) {
      return;
    }
    this.checking.set(installation.id);
    try {
      const result = await this.api.checkConnection(installation.tenantId, installation);
      const status = result.connectionStatus ?? 'SUCCEEDED';
      this.results.update((current) => ({
        ...current,
        [installation.id]: this.i18n.t('installationsExplorer.check.result', { status }),
      }));
    } catch (error) {
      this.results.update((current) => ({ ...current, [installation.id]: this.i18n.describe(error as ApiError) }));
    } finally {
      this.checking.set(null);
    }
  }
}
