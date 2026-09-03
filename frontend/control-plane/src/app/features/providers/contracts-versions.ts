import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { EventContractView, ProvidersApi } from './providers-api';

/**
 * IA 3.4 Contracts & versions -- the ADR 0032 event/schema contract half of
 * this row, real and code-owned (`EventCatalog`).
 *
 * Named gap, same discipline as 3.1 Provider registry: "adapter versions,
 * deprecations, consumer compatibility" -- provider adapter versioning
 * distinct from an event contract -- is not modeled anywhere in this build.
 * `EventContractController`'s own doc comment names this directly. This
 * screen shows the contract registry, which is real, and invents nothing for
 * the other half.
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

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.contracts.set(await this.api.listEventContracts());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
