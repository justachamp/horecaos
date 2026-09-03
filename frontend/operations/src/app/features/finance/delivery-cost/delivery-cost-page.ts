import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import {
  CostBasis,
  CourierFinanceApi,
  DeliveryCostReport,
  PartnerInvoiceDetailView,
  PartnerInvoiceView,
} from '../courier-finance-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const BASIS_KEYS: Readonly<Record<CostBasis, MessageKey>> = {
  ACCRUED: 'finance.deliveryCost.basis.ACCRUED',
  INVOICED: 'finance.deliveryCost.basis.INVOICED',
  SETTLED: 'finance.deliveryCost.basis.SETTLED',
};

const INVOICE_STATUS_KEYS: Readonly<Record<PartnerInvoiceView['status'], MessageKey>> = {
  IMPORTED: 'finance.deliveryCost.invoiceStatus.IMPORTED',
  MATCHED: 'finance.deliveryCost.invoiceStatus.MATCHED',
  DISPUTED: 'finance.deliveryCost.invoiceStatus.DISPUTED',
  PAID: 'finance.deliveryCost.invoiceStatus.PAID',
};

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}

function isoToday(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * 8.4 Delivery cost reconciliation (`frontend-information-architecture.md`
 * §8.4) — tier 2. "Provider invoices vs. recorded per-delivery cost —
 * charged-vs-cost delivery margin; provider terminal status."
 *
 * Both reads are ADR 0042's own: `DeliveryCostQueryService.report` (built
 * before this wave) for the basis-scoped two-line total, and
 * `JdbcDeliveryCostStore.listInvoices`/`.linesOfInvoice` (new this wave) for
 * the partner-invoice worklist and its per-line match state. Import and
 * match — `PartnerInvoiceService.importInvoice`/`.match` — are real,
 * multi-line operator workflows this wave's UI does not build a form for;
 * the read surface (this screen) is what a finance operator needs to see the
 * exposure, and the two write endpoints exist for whoever automates the
 * import from a provider's own settlement file.
 */
@Component({
  selector: 'q-delivery-cost-page',
  imports: [TPipe],
  templateUrl: './delivery-cost-page.html',
  styleUrl: './delivery-cost-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryCostPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(CourierFinanceApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly basis = signal<CostBasis>('ACCRUED');
  protected readonly from = signal(isoDaysAgo(30));
  protected readonly to = signal(isoToday());
  protected readonly report = signal<DeliveryCostReport | null>(null);

  protected readonly invoices = signal<readonly PartnerInvoiceView[]>([]);
  protected readonly openInvoiceId = signal<string | null>(null);
  protected readonly openInvoiceDetail = signal<PartnerInvoiceDetailView | null>(null);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  protected onBasisChange(value: string): void {
    this.basis.set(value as CostBasis);
    void this.loadReport();
  }

  protected async reloadRange(): Promise<void> {
    await this.loadReport();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      await Promise.all([this.loadReport(), this.loadInvoices(tenantId)]);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  private async loadReport(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.report.set(await this.api.deliveryCosts(tenantId, this.basis(), this.from(), this.to()));
  }

  private async loadInvoices(tenantId: string): Promise<void> {
    this.invoices.set(await this.api.partnerInvoices(tenantId));
  }

  protected async toggleInvoice(invoice: PartnerInvoiceView): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    if (this.openInvoiceId() === invoice.invoiceId) {
      this.openInvoiceId.set(null);
      this.openInvoiceDetail.set(null);
      return;
    }
    this.openInvoiceId.set(invoice.invoiceId);
    this.openInvoiceDetail.set(null);
    try {
      this.openInvoiceDetail.set(await this.api.partnerInvoiceDetail(tenantId, invoice.invoiceId));
    } catch {
      // The row itself already loaded; a failed detail fetch just leaves the panel empty.
    }
  }

  protected money(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected basisLabel(basis: CostBasis): string {
    return this.i18n.t(BASIS_KEYS[basis]);
  }

  protected invoiceStatusLabel(status: PartnerInvoiceView['status']): string {
    return this.i18n.t(INVOICE_STATUS_KEYS[status]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
