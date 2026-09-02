import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { ProviderConnectDeclaration, ProvidersApi } from './providers-api';

/**
 * IA 3.1 Provider registry -- every adapter this build declares.
 *
 * Honestly thin: the IA row imagines "12 POS, 8 aggregators, 6 delivery, 7
 * payment, 2 SMS" and more; this build has three real adapters (Click,
 * Payme, Telegram). The screen shows exactly that rather than padding the
 * registry with rows for integrations nobody built.
 */
@Component({
  selector: 'app-provider-registry',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './provider-registry.html',
  styleUrl: './provider-registry.css',
})
export class ProviderRegistry {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ProvidersApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly providers = signal<readonly ProviderConnectDeclaration[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.providers.set(await this.api.listProviders());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
