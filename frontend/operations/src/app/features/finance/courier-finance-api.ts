import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { financePaths } from '../../core/api/finance-paths';
import { command } from '../../core/api/idempotency';

// ---------------------------------------------------------------- 8.3 Cash reconciliation

/** Mirrors `OperationsCourierController.CashHandoverResponse`. */
export interface CashHandoverView {
  readonly handoverId: string;
  readonly shiftId: string;
  readonly courierId: string;
  readonly locationId: string;
  readonly status: 'PENDING' | 'DECLARED' | 'CONFIRMED' | 'VARIANCE_RAISED' | 'OVERRIDDEN';
  readonly currency: string;
  readonly expectedMinor: number;
  readonly declaredMinor: number | null;
  readonly confirmedMinor: number | null;
  readonly varianceMinor: number | null;
  readonly declaredAt: string | null;
  readonly confirmedBy: string | null;
  readonly confirmedAt: string | null;
  readonly reasonCode: string | null;
}

// ---------------------------------------------------------------- 8.4 Delivery cost reconciliation

/** `CostPath.java`: what the courier accrued, vs. what the partner (Noor, Yandex) charged. */
export type CostPath = 'INTERNAL' | 'PARTNER';

/**
 * `CostBasis.java`: a claim about a number, not a status. `INVOICED` is
 * meaningless on the `INTERNAL` path — a self-employed courier's accrual
 * becomes `SETTLED` at period close, with no invoice from HorecaOS to itself
 * in between (`CostBasis.validFor`).
 */
export type CostBasis = 'ACCRUED' | 'INVOICED' | 'SETTLED';

export interface DeliveryCostPathTotal {
  readonly costPath: CostPath;
  readonly currency: string;
  readonly totalMinor: number;
  readonly shipmentCount: number;
}

/**
 * Mirrors `DeliveryCostQueryService.CostReport`. Two lines and a total, never
 * one number, on purpose: the internal and partner paths are recognised at
 * different instants and rest on different tax documents.
 */
export interface DeliveryCostReport {
  readonly basis: CostBasis;
  readonly from: string;
  readonly to: string;
  readonly internalMinor: number;
  readonly partnerMinor: number;
  readonly totalMinor: number;
  /** Shipments carrying cost at some *other* basis — reported beside the total, never dropped. */
  readonly shipmentsWithoutThisBasis: number;
  readonly byPath: readonly DeliveryCostPathTotal[];
}

/** Mirrors `OperationsCourierController.PartnerInvoiceResponse`. */
export interface PartnerInvoiceView {
  readonly invoiceId: string;
  readonly providerCode: string;
  readonly providerInvoiceRef: string;
  readonly legalEntityId: string | null;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly totalMinor: number;
  readonly currency: string;
  readonly status: 'IMPORTED' | 'MATCHED' | 'DISPUTED' | 'PAID';
}

/** `PartnerChargeType.java`. */
export type PartnerChargeType = 'DELIVERY' | 'CANCELLATION' | 'WAITING' | 'SURCHARGE' | 'ADJUSTMENT';

/**
 * `MatchStatus.java`. `UNMATCHED_LINE` — the partner billed for a shipment
 * HorecaOS has no record of — is never netted into any total (ADR 0042).
 */
export type MatchStatus = 'PENDING' | 'MATCHED' | 'VARIANCE' | 'UNBILLED' | 'UNMATCHED_LINE';

export interface PartnerInvoiceLineView {
  readonly lineId: string;
  readonly providerShipmentRef: string;
  readonly shipmentId: string | null;
  readonly amountMinor: number;
  readonly currency: string;
  readonly chargeType: PartnerChargeType;
  readonly matchStatus: MatchStatus;
  readonly varianceMinor: number | null;
  readonly reasonCode: string | null;
}

export interface PartnerInvoiceDetailView {
  readonly invoice: PartnerInvoiceView;
  readonly lines: readonly PartnerInvoiceLineView[];
}

// ---------------------------------------------------------------- 8.5 Courier payouts

export interface CourierLedgerLineView {
  readonly entryId: string;
  readonly entryType: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly reasonCode: string | null;
  readonly occurredAt: string;
}

/** Mirrors `OperationsCourierController.LedgerResponse`. */
export interface CourierLedgerView {
  readonly balanceMinor: number;
  readonly entries: readonly CourierLedgerLineView[];
}

