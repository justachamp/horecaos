import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { IntentCommandRegistry } from '../../../core/api/idempotency';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { describeWalletError } from './wallet-errors';
import { cardExpiry, safeFormUrl, walletDateTime } from './wallet-format';
import {
  ConfirmCardBody,
  EnrolmentStartedView,
  PAYMENT_METHODS,
  PaymentMethod,
  TenantWalletView,
  WalletApi,
} from './wallet-api';

const METHOD_KEYS: Readonly<Record<PaymentMethod, MessageKey>> = {
  INVOICE: 'finance.wallet.collect.INVOICE',
  WALLET: 'finance.wallet.collect.WALLET',
  CARD: 'finance.wallet.collect.CARD',
};

const METHOD_HINT_KEYS: Readonly<Record<PaymentMethod, MessageKey>> = {
  INVOICE: 'finance.wallet.collect.INVOICE.hint',
  WALLET: 'finance.wallet.collect.WALLET.hint',
  CARD: 'finance.wallet.collect.CARD.hint',
};

/** The provider's refusals after which the session it opened is no use any more. */
const SESSION_GONE = new Set(['SESSION_EXPIRED', 'SESSION_UNKNOWN']);

type Busy = 'begin' | 'confirm' | 'remove' | 'method';

/**
 * The card HorecaOS may charge, and how what the wallet does not cover is collected (ADR 0095).
 *
 * **The card number never reaches HorecaOS, and never reaches this screen.** "Add a card" opens a session
 * with the payment provider; the cardholder types the card into the provider's own form (a page it hosts,
 * linked below) and the form hands back a one-time token. This panel takes two things from the person at the
 * keyboard — that token and the code their bank texted — and forwards them. It shows the card afterwards as
 * the last four digits, the brand and the expiry, because that is all the server ever returns.
 *
 * **Honest about what is not connected.** While HorecaOS has no card merchant account, `cardPaymentsAvailable`
 * is false: "Add a card" is disabled and says why, rather than opening a session that would only be refused.
 * The same flag is why a tenant who chose CARD is told it is collected like an invoice customer until the
 * account exists — that is what the server does.
 *
 * **Choosing CARD is a consent**, and the confirm dialog says so in those words: it lets HorecaOS charge the
 * card for every statement's remainder without the tenant present (ADR 0095). Choosing INVOICE or WALLET
 * needs none.
 */
