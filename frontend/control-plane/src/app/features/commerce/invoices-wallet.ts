import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 5.5 Invoices & wallet -- not built.
 *
 * `commercial.domain`/`commercial.infrastructure.persistence` model plans,
 * subscriptions, entitlements, and metered usage; nothing models an invoice
 * or a prepaid wallet ledger. `CommercialControlPlaneController`'s own usage
 * read carries "measured and adjusted quantities" precisely so a bill can be
 * defended, but nothing turns that into a subscription invoice, and there is
 * no top-up, balance, or credit-expiry row anywhere in the schema. Invoicing
 * and a prepaid wallet are a genuine new subsystem, not a small addition on
 * top of usage metering, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-invoices-wallet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.invoicesWallet') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('invoicesWallet.notBuilt.body') }}</p>
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
export class InvoicesWallet {
  protected readonly i18n = inject(I18nService);
}
