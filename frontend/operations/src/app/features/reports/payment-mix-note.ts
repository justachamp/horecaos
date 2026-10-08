import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * What a reader of a payment split has to be told beside it (ADR 0115):
 * three short facts the figure cannot show about itself, in the one place
 * every report that reads `payment_mix.amount.v1` puts them.
 *
 * - **Provisional** — finance has not signed the metric's definition. The
 *   response's own provenance says so (`provisionalMetrics`), and the note
 *   repeats it beside the card rather than leaving it to a banner at the top
 *   of the page that a card further down never mentions.
 * - **The open question** — the registry's own `openQuestion` for the metric:
 *   the split is counted at the amount recorded as tendered, never netted of
 *   what a card or wallet provider keeps as commission. Shown only while the
 *   metric dictionary still carries the question, so the day finance closes
 *   it the line goes away with it.
 * - **Not cut by channel or fulfilment type** — the tender fact has neither
 *   column (the record's grain is order, date, branch, legal entity, method),
 *   so a channel or fulfilment filter that narrows the cards beside this one
 *   does not narrow it, and it says so instead of quietly disagreeing.
 */
@Component({
  selector: 'q-payment-mix-note',
  imports: [TPipe],
  template: `
    @if (provisional() || openQuestion() || notCutByChannelOrFulfilment()) {
      <div class="payment-mix-note q-caption" data-testid="payment-mix-note">
        @if (provisional()) {
          <p data-testid="payment-mix-provisional">{{ 'reports.paymentMix.provisional' | t }}</p>
        }
        @if (openQuestion()) {
          <p data-testid="payment-mix-open-question">{{ 'reports.paymentMix.openQuestion' | t }}</p>
        }
        @if (notCutByChannelOrFulfilment()) {
          <p data-testid="payment-mix-not-cut">{{ 'reports.paymentMix.notCutBy' | t }}</p>
        }
      </div>
    }
  `,
  styles: `
    .payment-mix-note {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-top: 8px;
      color: var(--q-ink-subtle);
    }
    .payment-mix-note p {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentMixNote {
  /** `payment_mix.amount.v1` is in the response's `provisionalMetrics`. */
  readonly provisional = input(false);
  /** The metric dictionary still carries an `openQuestion` for `payment_mix.amount.v1`. */
  readonly openQuestion = input(false);
  /** A channel or fulfilment-type filter is active that this split ignores. */
  readonly notCutByChannelOrFulfilment = input(false);
}
