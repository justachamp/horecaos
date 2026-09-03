import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 8.2 Business types -- not built.
 *
 * There is no `BusinessType` anywhere in the tenancy schema or domain code
 * -- `TenantControlPlaneController`'s own directory endpoint names the gap
 * directly: "No plan, business type, country, or health score is returned
 * because none of those exist in the schema yet." A tenant carries no
 * business-type assignment today, so there is nothing to browse and nothing
 * to say a shape "enables/defaults." Modeling product shapes (restaurant,
 * courier service, pharmacy, florist) and their defaults is a genuine new
 * subsystem, not a small addition, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-business-types',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.businessTypes') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('businessTypes.notBuilt.body') }}</p>
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
export class BusinessTypes {
  protected readonly i18n = inject(I18nService);
}