@Component({
  selector: 'q-wallet-method-panel',
  imports: [TPipe, ConfirmDialog],
  templateUrl: './wallet-method-panel.html',
  styleUrl: './wallet.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletMethodPanel {
  private readonly api = inject(WalletApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly wallet = input.required<TenantWalletView>();
  /** Something changed on the server (a card added or removed, a method chosen): the page re-reads. */
  readonly changed = output<void>();

  protected readonly methods = PAYMENT_METHODS;

  protected readonly enrolment = signal<EnrolmentStartedView | null>(null);
  protected readonly providerToken = signal('');
  protected readonly verificationCode = signal('');
  protected readonly busy = signal<Busy | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly confirming = signal<'remove' | 'card' | null>(null);
  private readonly picked = signal<PaymentMethod | null>(null);

  /** One command per distinct confirmation, so a retry after a lost answer replays the first one's result. */
  private readonly commands = new IntentCommandRegistry<ConfirmCardBody>();

  /** The method as it stands on the server, narrowed to the three this screen knows. */
  protected readonly current = computed<PaymentMethod>(() => {
    const method = this.wallet().paymentMethod;
    return (PAYMENT_METHODS as readonly string[]).includes(method)
      ? (method as PaymentMethod)
      : 'INVOICE';
  });
  protected readonly selected = computed(() => this.picked() ?? this.current());
  protected readonly dirty = computed(() => this.selected() !== this.current());

  protected readonly formUrl = computed(() => safeFormUrl(this.enrolment()?.hostedFormUrl ?? null));
  /** The built-in test provider says so in its client parameters; a real one never does. */
  protected readonly testProvider = computed(
    () => this.enrolment()?.clientParameters['provider'] === 'FAKE_CARD',
  );
  protected readonly canConfirm = computed(
    () =>
      this.busy() === null &&
      this.providerToken().trim().length > 0 &&
      this.verificationCode().trim().length > 0,
  );
  protected readonly cardLabel = computed(() => {
    const card = this.wallet().card;
    if (card === null || card.last4 === null) {
      return null;
    }
    return card.brand
      ? this.i18n.t('finance.wallet.card.ending', { brand: card.brand, last4: card.last4 })
      : this.i18n.t('finance.wallet.card.endingNoBrand', { last4: card.last4 });
  });
  protected readonly expiry = computed(() => {
    const card = this.wallet().card;
    return card === null ? null : cardExpiry(card.expiryMonth, card.expiryYear);
  });

  protected methodLabel(method: PaymentMethod): string {
    return this.i18n.t(METHOD_KEYS[method]);
  }

  protected methodHint(method: PaymentMethod): string {
    return this.i18n.t(METHOD_HINT_KEYS[method]);
  }

  protected time(instant: string): string {
    return walletDateTime(instant);
  }

  protected pick(method: PaymentMethod): void {
    this.picked.set(method);
    this.error.set(null);
    this.notice.set(null);
  }

  // ------------------------------------------------------------ the card

  protected async startEnrolment(): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.busy.set('begin');
    try {
      this.enrolment.set(await this.api.beginCardEnrolment(this.tenantId()));
      this.providerToken.set('');
      this.verificationCode.set('');
    } catch (failure) {
      this.error.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.busy.set(null);
    }
  }

  protected cancelEnrolment(): void {
    if (this.busy() === 'confirm') {
      return;
    }
    this.enrolment.set(null);
    this.providerToken.set('');
    this.verificationCode.set('');
    this.error.set(null);
  }

  protected async confirmEnrolment(): Promise<void> {
    const session = this.enrolment();
    if (session === null || !this.canConfirm()) {
      return;
    }
    const body: ConfirmCardBody = {
      sessionReference: session.sessionReference,
      providerToken: this.providerToken().trim(),
      verificationCode: this.verificationCode().trim(),
    };
    this.error.set(null);
    this.busy.set('confirm');
    try {
      const card = await this.api.confirmCard(this.tenantId(), this.commands.next('confirm', body));
      this.commands.forget('confirm');
      this.enrolment.set(null);
      this.providerToken.set('');
      this.verificationCode.set('');
      this.notice.set(this.i18n.t('finance.wallet.card.added', { last4: card.last4 ?? '' }));
      this.changed.emit();
    } catch (failure) {
      this.error.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
      if (failure instanceof ApiError && SESSION_GONE.has(String(failure.problem?.['reason']))) {
        // The session the provider opened is spent; only a new one can finish this.
        this.commands.forget('confirm');
        this.enrolment.set(null);
      }
    } finally {
      this.busy.set(null);
    }
  }

  protected askRemove(): void {
    if (this.busy() === null) {
      this.error.set(null);
      this.confirming.set('remove');
    }
  }

  protected async removeCard(): Promise<void> {
    if (this.busy() !== null) {
      return;
    }
    this.busy.set('remove');
    try {
      const removed = await this.api.removeCard(this.tenantId());
      this.confirming.set(null);
      this.picked.set(null);
      this.notice.set(
        this.i18n.t(
          this.current() === 'CARD' && removed.paymentMethod !== 'CARD'
            ? 'finance.wallet.card.removedToInvoice'
            : 'finance.wallet.card.removed',
        ),
      );
      this.changed.emit();
    } catch (failure) {
      this.confirming.set(null);
      this.error.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.busy.set(null);
    }
  }

  // ----------------------------------------------------------- the method

  /** Leaving CARD or choosing INVOICE/WALLET is a plain save; choosing CARD is a consent first. */
  protected saveMethod(): void {
    if (!this.dirty() || this.busy() !== null) {
      return;
    }
    this.error.set(null);
    if (this.selected() === 'CARD') {
      this.confirming.set('card');
      return;
    }
    void this.applyMethod();
  }

  protected async applyMethod(): Promise<void> {
    const method = this.selected();
    if (this.busy() !== null) {
      return;
    }
    this.busy.set('method');
    try {
      await this.api.choosePaymentMethod(this.tenantId(), method);
      this.confirming.set(null);
      this.picked.set(null);
      this.notice.set(
        this.i18n.t('finance.wallet.collect.saved', { method: this.methodLabel(method) }),
      );
      this.changed.emit();
    } catch (failure) {
      this.confirming.set(null);
      this.error.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.busy.set(null);
    }
  }

  protected cancelConfirm(): void {
    if (this.busy() === null) {
      this.confirming.set(null);
    }
  }

  protected removeBody(): string {
    return this.i18n.t(
      this.current() === 'CARD'
        ? 'finance.wallet.card.remove.bodyCard'
        : 'finance.wallet.card.remove.body',
    );
  }

  protected consentBody(): string {
    return this.i18n.t('finance.wallet.collect.consent.body', {
      card: this.cardLabel() ?? '',
    });
  }
}
