import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { ConfigurationApi, ConfigurationKeyView } from './configuration-api';

/**
 * IA 8.5 Policy defaults -- platform-level order/SLA/retention defaults
 * tenants inherit.
 *
 * Reads the same `ConfigurationKeys` registry IA 2.7's resolution debugger
 * reads, filtered to {@link ConfigurationKeyView#tenantVisible}: a platform
 * default a tenant may never even see is not one it "inherits" in the sense
 * this row means. There is no separate SLA/retention registry -- `ordering`,
 * `notifications`, and `telemetry` keys are the closest this build has to
 * "order/SLA/retention defaults", and this screen shows exactly what is
 * declared, not a curated subset.
 */
@Component({
  selector: 'app-policy-defaults',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './policy-defaults.html',
  styleUrl: './policy-defaults.css',
})
export class PolicyDefaults {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ConfigurationApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  private readonly keys = signal<readonly ConfigurationKeyView[]>([]);

  protected readonly tenantVisibleKeys = computed(() => this.keys().filter((key) => key.tenantVisible));

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.keys.set(await this.api.listKeys());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
