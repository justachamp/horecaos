import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * A capability denial, which is a wall and not an upsell.
 *
 * Deliberately distinct from a plan lock: ADR 0021 and ADR 0031 keep
 * INSUFFICIENT_CAPABILITY and ENTITLEMENT_REQUIRED apart because the
 * remediation differs — ask an administrator, versus change a plan — and one
 * screen for both sends half its readers to the wrong person.
 */
@Component({
  selector: 'app-access-denied',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="state">
      <h1 class="q-title">{{ i18n.t('state.denied.title') }}</h1>
      <p class="q-body-sm body">{{ i18n.t('state.denied.body') }}</p>
    </section>
  `,
  styles: `
    .state {
      max-width: 560px;
    }

    .body {
      color: var(--q-ink-muted);
      margin-top: 8px;
    }
  `,
})
export class AccessDenied {
  protected readonly i18n = inject(I18nService);
}
