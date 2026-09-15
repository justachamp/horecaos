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
  ImportInvoiceLine,
  MatchStatus,
  PartnerChargeType,
  PartnerInvoiceDetailView,
  PartnerInvoiceView,
  VarianceResolution,
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

/** `MatchStatus.java` — wave T07: the raw enum used to print unlocalized. */
const MATCH_STATUS_KEYS: Readonly<Record<MatchStatus, MessageKey>> = {
  PENDING: 'finance.deliveryCost.matchStatus.PENDING',
  MATCHED: 'finance.deliveryCost.matchStatus.MATCHED',
  VARIANCE: 'finance.deliveryCost.matchStatus.VARIANCE',
  UNBILLED: 'finance.deliveryCost.matchStatus.UNBILLED',
  UNMATCHED_LINE: 'finance.deliveryCost.matchStatus.UNMATCHED_LINE',
};

const CHARGE_TYPE_KEYS: Readonly<Record<PartnerChargeType, MessageKey>> = {
  DELIVERY: 'finance.deliveryCost.chargeType.DELIVERY',
  CANCELLATION: 'finance.deliveryCost.chargeType.CANCELLATION',
  WAITING: 'finance.deliveryCost.chargeType.WAITING',
  SURCHARGE: 'finance.deliveryCost.chargeType.SURCHARGE',
  ADJUSTMENT: 'finance.deliveryCost.chargeType.ADJUSTMENT',
};

const VARIANCE_RESOLUTION_KEYS: Readonly<Record<VarianceResolution, MessageKey>> = {
  ACCEPTED: 'finance.deliveryCost.varianceResolution.ACCEPTED',
  DISPUTED: 'finance.deliveryCost.varianceResolution.DISPUTED',
};

interface ImportLineDraft {
  readonly domId: string;
  providerShipmentRef: string;
  amountMinor: string;
  chargeType: PartnerChargeType;
}

/** What one row's inline action is doing right now. */
type RowActionKind =
  'resolve-unmatched' | 'accept-variance' | 'dispute-variance' | 'dispute-invoice';