/** Mirrors `OperationsCourierController.SettlementPeriodResponse`. */
export interface SettlementPeriodView {
  readonly periodId: string;
  readonly courierId: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly status: 'OPEN' | 'CLOSING' | 'CLOSED' | 'SETTLED';
  readonly currency: string;
  readonly grossEarningsMinor: number;
  readonly adjustmentsMinor: number;
  readonly cashHeldMinor: number;
  readonly amountPayableMinor: number;
  readonly deliveredCount: number;
  readonly onTimeCount: number;
  readonly complianceFlag: boolean;
  readonly statementHash: string | null;
  readonly closedAt: string | null;
  readonly settledAt: string | null;
}

/** `PayoutMethod.java`. HorecaOS computes, approves and records the payout; it never moves the money itself. */
export type PayoutMethod = 'CASH_AT_BRANCH' | 'BANK_TRANSFER' | 'CARD_TRANSFER';

/**
 * Finance 8.3-8.5 — cash reconciliation, delivery cost reconciliation and
 * courier payouts — read directly over ADR 0042's built courier module
 * (`OperationsCourierController`), not over a reporting fact table. See
 * `statistics.md` §0's own split: "a payout is a money movement with an
 * approval, not a report" is why these three screens sit in Finance while
 * Reports 7.4 (blocked on `reporting.fact_delivery`) stays honest not-built.
 */
@Injectable({ providedIn: 'root' })
export class CourierFinanceApi {
  private readonly api = inject(ApiClient);

  // -------------------------------------------------------------- 8.3

  async cashHandovers(
    tenantId: string,
    filters: { readonly status?: string; readonly locationId?: string } = {},
  ): Promise<readonly CashHandoverView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CashHandoverView[]>(financePaths.cashHandovers(tenantId), {
        params: { status: filters.status, locationId: filters.locationId },
      }),
    );
    return result.value ?? [];
  }

  async confirmCash(
    tenantId: string,
    handoverId: string,
    input: { readonly confirmedMinor: number; readonly reasonCode?: string; readonly reason: string },
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<typeof input, void>(financePaths.cashHandoverConfirm(tenantId, handoverId), command(input)),
    );
  }

  // -------------------------------------------------------------- 8.4

  async deliveryCosts(
    tenantId: string,
    basis: CostBasis,
    from: string,
    to: string,
  ): Promise<DeliveryCostReport> {
    const result = await firstValueFrom(
      this.api.get<DeliveryCostReport>(financePaths.deliveryCosts(tenantId), {
        params: { basis, from, to },
      }),
    );
    return result.value;
  }

  async partnerInvoices(
    tenantId: string,
    status?: string,
  ): Promise<readonly PartnerInvoiceView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PartnerInvoiceView[]>(financePaths.partnerInvoices(tenantId), {
        params: { status },
      }),
    );
    return result.value ?? [];
  }

  async partnerInvoiceDetail(tenantId: string, invoiceId: string): Promise<PartnerInvoiceDetailView> {
    const result = await firstValueFrom(
      this.api.get<PartnerInvoiceDetailView>(financePaths.partnerInvoice(tenantId, invoiceId)),
    );
    return result.value;
  }

  // -------------------------------------------------------------- 8.5

  async courierLedger(tenantId: string, courierId: string): Promise<CourierLedgerView> {
    const result = await firstValueFrom(
      this.api.get<CourierLedgerView>(financePaths.courierLedger(tenantId, courierId)),
    );
    return result.value;
  }

  async settlementPeriods(tenantId: string, status?: string): Promise<readonly SettlementPeriodView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly SettlementPeriodView[]>(financePaths.settlementPeriods(tenantId), {
        params: { status },
      }),
    );
    return result.value ?? [];
  }

  async closeSettlementPeriod(tenantId: string, periodId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ readonly reason: string }, unknown>(
        financePaths.settlementPeriodClose(tenantId, periodId),
        command({ reason }),
      ),
    );
  }

  async authorisePayout(
    tenantId: string,
    periodId: string,
    method: PayoutMethod,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ readonly method: string; readonly reason: string }, unknown>(
        financePaths.settlementPeriodPayouts(tenantId, periodId),
        command({ method, reason }),
      ),
    );
  }
}
