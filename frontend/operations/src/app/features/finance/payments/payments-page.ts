import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { OrderLookupApi, OrderNumberMatchView } from '../../../core/api/order-lookup-api';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import { orderStatusLabel } from '../../orders/order-status';
import { describeReissueRefusal } from '../finance-errors';
import {
  ENTITLEMENT_BENEFIT_KEYS,
  ENTITLEMENT_SCOPE_KEYS,
  PAYMENT_ATTEMPT_STATUS_KEYS,
  PAYMENT_INTENT_STATUS_KEYS,
  REFUND_REASON_CODES,
  REFUND_REASON_CODE_KEYS,
  RefundReasonCode,
  REMEDY_TYPE_KEYS,
  SETTLEMENT_BASIS_KEYS,
  VERIFICATION_STATE_KEYS,
} from '../finance-labels';
import {
  EntitlementBenefit,
  EntitlementScope,
  ExecutionChannel,
  OrderPaymentView,
  PaymentAttemptStatus,
  PaymentIntentStatus,
  PaymentSessionView,
  PaymentsApi,
  RemedyType,
  RemedyView,
  RemedyTotalsView,
  SettlementBasis,
  VerificationState,
} from './payments-api';

type RemedyKind = RemedyType;
type ReissueKind = 'PAYMENT_LINK' | 'INVOICE_PUSH';

/**
 * 8.1 Payments & settlements — `operations-spec/finance.md` §8.1.
 *
 * **What this screen is not.** IA 8.1 describes a `payment[]` array per
 * order, implying split tender — cash plus cashback plus deposit on one
 * settlement. `PaymentIntent.tenderId`'s own Javadoc says that has not
 * shipped (ADR 0046 is the open decision): every order today pays through
 * exactly one intent and one method. So this screen shows one intent, named
 * plainly, rather than an array that would always have exactly one entry —
 * see the banner below when an order has none.
 *
 * **Why an order lookup rather than a list.** No endpoint projects payment
 * status onto the order list yet (`OperationsOrderController`'s response has
 * no such field, confirmed against the controller source, not assumed from
 * the spec) — adding one is Orders' surface to grow, not Finance's to reach
 * into. This screen instead does what `OperationsRemedyController`'s own
 * shape already supports well: one order's payment and remedies, plus the
 * two things that are genuinely cross-order — the unverified-attestation
 * worklist and the remedy totals report.
 */
