import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 4.3 Webhook deliveries -- not built.
 *
 * Nothing in this build persists a durable, queryable webhook delivery
 * record. `TelegramWebhookController` receives and processes a webhook
 * inline; it does not log a delivery row a console could list or redeliver.
 * `FailureOperationsController` (IA 4.1/4.2) covers the Kafka outbox/inbox,
 * which is a different durable store from an inbound HTTP callback ledger.
 * There is no "outbound webhook" concept at all -- HorecaOS calls providers,
 * it does not push webhooks to them. An inbound/outbound delivery history
 * with selective redelivery is a genuine new subsystem, not a small
 * addition, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-webhook-deliveries',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.webhookDeliveries') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('webhookDeliveries.notBuilt.body') }}</p>
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
export class WebhookDeliveries {
  protected readonly i18n = inject(I18nService);
}
