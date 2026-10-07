import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { IntentCommandRegistry } from '../../../core/api/idempotency';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { declineReasonKey, describeWalletError } from './wallet-errors';
import { currencyIsScaled, parseAmountInput, walletDateTime, walletMoney } from './wallet-format';
import {
  PaymentDetailsView,
  PrepaymentInvoiceView,
  TenantWalletView,
  TopUpView,
  WalletApi,
} from './wallet-api';

const OUTCOME_KEYS: Readonly<Record<string, MessageKey>> = {
  SUCCEEDED: 'finance.wallet.topUp.outcome.SUCCEEDED',
  PENDING: 'finance.wallet.topUp.outcome.PENDING',
  FAILED: 'finance.wallet.topUp.outcome.FAILED',
  NOT_CONFIGURED: 'finance.wallet.topUp.outcome.NOT_CONFIGURED',
};

type Busy = 'card' | 'invoice';

/**
 * The two ways to put paid money into the wallet (ADR 0095): by card, and by bank transfer against an
 * invoice. Both are the tenant's own acts under `commercial.wallet.topup`; neither lets a tenant credit
 * itself without paying.
 *
 * **By card** charges the card on file. The answer is one of four, told apart on screen because they mean
 * different things to a person holding a phone: the money arrived; the card was declined (nothing was
 * charged); the provider has not answered (HorecaOS settles it on its own, do not repeat it — repeating
 * would be a second charge asked of a provider that already holds one); or card payments are not connected.
 * A decline is a 200 `FAILED`, not an error.
 *
 * **Retry is safe, and only because of one rule:** the command (and so the `Idempotency-Key`) is minted
 * once per intent and held until the server has answered. A click after a lost response sends the same key,
 * so the server replays its answer rather than charging again. Changing the amount is a new intent and
 * mints a new key (`IntentCommandRegistry`).
 *
 * **By bank transfer** shows where to pay and asks for an invoice for an amount; the invoice carries a
 * number the payer writes in the transfer's purpose. Nothing is credited until HorecaOS finance records the
 * money arriving, and the screen says so rather than implying the wallet is topped up.
 *
 * **Not connected is said, not hidden.** While HorecaOS has no card merchant account the card form is
 * disabled with the reason beside it; while finance has not published bank details the transfer form is
 * disabled likewise. Nothing is shown that would only be refused.
 */
