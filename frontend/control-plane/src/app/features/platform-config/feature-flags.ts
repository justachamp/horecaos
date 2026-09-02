import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 8.1 Feature flags & rollout -- not built.
 *
 * No cohort/percentage-rollout mechanism exists anywhere in this codebase
 * (confirmed by exhaustive grep across the backend). `EntitlementValue`'s own
 * doc comment explicitly distinguishes an entitlement from a feature flag,
 * which is the closest anything in this build comes to the concept.
 * Progressive enablement by tenant cohort is a genuine new subsystem, not a
 * small read-model addition, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-feature-flags',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.featureFlags') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('featureFlags.notBuilt.body') }}</p>
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
export class FeatureFlags {
  protected readonly i18n = inject(I18nService);
}
