import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { financePaths } from '../../../core/api/finance-paths';
import { IdempotencyKey, newIdempotencyKey } from '../../../core/api/idempotency';
import { Page, firstPage } from '../../../core/api/page';
import { Money } from '../../../core/format/money';

/** Mirrors `OperationsPaymentController.PaymentIntentResponse`. */
export interface PaymentIntentView {
  readonly intentId: string;
  readonly tender: 'CASH' | 'PROVIDER';
  readonly method: 'CASH' | 'CLICK' | 'PAYME' | 'TELEGRAM' | 'MARKETPLACE';
  readonly providerType: string | null;
  readonly amount: Money;
  readonly status: 'PENDING' | 'AUTHORIZING' | 'PAID' | 'CANCELLED' | 'EXPIRED' | 'FAILED';
  readonly createdAt: string;
  readonly settledAt: string | null;
}

/** Mirrors `OperationsPaymentController.PaymentAttemptResponse`. */
export interface PaymentAttemptView {
  readonly attemptId: string;
  readonly providerType: string;
  readonly status: string;
  readonly presentationKind: string | null;
  readonly amount: Money;
  readonly live: boolean;
  readonly createdAt: string;
  readonly settledAt: string | null;
}

/** Mirrors `OperationsPaymentController.OrderPaymentResponse`. */
export interface OrderPaymentView {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly orderStatus: string;
  readonly orderTotal: Money;
  readonly intent: PaymentIntentView | null;
  readonly attempts: readonly PaymentAttemptView[];
  readonly captured: Money | null;
  readonly returned: Money | null;
}

/** Mirrors `OperationsPaymentController.PaymentSessionResponse`. */
export interface PaymentSessionView {
  readonly attemptId: string;
  readonly merchantTransId: string;
  readonly provider: string;
  readonly presentation: string;
  readonly checkoutUrl: string | null;
  readonly qrPayload: string | null;
  readonly expiresAt: string | null;
  readonly amountMinor: number;
  readonly currency: string;
  readonly rePresented: boolean;
  readonly presentationCount: number;
}

export type ExecutionChannel = 'PROVIDER_CONSOLE' | 'CASH_DRAWER' | 'BANK_TRANSFER';
export type RemedyType = 'ORDER_REFUND' | 'DELIVERY_FEE_REIMBURSEMENT' | 'FUTURE_DISCOUNT';
export type VerificationState = 'UNVERIFIED' | 'CONFIRMED' | 'DISPUTED';
export type SettlementBasis = 'OPERATOR_ATTESTED' | 'PLATFORM_SETTLED' | 'MIXED' | 'NOT_MONEY';

/** Mirrors `OperationsRemedyController.RemedyResponse`. */
export interface RemedyView {
  readonly approvalStatus: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'DECLINED';
  readonly approvalRequestId: string | null;
  readonly remedyId: string | null;
  readonly remedyType: RemedyType | null;
  readonly orderId: string | null;
  readonly amount: Money | null;
  readonly attestedMoney: Money | null;
  readonly platformSettledMoney: Money | null;
  readonly settlementBasis: SettlementBasis | null;
  readonly verificationState: VerificationState | null;
  readonly executionChannel: ExecutionChannel | null;
  readonly providerReference: string | null;
  readonly executedBy: string | null;
  readonly executedAt: string | null;
  readonly recordedBy: string | null;
  readonly recordedAt: string | null;
  readonly deliveryFeeBasis: Money | null;
}

/** Mirrors `OperationsRemedyController.RemedyTotalsResponse`. */
export interface RemedyTotalsView {
  readonly remedyType: RemedyType;
  readonly remedyCount: number;
  readonly amount: Money;
  readonly attestedMoney: Money;
  readonly platformSettledMoney: Money;
  readonly unverified: Money;
}

export interface RefundRequestInput {
  readonly amountMinor: number;
  readonly currency: string;
  readonly reasonCode: string;
  readonly reason: string;
  readonly channel: ExecutionChannel;
  readonly providerReference?: string;
  readonly executedBy?: string;
  readonly executedAt?: string;
  readonly correlationId?: string;
}

