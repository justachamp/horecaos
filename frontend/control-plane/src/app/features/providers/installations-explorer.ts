import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { PlatformInstallationView, ProvidersApi } from './providers-api';

/**
 * IA 3.3 Installations explorer -- every `(tenant, provider, branch)`
 * installation at platform scope.
 *
 * No error-rate column: nothing in the schema records one. `lastConnectionStatus`
 * and `lastSecretRotatedAt` are the closest real signals, and this screen
 * shows those rather than a fabricated rate.
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

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly installations = signal<readonly PlatformInstallationView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

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
}
