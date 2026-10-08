import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { describeWalletError } from './wallet-errors';
import { walletDate, walletMoney } from './wallet-format';
import { PrepaymentInvoiceView, WalletApi } from './wallet-api';

const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  OPEN: 'finance.wallet.invoices.status.OPEN',
  PARTIALLY_PAID: 'finance.wallet.invoices.status.PARTIALLY_PAID',
  PAID: 'finance.wallet.invoices.status.PAID',
  EXPIRED: 'finance.wallet.invoices.status.EXPIRED',
  CANCELLED: 'finance.wallet.invoices.status.CANCELLED',
};

/**
 * The tenant's prepayment invoices (ADR 0095): requests for payment of money to be held in the wallet.
 *
 * What an invoice has been paid is the ledger's word, read by the server and shown here as `paid` and
 * `due`; the status (open, part paid, paid, expired, cancelled) is read, never stored, so this screen
 * has nothing to keep in step. An invoice is frozen at issue with the bank details of that moment —
 * which is why the details shown under a row are the invoice's own and not today's.
 *
 * It is a request for payment and not a tax invoice (decision 7): every figure is before tax, and the
 * panel says so beside the table rather than leaving it to an accountant to ask.
 *
 * Only an invoice nothing has paid and that is still open is offered "Withdraw"; the server refuses one
 * with money against it either way (`INVOICE_HAS_PAYMENTS`).
 */
@Component({
  selector: 'q-wallet-invoices-panel',
  imports: [TPipe, ConfirmDialog],
  templateUrl: './wallet-invoices-panel.html',
  styleUrl: './wallet.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletInvoicesPanel {
  private readonly api = inject(WalletApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  /** Bumped by the page whenever anything changed, so the list is read again. */
  readonly revision = input(0);
  readonly changed = output<void>();

  protected readonly invoices = signal<readonly PrepaymentInvoiceView[]>([]);
  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly open = signal<string | null>(null);
  protected readonly downloading = signal<string | null>(null);
  protected readonly cancelTarget = signal<PrepaymentInvoiceView | null>(null);
  protected readonly cancelling = signal(false);

  constructor() {
    effect(() => {
      this.revision();
      const tenantId = this.tenantId();
      untracked(() => void this.load(tenantId));
    });
  }

  private async load(tenantId: string): Promise<void> {
    try {
      this.invoices.set(await this.api.invoices(tenantId));
      this.loadError.set(null);
    } catch (failure) {
      this.loadError.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.loaded.set(true);
    }
  }

  protected money(value: { amountMinor: number; currency: string }): string {
    return walletMoney(value, this.i18n.locale());
  }

  protected date(instant: string | null): string {
    return walletDate(instant);
  }

  protected statusLabel(status: string): string {
    const key = STATUS_KEYS[status];
    return key ? this.i18n.t(key) : status;
  }

  protected statusClass(status: string): string {
    switch (status) {
      case 'PAID':
        return 'status-badge--good';
      case 'OPEN':
      case 'PARTIALLY_PAID':
        return 'status-badge--warn';
      case 'EXPIRED':
      case 'CANCELLED':
        return 'status-badge--bad';
      default:
        return '';
    }
  }

  /** Only an open invoice with nothing paid against it can be withdrawn. */
  protected canWithdraw(invoice: PrepaymentInvoiceView): boolean {
    return invoice.status === 'OPEN' && invoice.paid.amountMinor === 0;
  }

  protected toggle(invoice: PrepaymentInvoiceView): void {
    this.open.set(this.open() === invoice.invoiceId ? null : invoice.invoiceId);
  }

  protected async download(invoice: PrepaymentInvoiceView): Promise<void> {
    if (this.downloading() !== null) {
      return;
    }
    this.actionError.set(null);
    this.downloading.set(invoice.invoiceId);
    try {
      const csv = await this.api.invoiceExport(this.tenantId(), invoice.invoiceId);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `invoice-${invoice.number}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (failure) {
      this.actionError.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.downloading.set(null);
    }
  }

  protected askWithdraw(invoice: PrepaymentInvoiceView): void {
    if (this.canWithdraw(invoice) && !this.cancelling()) {
      this.actionError.set(null);
      this.cancelTarget.set(invoice);
    }
  }

  protected dismissWithdraw(): void {
    if (!this.cancelling()) {
      this.cancelTarget.set(null);
    }
  }

  protected withdrawBody(invoice: PrepaymentInvoiceView): string {
    return this.i18n.t('finance.wallet.invoices.cancel.body', { number: invoice.number });
  }

  protected async withdraw(): Promise<void> {
    const invoice = this.cancelTarget();
    if (invoice === null || this.cancelling()) {
      return;
    }
    this.cancelling.set(true);
    try {
      await this.api.cancelInvoice(this.tenantId(), invoice.invoiceId);
      this.cancelTarget.set(null);
      this.changed.emit();
    } catch (failure) {
      this.cancelTarget.set(null);
      this.actionError.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
      // The list may be stale (somebody paid it meanwhile): read it again so no dead button stays.
      await this.load(this.tenantId());
    } finally {
      this.cancelling.set(false);
    }
  }
}