export interface RePresentationInput {
  readonly presentation?: 'PAYMENT_LINK' | 'INVOICE_PUSH';
  readonly language?: string;
  readonly pushRecipient?: string;
}

export interface VerificationInput {
  readonly state: VerificationState;
  readonly source: string;
  readonly reason: string;
  readonly correlationId?: string;
}

/**
 * 8.1 Payments & settlements: one order's payment picture, re-issuing its
 * checkout surface, and the refund/delivery-fee remedies ADR 0048 defines.
 * (Future-discount grants are ADR 0013's entitlement machinery, a different
 * shape again, and are not owned by this screen's IA bullet list — see
 * `operations-spec/finance.md`.)
 */
@Injectable({ providedIn: 'root' })
export class PaymentsApi {
  private readonly api = inject(ApiClient);

  async orderPayment(tenantId: string, orderId: string): Promise<OrderPaymentView> {
    const result = await firstValueFrom(
      this.api.get<OrderPaymentView>(financePaths.orderPayment(tenantId, orderId)),
    );
    return result.value;
  }

  async reissuePayment(
    tenantId: string,
    orderId: string,
    input: RePresentationInput,
  ): Promise<PaymentSessionView> {
    const key = newIdempotencyKey();
    return firstValueFrom(
      this.api.post<RePresentationInput, PaymentSessionView>(
        financePaths.orderPaymentRepresentations(tenantId, orderId),
        { key, body: input },
      ),
    );
  }

  async remediesOfOrder(tenantId: string, orderId: string): Promise<readonly RemedyView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RemedyView[]>(financePaths.orderRemedies(tenantId, orderId)),
    );
    return result.value ?? [];
  }

  async recordRefund(
    tenantId: string,
    orderId: string,
    input: RefundRequestInput,
  ): Promise<RemedyView> {
    return this.submitRemedy(financePaths.orderRefunds(tenantId, orderId), input);
  }

  async reimburseDeliveryFee(
    tenantId: string,
    orderId: string,
    input: RefundRequestInput,
  ): Promise<RemedyView> {
    return this.submitRemedy(financePaths.orderDeliveryFeeReimbursements(tenantId, orderId), input);
  }

  /**
   * `remediesUnverified` and `remedyReport` share this page's fixed size —
   * the worklist is a triage list, not a paginated report, so there is no
   * "next page" affordance to wire yet.
   */
  async unverifiedRemedies(tenantId: string, settlingHours?: number): Promise<Page<RemedyView>> {
    return firstValueFrom(
      this.api.page<RemedyView>(financePaths.remediesUnverified(tenantId), firstPage(50), {
        settlingHours,
      }),
    );
  }

  async remedyTotals(
    tenantId: string,
    from: string,
    to: string,
  ): Promise<readonly RemedyTotalsView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RemedyTotalsView[]>(financePaths.remedyReport(tenantId), {
        params: { from, to },
      }),
    );
    return result.value ?? [];
  }

  async verifyRemedy(
    tenantId: string,
    remedyId: string,
    input: VerificationInput,
  ): Promise<{ recorded: boolean }> {
    const key = newIdempotencyKey();
    return firstValueFrom(
      this.api.post<VerificationInput, { recorded: boolean }>(
        financePaths.remedyVerification(tenantId, remedyId),
        { key, body: input },
      ),
    );
  }

  /**
   * `RefundRequest` carries its own `idempotencyKey` body field *in addition*
   * to the `Idempotency-Key` header every mutating call sends
   * (`OperationsRemedyController`'s own doc: a PENDING remedy is resubmitted
   * identically once approved, and that resubmission is a second HTTP call
   * needing a domain-level key the header alone cannot carry across it). One
   * minted value serves both, so the two can never disagree.
   */
  private async submitRemedy(path: string, input: RefundRequestInput): Promise<RemedyView> {
    const key: IdempotencyKey = newIdempotencyKey();
    return firstValueFrom(
      this.api.post<RefundRequestInput & { idempotencyKey: string }, RemedyView>(path, {
        key,
        body: { ...input, idempotencyKey: key },
      }),
    );
  }
}
