import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import {
  ConfigurationApi,
  ConfigurationKeyView,
  ConfigurationResolutionView,
  ScopeType,
} from '../platform-config/configuration-api';

/**
 * IA 2.7 Configuration & policy -- platform defaults, tenant overrides, and
 * a resolution trace for any key (ADR 0030: platform -> tenant -> brand ->
 * location -> channel).
 *
 * "-> channel" in the IA row's own parenthetical is not a real level:
 * `ResourceScope.ScopeType` has exactly four (platform, tenant, brand,
 * location), and this picker offers exactly those. There is no fifth,
 * channel-scoped configuration level anywhere in the code this row could
 * point at.
 */
@Component({
  selector: 'app-configuration-policy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './configuration-policy.html',
  styleUrl: './configuration-policy.css',
})
export class ConfigurationPolicy {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ConfigurationApi);

  protected readonly loadingKeys = signal(true);
  protected readonly keysError = signal<string | null>(null);
  protected readonly keys = signal<readonly ConfigurationKeyView[]>([]);

  protected readonly keyCode = signal('');
  protected readonly scopeType = signal<ScopeType>('TENANT');
  protected readonly tenantId = signal('');
  protected readonly brandId = signal('');
  protected readonly locationId = signal('');

  protected readonly resolving = signal(false);
  protected readonly resolveError = signal<string | null>(null);
  protected readonly resolution = signal<ConfigurationResolutionView | null>(null);

  constructor() {
    void this.loadKeys();
  }

  private async loadKeys(): Promise<void> {
    this.loadingKeys.set(true);
    this.keysError.set(null);
    try {
      this.keys.set(await this.api.listKeys());
    } catch (error) {
      this.keysError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loadingKeys.set(false);
    }
  }

  protected canResolve(): boolean {
    if (this.resolving() || this.keyCode().trim().length === 0) {
      return false;
    }
    switch (this.scopeType()) {
      case 'PLATFORM':
        return true;
      case 'TENANT':
        return this.tenantId().trim().length > 0;
      case 'BRAND':
        return this.tenantId().trim().length > 0 && this.brandId().trim().length > 0;
      case 'LOCATION':
        return (
          this.tenantId().trim().length > 0 &&
          this.brandId().trim().length > 0 &&
          this.locationId().trim().length > 0
        );
    }
  }

  protected async resolve(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canResolve()) {
      return;
    }
    this.resolving.set(true);
    this.resolveError.set(null);
    try {
      this.resolution.set(
        await this.api.resolve(
          this.keyCode().trim(),
          this.scopeType(),
          this.tenantId().trim() || undefined,
          this.brandId().trim() || undefined,
          this.locationId().trim() || undefined,
        ),
      );
    } catch (error) {
      this.resolveError.set(this.i18n.describe(error as ApiError));
      this.resolution.set(null);
    } finally {
      this.resolving.set(false);
    }
  }
}