@Component({
  selector: 'q-wallet-topup-panel',
  imports: [TPipe],
  templateUrl: './wallet-topup-panel.html',
  styleUrl: './wallet.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletTopUpPanel {
  private readonly api = inject(WalletApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly wallet = input.required<TenantWalletView>();
  /** Where an invoice is paid, once HorecaOS has published it; null before that. */
  readonly paymentDetails = input<PaymentDetailsView | null>(null);
  /** Bumped by the page whenever anything changed, so the history is read again. */
  readonly revision = input(0);
  readonly changed = output<void>();

  protected readonly topUps = signal<readonly TopUpView[]>([]);
  protected readonly historyError = signal<string | null>(null);

  protected readonly cardAmountText = signal('');
  protected readonly invoiceAmountText = signal('');
  protected readonly busy = signal<Busy | null>(null);
  protected readonly cardError = signal<string | null>(null);
  protected readonly invoiceError = signal<string | null>(null);
  protected readonly cardResult = signal<TopUpView | null>(null);
  protected readonly issued = signal<PrepaymentInvoiceView | null>(null);

  private readonly commands = new IntentCommandRegistry<{ amountMinor: number }>();

  protected readonly currency = computed(() => this.wallet().paidBalance.currency);
  protected readonly scaled = computed(() => currencyIsScaled(this.currency()));
  protected readonly cardAmount = computed(() =>
    parseAmountInput(this.cardAmountText(), this.currency()),
  );
  protected readonly invoiceAmount = computed(() =>
    parseAmountInput(this.invoiceAmountText(), this.currency()),
  );

  /** Why the card form cannot be used right now, or null when it can. The first reason wins. */
  protected readonly cardBlocked = computed<MessageKey | null>(() => {
    const wallet = this.wallet();
    if (!this.scaled()) {
      return 'finance.wallet.topUp.blocked.unscaled';
    }
    if (!wallet.cardPaymentsAvailable) {
      return 'finance.wallet.topUp.blocked.notConnected';
    }
    if (wallet.card === null) {
      return 'finance.wallet.topUp.blocked.noCard';
    }
    if (wallet.card.lapsed) {
      return 'finance.wallet.topUp.blocked.lapsed';
    }
    if (wallet.pendingTopUp !== null) {
      return 'finance.wallet.topUp.blocked.pending';
    }
    return null;
  });
  protected readonly canTopUp = computed(
    () => this.cardBlocked() === null && this.cardAmount() !== null && this.busy() === null,
  );
  protected readonly canRequestInvoice = computed(
    () =>
      this.scaled() &&
      this.wallet().bankTransferAvailable &&
      this.invoiceAmount() !== null &&
      this.busy() === null,
  );

  constructor() {
    effect(() => {
      this.revision();
      const tenantId = this.tenantId();
      untracked(() => void this.loadHistory(tenantId));
    });
  }

  private async loadHistory(tenantId: string): Promise<void> {
    try {
      this.topUps.set(await this.api.topUps(tenantId));
      this.historyError.set(null);
    } catch (failure) {
      this.historyError.set(
        describeWalletError(failure, (key, values) => this.i18n.t(key, values)),
      );
    }
  }

  protected money(value: { amountMinor: number; currency: string }): string {
    return walletMoney(value, this.i18n.locale());
  }

  protected time(instant: string): string {
    return walletDateTime(instant);
  }

  protected outcomeLabel(outcome: string): string {
    const key = OUTCOME_KEYS[outcome];
    return key ? this.i18n.t(key) : outcome;
  }

  protected outcomeClass(outcome: string): string {
    switch (outcome) {
      case 'SUCCEEDED':
        return 'status-badge--good';
      case 'PENDING':
        return 'status-badge--warn';
      case 'FAILED':
        return 'status-badge--bad';
      default:
        return '';
    }
  }

  /** The note beside a history row: a decline's reason in words when we have them, else the code itself. */
  protected reasonText(topUp: TopUpView): string {
    if (topUp.reason === null) {
      return '';
    }
    const key = declineReasonKey(topUp.reason);
    return key ? this.i18n.t(key) : topUp.reason;
  }

  /** The sentence under the form for what the provider answered. */
  protected resultText(result: TopUpView): string {
    switch (result.outcome) {
      case 'SUCCEEDED':
        return this.i18n.t('finance.wallet.topUp.result.SUCCEEDED', {
          amount: this.money(result.amount),
        });
      case 'FAILED':
        return result.reason === null
          ? this.i18n.t('finance.wallet.topUp.result.FAILED.noReason')
          : this.i18n.t('finance.wallet.topUp.result.FAILED', { reason: this.reasonText(result) });
      case 'PENDING':
        return this.i18n.t('finance.wallet.topUp.result.PENDING');
      case 'NOT_CONFIGURED':
        return this.i18n.t('finance.wallet.topUp.result.NOT_CONFIGURED');
      default:
        return result.outcome;
    }
  }

  protected resultIsGood(result: TopUpView): boolean {
    return result.outcome === 'SUCCEEDED';
  }

  protected async topUpByCard(): Promise<void> {
    const amountMinor = this.cardAmount();
    if (amountMinor === null || !this.canTopUp()) {
      return;
    }
    this.cardError.set(null);
    this.cardResult.set(null);
    this.busy.set('card');
    try {
      const result = await this.api.topUp(
        this.tenantId(),
        this.commands.next('card', { amountMinor }),
      );
      // The server answered, whatever it said: this intent is settled and a new click is a new one.
      this.commands.forget('card');
      this.cardResult.set(result);
      if (result.outcome === 'SUCCEEDED') {
        this.cardAmountText.set('');
      }
      this.changed.emit();
    } catch (failure) {
      // Held: the same click again, same amount, goes out under the same key.
      this.cardError.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.busy.set(null);
    }
  }

  protected async requestInvoice(): Promise<void> {
    const amountMinor = this.invoiceAmount();
    if (amountMinor === null || !this.canRequestInvoice()) {
      return;
    }
    this.invoiceError.set(null);
    this.issued.set(null);
    this.busy.set('invoice');
    try {
      const invoice = await this.api.issueInvoice(
        this.tenantId(),
        this.commands.next('invoice', { amountMinor }),
      );
      this.commands.forget('invoice');
      this.issued.set(invoice);
      this.invoiceAmountText.set('');
      this.changed.emit();
    } catch (failure) {
      this.invoiceError.set(
        describeWalletError(failure, (key, values) => this.i18n.t(key, values)),
      );
    } finally {
      this.busy.set(null);
    }
  }

  protected issuedText(invoice: PrepaymentInvoiceView): string {
    return this.i18n.t('finance.wallet.transfer.issued', {
      number: invoice.number,
      amount: this.money(invoice.amount),
    });
  }
}
