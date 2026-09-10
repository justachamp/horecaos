import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { AdapterVersionView, EventContractView, ProvidersApi } from './providers-api';

/**
 * IA 3.4 Contracts & versions -- every event this build can publish with its
 * schema version, and which adapter version each provider's installations
 * run, with how many of them last connected.
 *
 * Deprecations are not recorded: no adapter has been retired yet, so there is
 * nothing to list, and the screen says so rather than inventing a schedule.
 */
@Component({
  selector: 'app-contracts-versions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './contracts-versions.html',
  styleUrl: './contracts-versions.css',
})
export class ContractsVersions {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ProvidersApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly contracts = signal<readonly EventContractView[]>([]);
  protected readonly adapters = signal<readonly AdapterVersionView[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [contracts, adapters] = await Promise.all([
        this.api.listEventContracts(),
        this.api.adapterVersions().catch(() => []),
      ]);
      this.contracts.set(contracts);
      this.adapters.set(adapters);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
