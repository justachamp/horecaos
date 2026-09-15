import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ExternalPartnerResponse, ExternalQuoteResponse } from '../delivery/dispatch-api';

/** What the operator asked this dialog to do — the parent owns every API call. */
export interface ExternalBookingSubmission {
  readonly bindingId: string;
  readonly quoteId: string;
}

/**
 * The Millenium pattern's own confirmation seam — «Вызвать курьера» (gap map
 * row 1.2f, orders.md/dispatch board). Purely presentational, mirroring
 * {@code OrderOutcomeReasonDialog}'s own shape: the host resolves {@link
 * partners} before opening this and performs every mutation
 * ({@link quoteRequested}/{@link accepted}/{@link abandoned}) itself.
 *
 * <p><b>A price increase cannot be accepted implicitly.</b> {@link canAccept}
 * is false — and the Принять button stays disabled — until the operator has
 * both received a priced quote <em>and</em>, whenever that price exceeds the
 * customer's own delivery fee, ticked the acknowledgement checkbox this
 * dialog renders only in that case. There is no path from "a quote arrived"
 * to "accepted" that skips the checkbox when the price went up: {@link
 * onQuoteChange} resets the acknowledgement on every new quote, so a second,
 * higher re-quote cannot ride on an acknowledgement the operator gave a
 * cheaper one.
 */
@Component({
  selector: 'q-external-courier-dialog',
  imports: [TPipe],
  templateUrl: './external-courier-dialog.html',
  styleUrl: './external-courier-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExternalCourierDialog {
  private readonly i18n = inject(I18n);

  readonly partners = input.required<readonly ExternalPartnerResponse[]>();
  readonly quote = input<ExternalQuoteResponse | null>(null);
  readonly busy = input(false);

  readonly quoteRequested = output<string>();
  readonly accepted = output<ExternalBookingSubmission>();
  readonly abandoned = output<ExternalBookingSubmission>();
  readonly dismiss = output<void>();

  protected readonly selectedBindingId = signal<string | null>(null);
  protected readonly acknowledged = signal(false);
  private lastQuoteId: string | null = null;

  protected readonly deltaPositive = computed(() => {
    const quote = this.quote();
    return !!quote && quote.priced && (quote.deltaMinor ?? 0) > 0;
  });

  /**
   * False whenever a price increase has not been explicitly acknowledged —
   * the one property this whole dialog exists to guarantee. See the class
   * doc for why a re-quote can never inherit an earlier acknowledgement.
   */
  protected readonly canAccept = computed(() => {
    const quote = this.quote();
    if (!quote || !quote.priced || !quote.quoteId) {
      return false;
    }
    return !this.deltaPositive() || this.acknowledged();
  });

  constructor() {
    // Clears a stale acknowledgement the instant a new quote arrives --
    // including a re-quote for the same partner at a higher price. See the
    // class doc: this is the mechanism that makes an acknowledgement given
    // to one price never carry over to a different one.
    effect(() => {
      this.onQuoteChange(this.quote()?.quoteId);
    });
  }

  protected selectPartner(bindingId: string): void {
    this.selectedBindingId.set(bindingId);
  }

  protected requestQuote(): void {
    const bindingId = this.selectedBindingId() ?? this.partners()[0]?.bindingId;
    if (bindingId) {
      this.quoteRequested.emit(bindingId);
    }
  }

  protected toggleAcknowledged(value: boolean): void {
    this.acknowledged.set(value);
  }

  /** Invoked by the constructor's own `effect` whenever `quote()`'s id changes — see the class doc. */
  private onQuoteChange(quoteId: string | null | undefined): void {
    const id = quoteId ?? null;
    if (id !== this.lastQuoteId) {
      this.lastQuoteId = id;
      this.acknowledged.set(false);
    }
  }

  protected confirmAccept(): void {
    const quote = this.quote();
    if (!this.canAccept() || !quote?.quoteId || !quote.bindingId) {
      return;
    }
    this.accepted.emit({ bindingId: quote.bindingId, quoteId: quote.quoteId });
  }

  protected confirmAbandon(): void {
    const quote = this.quote();
    if (!quote?.quoteId || !quote.bindingId) {
      return;
    }
    this.abandoned.emit({ bindingId: quote.bindingId, quoteId: quote.quoteId });
  }

  protected close(): void {
    this.dismiss.emit();
  }

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }
}
