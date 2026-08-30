import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * The customer directory.
 *
 * An empty route on purpose. The directory is the screen an account manager
 * lives on and it is specified in docs/operations-spec and shown in the
 * prototype; half of it, built by somebody establishing the foundations, would
 * have to be thrown away by whoever builds the rest.
 *
 * What this route does prove is that the capability guard works: it is behind
 * TENANT_READ, so an operator without it lands on the denied state rather than
 * on a table that answers 403.
 */
@Component({
  selector: 'app-tenants',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('tenants.title') }}</h1>
    <p class="q-body-sm lead">{{ i18n.t('tenants.lead') }}</p>

    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('state.notBuilt.body') }}</p>
    </section>
  `,
  styles: `
    .lead {
      color: var(--q-ink-muted);
      margin-top: 4px;
      max-width: 640px;
    }

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
export class Tenants {
  protected readonly i18n = inject(I18nService);
}
