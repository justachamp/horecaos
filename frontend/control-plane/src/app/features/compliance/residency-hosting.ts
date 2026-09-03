import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 6.3 Residency & hosting -- not built.
 *
 * `Tenant` carries a `defaultCurrency` and a `defaultTimezone`, and nothing
 * else placing it in a country or a hosting region: `TenantControlPlaneController`'s
 * own directory endpoint says so directly -- "No plan, business type,
 * country, or health score is returned because none of those exist in the
 * schema yet." ADR 0034 ("Hosting environments, topology, and data
 * residency") stays `Partial` for the same reason. The platform-wide half of
 * this row -- which countries HorecaOS markets to, with their default
 * currency/timezone -- is real and already shown at IA 8.3 Reference data;
 * what is missing is the per-tenant assignment ("where THIS tenant's data
 * lives") the row is actually about, and inventing one here would show a
 * fact nobody decided. Recording and displaying per-tenant residency is a
 * genuine new subsystem, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-residency-hosting',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.residencyHosting') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('residencyHosting.notBuilt.body') }}</p>
    </section>
  `,
  styles: `
    .notice {
      margin-top: 24px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      padding: 24px;
      max-width: 640px;
    }

    .body {
      color: var(--q-ink-muted);
      margin-top: 8px;
    }
  `,
})
export class ResidencyHosting {
  protected readonly i18n = inject(I18nService);
}