let nextDraftId = 0;

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
 * Both reads are ADR 0042's own: `DeliveryCostQueryService.report` for the
 * basis-scoped two-line total, and `JdbcDeliveryCostStore.listInvoices`/
 * `.linesOfInvoice` for the partner-invoice worklist and its per-line match
 * state. Import, match, dispute and variance resolution — this wave's own —
 * are what turns the read-only exposure into an акт сверки workflow: an
 * operator can now load Yandex's or Noor's monthly file, resolve an
 * `UNMATCHED_LINE` by re-matching with the correct reference (`match` only
 * reprocesses `PENDING`/`UNMATCHED_LINE` lines — see `PartnerInvoiceService
 * .match`'s own doc — so a second, narrower call is exactly the resolution
 * path), and accept or dispute a `VARIANCE`.
 *
 * The charged-vs-cost margin needs no new backend read: `ACCRUED` (what
 * booking predicted the partner would cost) and `INVOICED` (what the partner
 * actually charged) are both the same `deliveryCosts` call at a different
 * basis, over the same range — the margin is their difference.
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

  /** Charged (INVOICED) vs. cost (ACCRUED) partner totals over the same range. */
  protected readonly marginAccruedMinor = signal<number | null>(null);
  protected readonly marginInvoicedMinor = signal<number | null>(null);

  protected readonly invoices = signal<readonly PartnerInvoiceView[]>([]);
  protected readonly openInvoiceId = signal<string | null>(null);
  protected readonly openInvoiceDetail = signal<PartnerInvoiceDetailView | null>(null);

  // ------------------------------------------------------------- import form

  protected readonly importOpen = signal(false);
  protected readonly importProviderCode = signal('');
  protected readonly importInvoiceRef = signal('');
  protected readonly importPeriodStart = signal(isoDaysAgo(30));
  protected readonly importPeriodEnd = signal(isoToday());
  protected readonly importCurrency = signal('UZS');
  protected readonly importTotalMinor = signal('');
  protected readonly importLines = signal<ImportLineDraft[]>([newImportLine()]);
  protected readonly importReason = signal('');
  protected readonly importBusy = signal(false);
  protected readonly importError = signal<string | null>(null);

  // ------------------------------------------------------- per-row actions

  protected readonly actionKind = signal<RowActionKind | null>(null);
  protected readonly actionInvoiceId = signal<string | null>(null);
  protected readonly actionLineId = signal<string | null>(null);
  protected readonly actionShipmentId = signal('');
  protected readonly actionReason = signal('');
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);

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
    await Promise.all([this.loadReport(), this.loadMargin()]);
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
      await Promise.all([this.loadReport(), this.loadInvoices(tenantId), this.loadMargin()]);
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

  private async loadMargin(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    const [accrued, invoiced] = await Promise.all([
      this.api.deliveryCosts(tenantId, 'ACCRUED', this.from(), this.to()),
      this.api.deliveryCosts(tenantId, 'INVOICED', this.from(), this.to()),
    ]);
    this.marginAccruedMinor.set(accrued.partnerMinor);
    this.marginInvoicedMinor.set(invoiced.partnerMinor);
  }

  protected marginMinor(): number | null {
    const accrued = this.marginAccruedMinor();
    const invoiced = this.marginInvoicedMinor();
    return accrued === null || invoiced === null ? null : invoiced - accrued;
  }

  private async loadInvoices(tenantId: string): Promise<void> {
    this.invoices.set(await this.api.partnerInvoices(tenantId));
  }

  private async reloadInvoices(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    await this.loadInvoices(tenantId);
    const openId = this.openInvoiceId();
    if (openId) {
      this.openInvoiceDetail.set(await this.api.partnerInvoiceDetail(tenantId, openId));
    }
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

  protected matchStatusLabel(status: MatchStatus): string {
    return this.i18n.t(MATCH_STATUS_KEYS[status]);
  }

  protected chargeTypeLabel(chargeType: PartnerChargeType): string {
    return this.i18n.t(CHARGE_TYPE_KEYS[chargeType]);
  }

  protected varianceResolutionLabel(resolution: VarianceResolution): string {
    return this.i18n.t(VARIANCE_RESOLUTION_KEYS[resolution]);
  }

  // ------------------------------------------------------------- import form

  protected toggleImportForm(): void {
    this.importOpen.set(!this.importOpen());
    this.importError.set(null);
  }

  protected addImportLine(): void {
    this.importLines.set([...this.importLines(), newImportLine()]);
  }

  protected removeImportLine(domId: string): void {
    const remaining = this.importLines().filter((line) => line.domId !== domId);
    this.importLines.set(remaining.length > 0 ? remaining : [newImportLine()]);
  }

  protected updateImportLine(domId: string, patch: Partial<ImportLineDraft>): void {
    this.importLines.set(
      this.importLines().map((line) => (line.domId === domId ? { ...line, ...patch } : line)),
    );
  }

  protected canSubmitImport(): boolean {
    if (this.importBusy()) {
      return false;
    }
    if (
      !this.importProviderCode().trim() ||
      !this.importInvoiceRef().trim() ||
      !this.importCurrency().trim() ||
      !this.importReason().trim()
    ) {
      return false;
    }
    if (!Number.isInteger(Number(this.importTotalMinor())) || Number(this.importTotalMinor()) < 0) {
      return false;
    }
    return this.importLines().every(
      (line) =>
        line.providerShipmentRef.trim().length > 0 &&
        Number.isInteger(Number(line.amountMinor)) &&
        Number(line.amountMinor) > 0,
    );
  }

  protected async submitImport(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.canSubmitImport()) {
      return;
    }
    this.importBusy.set(true);
    this.importError.set(null);
    try {
      const lines: readonly ImportInvoiceLine[] = this.importLines().map((line) => ({
        providerShipmentRef: line.providerShipmentRef.trim(),
        amountMinor: Number(line.amountMinor),
        chargeType: line.chargeType,
      }));
      await this.api.importInvoice(tenantId, {
        providerCode: this.importProviderCode().trim(),
        providerInvoiceRef: this.importInvoiceRef().trim(),
        periodStart: this.importPeriodStart(),
        periodEnd: this.importPeriodEnd(),
        totalMinor: Number(this.importTotalMinor()),
        currency: this.importCurrency().trim().toUpperCase(),
        lines,
        reason: this.importReason().trim(),
      });
      this.importOpen.set(false);
      this.importProviderCode.set('');
      this.importInvoiceRef.set('');
      this.importTotalMinor.set('');
      this.importLines.set([newImportLine()]);
      this.importReason.set('');
      await this.reloadInvoices();
    } catch (error) {
      this.importError.set(this.describe(error));
    } finally {
      this.importBusy.set(false);
    }
  }

  // ------------------------------------------------------- per-row actions

  protected startResolveUnmatched(invoiceId: string, lineId: string): void {
    this.actionKind.set('resolve-unmatched');
    this.actionInvoiceId.set(invoiceId);
    this.actionLineId.set(lineId);
    this.actionShipmentId.set('');
    this.actionReason.set('');
    this.actionError.set(null);
  }

  protected startVarianceResolution(invoiceId: string, lineId: string, accept: boolean): void {
    this.actionKind.set(accept ? 'accept-variance' : 'dispute-variance');
    this.actionInvoiceId.set(invoiceId);
    this.actionLineId.set(lineId);
    this.actionReason.set('');
    this.actionError.set(null);
  }

  protected startDisputeInvoice(invoiceId: string): void {
    this.actionKind.set('dispute-invoice');
    this.actionInvoiceId.set(invoiceId);
    this.actionLineId.set(null);
    this.actionReason.set('');
    this.actionError.set(null);
  }

  protected cancelAction(): void {
    this.actionKind.set(null);
    this.actionInvoiceId.set(null);
    this.actionLineId.set(null);
  }

  protected canSubmitAction(): boolean {
    if (this.actionBusy() || this.actionReason().trim().length === 0) {
      return false;
    }
    if (this.actionKind() === 'resolve-unmatched') {
      return this.actionShipmentId().trim().length > 0;
    }
    return true;
  }

  protected async submitAction(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const kind = this.actionKind();
    const invoiceId = this.actionInvoiceId();
    if (!tenantId || !kind || !invoiceId || !this.canSubmitAction()) {
      return;
    }
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      const line = this.currentActionLine();
      switch (kind) {
        case 'resolve-unmatched': {
          if (!line) {
            return;
          }
          await this.api.matchInvoice(
            tenantId,
            invoiceId,
            { [line.providerShipmentRef]: this.actionShipmentId().trim() },
            this.actionReason().trim(),
          );
          break;
        }
        case 'accept-variance':
        case 'dispute-variance': {
          const lineId = this.actionLineId();
          if (!lineId) {
            return;
          }
          await this.api.resolveVariance(
            tenantId,
            invoiceId,
            lineId,
            kind === 'accept-variance',
            this.actionReason().trim(),
          );
          break;
        }
        case 'dispute-invoice':
          await this.api.disputeInvoice(tenantId, invoiceId, this.actionReason().trim());
          break;
      }
      this.cancelAction();
      await this.reloadInvoices();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actionBusy.set(false);
    }
  }

  private currentActionLine() {
    const lineId = this.actionLineId();
    const detail = this.openInvoiceDetail();
    return detail?.lines.find((line) => line.lineId === lineId) ?? null;
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function newImportLine(): ImportLineDraft {
  return {
    domId: `import-line-${(nextDraftId += 1)}`,
    providerShipmentRef: '',
    amountMinor: '',
    chargeType: 'DELIVERY',
  };
}
