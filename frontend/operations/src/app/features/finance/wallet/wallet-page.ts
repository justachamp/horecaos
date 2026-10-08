import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { CommercialApi, TenantArrearsView } from '../commercial-api';
import { describeWalletError } from './wallet-errors';
import { walletDate, walletMoney } from './wallet-format';
import { PaymentDetailsView, PaymentMethod, TenantWalletView, WalletApi } from './wallet-api';
import { WalletInvoicesPanel } from './wallet-invoices-panel';
import { WalletLedgerPanel } from './wallet-ledger-panel';
import { WalletMethodPanel } from './wallet-method-panel';
import { WalletTopUpPanel } from './wallet-topup-panel';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const METHOD_KEYS: Readonly<Record<string, MessageKey>> = {
  INVOICE: 'finance.wallet.collect.INVOICE',
  WALLET: 'finance.wallet.collect.WALLET',
  CARD: 'finance.wallet.collect.CARD',
};

/**
 * 8.6 Subscription & billing, the prepaid half (`frontend-information-architecture.md` §8.6, ADR 0095):
 * "prepaid wallet + top-up via Click/Atmos; credit-expiry warning; arrears state".
 *
 * A tab beside the subscription page rather than a section of it: the subscription page is what the plan
 * and its modules cost, this is what has been paid and how to pay more, and the two were already long
 * enough that one screen would bury the other. The subscription page's arrears banner links here.
 *
 * **What a tenant can do here** is exactly what the server lets a tenant do, and no more: keep a card on
 * file through the provider's own form, top the wallet up by card, ask for an invoice to pay by bank
 * transfer, and choose how what the wallet does not cover is collected. It cannot grant itself credit,
 * correct a balance, or record a transfer — those are HorecaOS staff's, behind two people (decision 4).
 *
 * **What is honestly not connected is said, not hidden.** HorecaOS has no card merchant account yet
 * (the real provider connection is the platform owner's, completed later as an ADR 0026 installation),
 * so `cardPaymentsAvailable` is false and the card entry points say so beside themselves instead of
 * failing on click; likewise the bank details are a placeholder until finance replaces them. Both flags
 * come from the server, so the day either is connected this screen needs no change.
 *
 * **Credit that is about to lapse is warned about here** (`lapsingGrants`, a window the server owns): bonus
 * credit is spent first and lost on its date, which a tenant who did not know it was there cannot act on.
 *
 * Panels own their own data and tell this page when something changed, which re-reads the overview and
 * bumps {@link revision} so each panel reads again; the balances above them are therefore never older than
 * the rows beneath.
 */
@Component({
  selector: 'q-wallet-page',
  imports: [
    TPipe,
    RouterLink,
    WalletMethodPanel,
    WalletTopUpPanel,
    WalletInvoicesPanel,
    WalletLedgerPanel,
  ],
  templateUrl: './wallet-page.html',
  styleUrls: ['./wallet.css', './wallet-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(WalletApi);
  private readonly commercial = inject(CommercialApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly tenantId = signal<string | null>(null);
  protected readonly wallet = signal<TenantWalletView | null>(null);
  protected readonly paymentDetails = signal<PaymentDetailsView | null>(null);
  protected readonly arrears = signal<TenantArrearsView | null>(null);
  protected readonly revision = signal(0);

  protected readonly owed = computed(() => this.arrears()?.owed ?? null);
  protected readonly collectedBy = computed(() => {
    const method = this.wallet()?.paymentMethod ?? 'INVOICE';
    const key = METHOD_KEYS[method as PaymentMethod];
    return key ? this.i18n.t(key) : method;
  });

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    this.tenantId.set(tenantId);
    try {
      await this.readOverview(tenantId);
      this.state.set('ready');
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(
          describeWalletError(failure, (key, values) => this.i18n.t(key, values)),
        );
        this.state.set('error');
      }
    }
  }

  /**
   * The overview, the bank details (only when they are published: before that the server returns the
   * placeholder text, which no screen may present as an account) and what is owed. The last two are
   * best-effort: a tenant that may read its wallet but not its arrears still has a wallet to see.
   */
  private async readOverview(tenantId: string): Promise<void> {
    const wallet = await this.api.overview(tenantId);
    const [details, arrears] = await Promise.all([
      wallet.bankTransferAvailable
        ? this.api.paymentDetails(tenantId).catch(() => null)
        : Promise.resolve(null),
      this.commercial.arrears(tenantId).catch(() => null),
    ]);
    this.wallet.set(wallet);
    this.paymentDetails.set(details?.configured ? details : null);
    this.arrears.set(arrears);
  }

  /** A panel changed something: re-read the balances first, then tell every panel to read again. */
  protected async onChanged(): Promise<void> {
    const tenantId = this.tenantId();
    if (tenantId === null) {
      return;
    }
    try {
      await this.readOverview(tenantId);
    } catch {
      // The panels still read again; a stale tile is better than losing the whole screen to a blip.
    }
    this.revision.update((value) => value + 1);
  }

  protected money(value: { amountMinor: number; currency: string }): string {
    return walletMoney(value, this.i18n.locale());
  }

  protected date(instant: string): string {
    return walletDate(instant);
  }
}
