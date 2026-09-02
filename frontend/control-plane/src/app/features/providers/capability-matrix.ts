import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { AdapterCapabilities, ProvidersApi } from './providers-api';

/**
 * IA 3.2 Capability matrix -- which capabilities each POS adapter declares
 * (ADR 0011), read straight from `PosAdapterRegistry`'s own wired beans.
 *
 * Also thin today: one adapter (Clopos) against the twelve POS systems the
 * parity inventory names. Reflects the real Spring context rather than a
 * hand-maintained list, so it can only ever be as complete as the build.
 */
@Component({
  selector: 'app-capability-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './capability-matrix.html',
  styleUrl: './capability-matrix.css',
})
export class CapabilityMatrix {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ProvidersApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly adapters = signal<readonly AdapterCapabilities[]>([]);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.adapters.set(await this.api.capabilityMatrix());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
