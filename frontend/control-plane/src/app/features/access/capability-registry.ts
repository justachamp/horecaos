import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { AccessApi, CapabilityDescriptor } from './access-api';

/**
 * IA 7.2 Capability registry -- the canonical ADR 0025 capability
 * vocabulary, granular to per-bulk-action.
 */
@Component({
  selector: 'app-capability-registry',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './capability-registry.html',
  styleUrl: './capability-registry.css',
})
export class CapabilityRegistry {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(AccessApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly capabilities = signal<readonly CapabilityDescriptor[]>([]);
  protected readonly filter = signal('');

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.capabilities.set(await this.api.capabilityRegistry());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected filtered(): readonly CapabilityDescriptor[] {
    const term = this.filter().trim().toLowerCase();
    if (term.length === 0) {
      return this.capabilities();
    }
    return this.capabilities().filter(
      (capability) =>
        capability.code.toLowerCase().includes(term) || capability.resourceType.toLowerCase().includes(term),
    );
  }
}
