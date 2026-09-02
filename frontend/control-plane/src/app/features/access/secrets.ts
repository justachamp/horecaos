import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { PlatformInstallationView, ProvidersApi } from '../providers/providers-api';

/**
 * IA 7.4 Secrets -- inventory of provider credentials by tenant/branch,
 * rotation status, last use, never rendered (ADR 0028).
 *
 * Reuses the same cross-tenant installations read 3.3 uses (only
 * `secretReference`'s presence is shown, never its value, and never any
 * value at all — the value has no read path anywhere in this API by
 * construction). No "last used" timestamp exists anywhere in this schema,
 * only `lastSecretRotatedAt`; this screen shows that honestly rather than
 * inventing a last-use figure.
 */
@Component({
  selector: 'app-secrets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './secrets.html',
  styleUrl: './secrets.css',
})
export class Secrets {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(ProvidersApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly installations = signal<readonly PlatformInstallationView[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.api.listInstallations(null, 200);
      this.installations.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
