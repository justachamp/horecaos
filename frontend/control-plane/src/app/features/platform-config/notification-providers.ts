import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 8.4 Notification providers & template moderation -- not built.
 *
 * There is no platform-wide SMS gateway/sender-alias registry:
 * `SmsAccountLookup` resolves one tenant binding's non-secret account facts
 * at call time, it does not browse or list gateways. `NotificationTemplateController`
 * has authoring and an internal HorecaOS-side activation step, but its own
 * Javadoc says the rest is deferred -- "ADR 0020's full approval workflow is
 * deferred; the attribution is not" -- so there is no *provider-side*
 * moderation state (Eskiz/Playmobile approving a regulated template) to
 * show, and nothing blocks a send while one is pending. Both halves this row
 * needs -- the provider registry and the moderation gate -- are genuine new
 * subsystems, not a small addition to template authoring, so it stays
 * unbuilt this wave.
 */
@Component({
  selector: 'app-notification-providers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.notificationProviders') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('notificationProviders.notBuilt.body') }}</p>
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
export class NotificationProviders {
  protected readonly i18n = inject(I18nService);
}