@Component({
  selector: 'q-payments-page',
  imports: [TPipe],
  templateUrl: './payments-page.html',
  styleUrl: './payments-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(PaymentsApi);
  private readonly orderLookup = inject(OrderLookupApi);
  protected readonly i18n = inject(I18n);

  protected readonly reasonCodes = REFUND_REASON_CODES;
  protected readonly entitlementScopes: readonly EntitlementScope[] = [
    'SUBTOTAL',
    'DELIVERY_FEE',
    'BOTH',
  ];
  protected readonly entitlementBenefits: readonly EntitlementBenefit[] = [
    'PERCENT',
    'FIXED_AMOUNT',
  ];

  // -------------------------------------------------------------- order lookup by number
  protected readonly orderNumberInput = signal('');
  protected readonly lookupByNumberLoading = signal(false);
  protected readonly lookupByNumberError = signal<string | null>(null);
  protected readonly lookupCandidates = signal<readonly OrderNumberMatchView[]>([]);

  // -------------------------------------------------------------- order lookup
  protected readonly orderIdInput = signal('');
  protected readonly loadingOrder = signal(false);
  protected readonly orderDenied = signal(false);
  protected readonly orderError = signal<string | null>(null);
  protected readonly payment = signal<OrderPaymentView | null>(null);
  protected readonly remedies = signal<readonly RemedyView[]>([]);

  // -------------------------------------------------------------- reissue
  protected readonly showReissueForm = signal(false);
  protected readonly reissueKind = signal<ReissueKind>('PAYMENT_LINK');
  protected readonly reissuePhone = signal('');
  protected readonly reissueSubmitting = signal(false);
  protected readonly reissueError = signal<string | null>(null);
  protected readonly reissueResult = signal<PaymentSessionView | null>(null);

  // -------------------------------------------------------------- remedy (refund / delivery fee / future discount)
  protected readonly showRemedyForm = signal(false);
  protected readonly remedyKind = signal<RemedyKind>('ORDER_REFUND');
  protected readonly remedyAmount = signal('');
  protected readonly remedyReasonCode = signal('');
  protected readonly remedyReason = signal('');
  protected readonly remedyChannel = signal<ExecutionChannel>('CASH_DRAWER');
  protected readonly remedyProviderReference = signal('');
  protected readonly remedySubmitting = signal(false);
  protected readonly remedyError = signal<string | null>(null);

  // ---------------------------------------------------- future-discount fields (RemedyKind = FUTURE_DISCOUNT)
  protected readonly discountAppliesTo = signal<EntitlementScope>('SUBTOTAL');
  protected readonly discountBenefit = signal<EntitlementBenefit>('PERCENT');
  protected readonly discountPercentBasisPoints = signal('');
  protected readonly discountAmount = signal('');
  protected readonly discountMaximum = signal('');
  protected readonly discountUses = signal('1');
  protected readonly discountValidForDays = signal('30');

  // -------------------------------------------------------------- unverified worklist
  protected readonly unverifiedLoading = signal(true);
  protected readonly unverifiedDenied = signal(false);
  protected readonly unverifiedError = signal<string | null>(null);
  protected readonly unverified = signal<readonly RemedyView[]>([]);
  protected readonly verifyingRemedyId = signal<string | null>(null);
  protected readonly verifyReason = signal('');
  protected readonly verifySource = signal('');
  protected readonly verifySubmitting = signal(false);
  protected readonly verifyError = signal<string | null>(null);

  // -------------------------------------------------------------- remedy totals
  protected readonly totalsLoading = signal(false);
  protected readonly totalsError = signal<string | null>(null);
  protected readonly totals = signal<readonly RemedyTotalsView[]>([]);
  protected readonly totalsFrom = signal(isoDaysAgo(30));
  protected readonly totalsTo = signal(isoNow());

  constructor() {
    void this.init();
  }

  private async init(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.unverifiedLoading.set(false);
      this.unverifiedDenied.set(this.tenant.denied());
      return;
    }
    await Promise.all([this.loadUnverified(tenantId), this.loadTotals(tenantId)]);
  }

  // -------------------------------------------------------------- order lookup by number

  protected canLookupByNumber(): boolean {
    return this.orderNumberInput().trim().length > 0 && !this.lookupByNumberLoading();
  }

  /**
   * The thing an operator actually has -- a public order number, from a
   * receipt or a call -- resolved to the order id `lookup()` still needs.
   * `uq_order_number` is per-location, not per-tenant, so more than one
   * candidate is a real answer, not a bug: a two-branch tenant can hand out
   * the same number to two customers the same afternoon.
   */
  protected async lookupByNumber(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const number = this.orderNumberInput().trim();
    if (!tenantId || !number) {
      return;
    }
    this.lookupByNumberLoading.set(true);
    this.lookupByNumberError.set(null);
    this.lookupCandidates.set([]);
    try {
      const matches = await this.orderLookup.byNumber(tenantId, number);
      if (matches.length === 1) {
        this.orderIdInput.set(matches[0].orderId);
        await this.lookup();
      } else {
        this.lookupCandidates.set(matches);
      }
    } catch (error) {
      this.lookupByNumberError.set(this.describe(error));
    } finally {
      this.lookupByNumberLoading.set(false);
    }
  }

  protected async pickCandidate(candidate: OrderNumberMatchView): Promise<void> {
    this.lookupCandidates.set([]);
    this.orderIdInput.set(candidate.orderId);
    await this.lookup();
  }

  // -------------------------------------------------------------- order lookup

  protected canLookup(): boolean {
    return this.orderIdInput().trim().length > 0 && !this.loadingOrder();
  }

  protected async lookup(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const orderId = this.orderIdInput().trim();
    if (!tenantId || !orderId) {
      return;
    }
    this.loadingOrder.set(true);
    this.orderError.set(null);
    this.payment.set(null);
    this.remedies.set([]);
    this.resetReissueForm();
    this.resetRemedyForm();
    try {
      const [payment, remedies] = await Promise.all([
        this.api.orderPayment(tenantId, orderId),
        this.api.remediesOfOrder(tenantId, orderId),
      ]);
      this.payment.set(payment);
      this.remedies.set(remedies);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.orderDenied.set(true);
      } else {
        this.orderError.set(this.describe(error));
      }
    } finally {
      this.loadingOrder.set(false);
    }
  }

  private async refreshOrder(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const order = this.payment();
    if (!tenantId || !order) {
      return;
    }
    const [payment, remedies] = await Promise.all([
      this.api.orderPayment(tenantId, order.orderId),
      this.api.remediesOfOrder(tenantId, order.orderId),
    ]);
    this.payment.set(payment);
    this.remedies.set(remedies);
  }

  // -------------------------------------------------------------- reissue

  protected canReissue(): boolean {
    if (this.reissueSubmitting()) {
      return false;
    }
    return this.reissueKind() === 'PAYMENT_LINK' || /^998\d{9}$/.test(this.reissuePhone().trim());
  }

  protected async submitReissue(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const order = this.payment();
    if (!tenantId || !order || !this.canReissue()) {
      return;
    }
    this.reissueSubmitting.set(true);
    this.reissueError.set(null);
    this.reissueResult.set(null);
    try {
      const result = await this.api.reissuePayment(tenantId, order.orderId, {
        presentation: this.reissueKind(),
        pushRecipient:
          this.reissueKind() === 'INVOICE_PUSH' ? this.reissuePhone().trim() : undefined,
      });
      this.reissueResult.set(result);
    } catch (error) {
      if (error instanceof ApiError) {
        this.reissueError.set(
          describeReissueRefusal(error, (key, values) => this.i18n.t(key, values)),
        );
      } else {
        this.reissueError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.reissueSubmitting.set(false);
    }
  }

  private resetReissueForm(): void {
    this.showReissueForm.set(false);
    this.reissueKind.set('PAYMENT_LINK');
    this.reissuePhone.set('');
    this.reissueError.set(null);
    this.reissueResult.set(null);
  }

  // -------------------------------------------------------------- remedy

  protected canSubmitRemedy(): boolean {
    if (
      this.remedySubmitting() ||
      this.remedyReasonCode().trim().length === 0 ||
      this.remedyReason().trim().length === 0
    ) {
      return false;
    }
    if (this.remedyKind() === 'FUTURE_DISCOUNT') {
      return this.canSubmitFutureDiscount();
    }
    const amount = Number(this.remedyAmount());
    return (
      Number.isInteger(amount) &&
      amount > 0 &&
      (this.remedyChannel() !== 'PROVIDER_CONSOLE' ||
        this.remedyProviderReference().trim().length > 0)
    );
  }

  private canSubmitFutureDiscount(): boolean {
    const uses = Number(this.discountUses());
    const validForDays = Number(this.discountValidForDays());
    if (
      !Number.isInteger(uses) ||
      uses < 1 ||
      !Number.isInteger(validForDays) ||
      validForDays < 1
    ) {
      return false;
    }
    if (this.discountBenefit() === 'PERCENT') {
      const percent = Number(this.discountPercentBasisPoints());
      const max = Number(this.discountMaximum());
      return Number.isInteger(percent) && percent > 0 && Number.isInteger(max) && max > 0;
    }
    const amount = Number(this.discountAmount());
    return Number.isInteger(amount) && amount > 0;
  }

  protected async submitRemedy(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const order = this.payment();
    if (!tenantId || !order || !this.canSubmitRemedy()) {
      return;
    }
    this.remedySubmitting.set(true);
    this.remedyError.set(null);
    try {
      const outcome = await this.submitRemedyOfKind(tenantId, order);
      if (outcome.approvalStatus === 'PENDING') {
        this.remedyError.set(this.i18n.t('finance.payments.remedy.pending'));
      } else {
        this.resetRemedyForm();
      }
      await this.refreshOrder();
      const tenantForTotals = this.tenant.tenantId();
      if (tenantForTotals) {
        await this.loadTotals(tenantForTotals);
      }
    } catch (error) {
      this.remedyError.set(this.describe(error));
    } finally {
      this.remedySubmitting.set(false);
    }
  }

  private async submitRemedyOfKind(tenantId: string, order: OrderPaymentView): Promise<RemedyView> {
    const reasonCode = this.remedyReasonCode().trim();
    const reason = this.remedyReason().trim();
    if (this.remedyKind() === 'FUTURE_DISCOUNT') {
      const benefit = this.discountBenefit();
      return this.api.grantFutureDiscount(tenantId, order.orderId, {
        appliesTo: this.discountAppliesTo(),
        benefit,
        percentBasisPoints:
          benefit === 'PERCENT' ? Number(this.discountPercentBasisPoints()) : undefined,
        maximumMinor: benefit === 'PERCENT' ? Number(this.discountMaximum()) : undefined,
        amountMinor: benefit === 'FIXED_AMOUNT' ? Number(this.discountAmount()) : undefined,
        uses: Number(this.discountUses()),
        validForDays: Number(this.discountValidForDays()),
        reasonCode,
        reason,
      });
    }
    const input = {
      amountMinor: Number(this.remedyAmount()),
      currency: order.orderTotal.currency,
      reasonCode,
      reason,
      channel: this.remedyChannel(),
      providerReference: this.remedyProviderReference().trim() || undefined,
    };
    return this.remedyKind() === 'ORDER_REFUND'
      ? this.api.recordRefund(tenantId, order.orderId, input)
      : this.api.reimburseDeliveryFee(tenantId, order.orderId, input);
  }

  private resetRemedyForm(): void {
    this.showRemedyForm.set(false);
    this.remedyKind.set('ORDER_REFUND');
    this.remedyAmount.set('');
    this.remedyReasonCode.set('');
    this.remedyReason.set('');
    this.remedyChannel.set('CASH_DRAWER');
    this.remedyProviderReference.set('');
    this.remedyError.set(null);
    this.discountAppliesTo.set('SUBTOTAL');
    this.discountBenefit.set('PERCENT');
    this.discountPercentBasisPoints.set('');
    this.discountAmount.set('');
    this.discountMaximum.set('');
    this.discountUses.set('1');
    this.discountValidForDays.set('30');
  }

  // -------------------------------------------------------------- unverified worklist

  private async loadUnverified(tenantId: string): Promise<void> {
    this.unverifiedLoading.set(true);
    this.unverifiedError.set(null);
    try {
      const page = await this.api.unverifiedRemedies(tenantId);
      this.unverified.set(page.items);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.unverifiedDenied.set(true);
      } else {
        this.unverifiedError.set(this.describe(error));
      }
    } finally {
      this.unverifiedLoading.set(false);
    }
  }

  protected startVerify(remedy: RemedyView): void {
    if (!remedy.remedyId) {
      return;
    }
    this.verifyingRemedyId.set(remedy.remedyId);
    this.verifyReason.set('');
    this.verifySource.set('');
    this.verifyError.set(null);
  }

  protected canSubmitVerification(): boolean {
    return (
      !this.verifySubmitting() &&
      this.verifySource().trim().length > 0 &&
      this.verifyReason().trim().length > 0
    );
  }

  protected async submitVerification(state: 'CONFIRMED' | 'DISPUTED'): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const remedyId = this.verifyingRemedyId();
    if (!tenantId || !remedyId || !this.canSubmitVerification()) {
      return;
    }
    this.verifySubmitting.set(true);
    this.verifyError.set(null);
    try {
      await this.api.verifyRemedy(tenantId, remedyId, {
        state,
        source: this.verifySource().trim(),
        reason: this.verifyReason().trim(),
      });
      this.verifyingRemedyId.set(null);
      await this.loadUnverified(tenantId);
    } catch (error) {
      this.verifyError.set(this.describe(error));
    } finally {
      this.verifySubmitting.set(false);
    }
  }

  // -------------------------------------------------------------- totals

  protected async reloadTotals(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      await this.loadTotals(tenantId);
    }
  }

  private async loadTotals(tenantId: string): Promise<void> {
    this.totalsLoading.set(true);
    this.totalsError.set(null);
    try {
      const totals = await this.api.remedyTotals(
        tenantId,
        new Date(this.totalsFrom()).toISOString(),
        new Date(this.totalsTo()).toISOString(),
      );
      this.totals.set(totals);
    } catch (error) {
      this.totalsError.set(this.describe(error));
    } finally {
      this.totalsLoading.set(false);
    }
  }

  // -------------------------------------------------------------- formatting

  protected money(value: { amountMinor: number; currency: string } | null): string {
    if (!value) {
      return '—';
    }
    return formatMoney(value, this.i18n.locale(), { withUnit: true });
  }

  protected remedyTypeLabel(type: RemedyType | null): string {
    return type ? this.i18n.t(REMEDY_TYPE_KEYS[type]) : '—';
  }

  protected settlementBasisLabel(basis: SettlementBasis | null): string {
    return basis ? this.i18n.t(SETTLEMENT_BASIS_KEYS[basis]) : '—';
  }

  protected verificationStateLabel(state: VerificationState | null): string {
    return state ? this.i18n.t(VERIFICATION_STATE_KEYS[state]) : '—';
  }

  /** Reuses `orders/order-status.ts`'s own twelve-value map — one order status, one label, everywhere. */
  protected orderStatusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected intentStatusLabel(status: PaymentIntentStatus): string {
    return this.i18n.t(PAYMENT_INTENT_STATUS_KEYS[status]);
  }

  protected attemptStatusLabel(status: PaymentAttemptStatus): string {
    return this.i18n.t(PAYMENT_ATTEMPT_STATUS_KEYS[status]);
  }

  /**
   * A remedy recorded before this wave may carry any free-typed string, so
   * this falls back to the raw code rather than assuming every reasonCode on
   * a historical row is one of the fixed vocabulary's ten.
   */
  protected reasonCodeLabel(code: string): string {
    return isRefundReasonCode(code) ? this.i18n.t(REFUND_REASON_CODE_KEYS[code]) : code;
  }

  protected entitlementScopeLabel(scope: EntitlementScope): string {
    return this.i18n.t(ENTITLEMENT_SCOPE_KEYS[scope]);
  }

  protected entitlementBenefitLabel(benefit: EntitlementBenefit): string {
    return this.i18n.t(ENTITLEMENT_BENEFIT_KEYS[benefit]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

const KNOWN_REASON_CODES: ReadonlySet<string> = new Set(REFUND_REASON_CODES);

function isRefundReasonCode(value: string): value is RefundReasonCode {
  return KNOWN_REASON_CODES.has(value);
}

function isoNow(): string {
  return new Date().toISOString().slice(0, 10);
}

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}
