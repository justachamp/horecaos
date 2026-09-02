import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 2.8 Impersonation & support sessions -- not built.
 *
 * No backend surface exists for this anywhere: no support-session endpoint,
 * no scoped time-boxed identity mechanism. `CustomerController`'s own code
 * comment names exactly this shape as a *future* need ("a support agent
 * acting for a customer needs its own endpoint that records both
 * identities") and explicitly declines to build it as a side effect of
 * something else. Issuing a scoped, time-boxed, audited session into another
 * app's identity is a real security feature in its own right -- not a small
 * read-model addition -- so it stays unbuilt this wave rather than being
 * improvised without ADR 0027 evidence and ADR 0003 review.
 */
@Component({
  selector: 'app-tenant-impersonation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('tenantDetail.action.impersonation') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('impersonation.notBuilt.body') }}</p>
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
export class TenantImpersonation {
  protected readonly i18n = inject(I18nService);
}
