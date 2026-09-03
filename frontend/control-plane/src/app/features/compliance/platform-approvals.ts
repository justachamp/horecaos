import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 6.5 Approvals -- not built.
 *
 * `ApprovalRequestController`'s pending-decision queue is real and generic
 * over any `actionCode` a tenant's approval policy governs -- IA 7.1 Staff &
 * roles already reuses it for the IAM-grant checker half of maker-checker.
 * What this row names as its reason to exist is different: platform-side
 * approvals for residency change, bulk export, and retention override. None
 * of the three is an action anything in this codebase raises -- per-tenant
 * residency is not modeled (IA 6.3), there is no bulk PII export path, and
 * there is no retention-override mechanism (IA 6.4) -- so no approval
 * request with one of those action codes can ever exist to list. Building
 * this screen today would either duplicate 7.1's identical generic queue for
 * nothing this row specifically asks for, or list nothing at all; either way
 * it stays unbuilt until the actions it is meant to gate exist.
 */
@Component({
  selector: 'app-platform-approvals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.platformApprovals') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('platformApprovals.notBuilt.body') }}</p>
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
export class PlatformApprovals {
  protected readonly i18n = inject(I18nService);
}
