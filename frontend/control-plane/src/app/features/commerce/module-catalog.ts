import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 5.2 Module catalog -- not built.
 *
 * The commercial module has no "module" concept anywhere: `commercial.api`
 * and `commercial.domain` model plans, plan versions, plan entitlements,
 * subscriptions, entitlement overrides, and metered usage -- never a
 * sellable module with its own heterogeneous billing unit (per brand, per
 * branch, per kiosk, per courier service, one-off). `PlanEntitlement` is the
 * closest existing shape, and it is a line on a plan, not an independently
 * sellable, independently billed unit. A module catalog with per-unit
 * billing is a genuine new subsystem, not a small addition to the existing
 * plan/entitlement model, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-module-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.moduleCatalog') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('moduleCatalog.notBuilt.body') }}</p>
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
export class ModuleCatalog {
  protected readonly i18n = inject(I18nService);
}
