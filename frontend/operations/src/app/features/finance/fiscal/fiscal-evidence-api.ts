import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { financePaths } from '../../../core/api/finance-paths';
import { newIdempotencyKey } from '../../../core/api/idempotency';
import { Money } from '../../../core/format/money';

/**
 * Why an identifier is opened in full: a closed set, never free text. Mirrors
 * `FiscalEvidenceService.Purpose` (ADR 0166, Decision 5).
 */
export const FISCAL_EVIDENCE_PURPOSES = [
  'TAX_INQUIRY',
  'CUSTOMER_COMPLAINT',
  'AUDIT',
  'CORRECTION',
] as const;

export type FiscalEvidencePurpose = (typeof FISCAL_EVIDENCE_PURPOSES)[number];

/** Mirrors `FiscalCorrectionService.Kind`. */
export const FISCAL_CORRECTION_KINDS = ['CORRECTION', 'VOID'] as const;
export type FiscalCorrectionKind = (typeof FISCAL_CORRECTION_KINDS)[number];

/** Mirrors `FiscalCorrectionService.Reason`: the closed set a worklist groups by. */
export const FISCAL_CORRECTION_REASONS = [
  'WRONG_AMOUNT',
  'WRONG_LINES',
  'WRONG_CLASSIFICATION',
  'WRONG_SELLER',
  'DUPLICATE_RECEIPT',
  'CUSTOMER_REFUND',
  'OTHER',
] as const;
export type FiscalCorrectionReason = (typeof FISCAL_CORRECTION_REASONS)[number];

/**
 * Why a request is closed without a receipt: the restaurant's fiscal agent would not issue it, or a person
 * withdrew it. Mirrors `FiscalCorrectionService.Closure`.
 */
export const FISCAL_CLOSURES = ['DECLINED', 'CANCELLED'] as const;
export type FiscalClosure = (typeof FISCAL_CLOSURES)[number];

/**
 * `OPENING` is the INN taken when the document was opened; `BACKFILL` was resolved from the entity as it stood
 * when the snapshot was added (a weaker claim, and said so); `UNRESOLVED` means no entity, so nobody can be said
 * to have issued it.
 */
export type FiscalSellerSource = 'OPENING' | 'BACKFILL' | 'UNRESOLVED';

/** `AVAILABLE`, `NOT_SENT` (nothing was sent, or it was not kept) or `NOT_READABLE` (recorded and cannot be opened). */
export type FiscalLinesState = 'AVAILABLE' | 'NOT_SENT' | 'NOT_READABLE';

/** `ITEM`, `DELIVERY_FEE` (the fee as an ordinary line, marked as such) or `SYNTHETIC` (the sandbox stand-in). */
export type FiscalLineKind = 'ITEM' | 'DELIVERY_FEE' | 'SYNTHETIC';

/** `NOT_BUILT` when the platform has no mark store (no count is shown), else `COUNTS`. */
export type FiscalMarksState = 'NOT_BUILT' | 'COUNTS';

/** Mirrors `OperationsFiscalEvidenceController.SellerResponse`. */
export interface FiscalSellerView {
  readonly legalEntityId: string | null;
  readonly tin: string | null;
  readonly name: string | null;
  readonly source: FiscalSellerSource;
}

/** Masked to `****` and the last four characters unless `revealed`. */
export interface FiscalIdentifiersView {
  readonly revealed: boolean;
  readonly fiscalSign: string | null;
  readonly receiptNumber: string | null;
  readonly receiptUrl: string | null;
  readonly terminalId: string | null;
}

export interface FiscalMerchantAccountView {
  readonly bindingId: string;
  readonly accountReferenceMasked: string | null;
}

/** One line as filed. `markingCodeCount` is a number of codes, never a code. */
export interface FiscalEvidenceLineView {
  readonly kind: FiscalLineKind | string;
  readonly name: string;
  readonly ikpu: string;
  readonly packageCode: string;
  readonly unitCode: number | null;
  readonly quantity: number;
  readonly unitPrice: Money;
  readonly tax: Money;
  readonly vatPercent: number;
  readonly discount: Money | null;
  readonly barcode: string | null;
  readonly markingCodeCount: number;
  /**
   * The INN this line was filed under (Click's `CommissionInfo`), as sent. Shown beside `seller.tin`, the INN the
   * document was opened under, so a difference between the two is visible and not resolved in either one's favour.
   */
  readonly commissionTin?: string | null;
}

export interface FiscalEvidenceLinesView {
  readonly state: FiscalLinesState;
  /** True when the one-line placeholder was filed (a sandbox only): its zeroes are not a sale. */
  readonly synthetic: boolean;
  readonly items: readonly FiscalEvidenceLineView[];
  readonly markingCodesTransmitted: number | null;
}

/** How the provider was told the payment was split (Click's `received_card / _cash / _ecash`). */
export interface FiscalTenderSplitView {
  readonly card: Money;
  readonly cash: Money;
  readonly ecash: Money;
}

export interface FiscalCorrectionSummaryView {
  readonly requestId: string;
  readonly kind: FiscalCorrectionKind;
  readonly status: 'REQUESTED' | 'ATTACHED' | 'DECLINED' | 'CANCELLED';
  readonly requestedAt: string;
  readonly attachedDocumentId: string | null;
}

/** Mirrors `OperationsFiscalEvidenceController.EvidenceDocumentResponse`. */
export interface FiscalEvidenceDocumentView {
  readonly documentId: string;
  readonly documentType: string;
  readonly correctsDocumentId: string | null;
  readonly status: string;
  readonly reasonCode: string;
  readonly reasonNote: string;
  readonly responsibility: string;
  readonly providerType: string | null;
  readonly seller: FiscalSellerView;
  readonly merchantAccount: FiscalMerchantAccountView | null;
  readonly tenderId: string | null;
  readonly paymentIntentId: string | null;
  readonly hasEvidence: boolean;
  readonly identifiers: FiscalIdentifiersView;
  readonly registeredAt: string | null;
  readonly providerStatusCode: string | null;
  readonly lines: FiscalEvidenceLinesView;
  readonly tenderSplit: FiscalTenderSplitView | null;
  readonly correction: FiscalCorrectionSummaryView | null;
  readonly submittedAt: string | null;
  readonly issuedAt: string | null;
  readonly blockedAt: string | null;
  readonly attemptCount: number;
  readonly version: number;
  readonly createdAt: string;
}

/** Mirrors `OperationsFiscalEvidenceController.FiscalEvidenceResponse`. */
export interface FiscalEvidenceView {
  readonly orderId: string;
  readonly publicOrderNumber: string | null;
  readonly retention: { readonly years: number; readonly provisional: boolean };
  readonly marks: {
    readonly state: FiscalMarksState;
    readonly required: number | null;
    readonly captured: number | null;
  };
  readonly revealed: boolean;
  readonly purpose: FiscalEvidencePurpose | null;
  readonly documents: readonly FiscalEvidenceDocumentView[];
}

/** `PARTNER_ACCOUNT`, `TERMINAL`, `AGGREGATOR`, `NOT_A_FISCAL_PATH` (a balance is a line discount) or `NONE`. */
export type FiscalPathKind =
  'PARTNER_ACCOUNT' | 'TERMINAL' | 'AGGREGATOR' | 'NOT_A_FISCAL_PATH' | 'NONE';

export interface FiscalPathEntryView {
  readonly legalEntityId: string;
  readonly tin: string | null;
  readonly legalName: string | null;
  readonly bindingId: string | null;
  readonly accountReferenceMasked: string | null;
  readonly capableTerminals: number;
  readonly supportsPartnerFiscalization: boolean;
}

/** Mirrors `OperationsFiscalEvidenceController.PaymentTypeResponse`: derived, and there is no way to set it. */
export interface FiscalPaymentTypeView {
  readonly code: string;
  readonly displayName: string;
  readonly methodStatus: string;
  readonly responsibility: string;
  readonly path: FiscalPathKind | string;
  readonly live: boolean;
  readonly reasonCode: string | null;
  readonly note: string | null;
  readonly entries: readonly FiscalPathEntryView[];
}

/** Mirrors `OperationsFiscalEvidenceController.CorrectionRequestResponse`. */
export interface FiscalCorrectionRequestView {
  readonly requestId: string;
  readonly documentId: string;
  readonly orderId: string;
  /** The number the order is known by; null only if the order cannot be resolved. Show this, never `orderId`. */
  readonly publicOrderNumber: string | null;
  readonly kind: FiscalCorrectionKind;
  readonly reasonCode: string;
  readonly status: string;
  readonly requestedBy: string;
  readonly approvalId: string | null;
  readonly requestedAt: string;
  readonly attachedDocumentId: string | null;
  readonly attachedAt: string | null;
  /** When it was closed without a receipt (`DECLINED` or `CANCELLED`); null while open or once attached. */
  readonly closedAt?: string | null;
  readonly version: number;
}

export interface FiscalCorrectionRaisedView {
  readonly request: FiscalCorrectionRequestView;
  readonly documentVersion: number;
}

export interface FiscalCorrectionAttachedView {
  readonly request: FiscalCorrectionRequestView;
  readonly linkedDocumentId: string;
  readonly documentVersion: number;
}

export interface FiscalCorrectionClosedView {
  readonly request: FiscalCorrectionRequestView;
  readonly documentVersion: number;
}

export interface FiscalCorrectionCloseBody {
  readonly outcome: FiscalClosure;
  readonly note: string;
}

export interface FiscalCorrectionRequestBody {
  readonly kind: FiscalCorrectionKind;
  readonly reasonCode: FiscalCorrectionReason;
  readonly reasonNote: string;
}

/** What the tenant's own fiscal agent filed, as the operator attaches it: a reference and not a receipt. */
export interface FiscalAttachmentBody {
  readonly fiscalSign: string;
  readonly receiptNumber: string;
  readonly receiptUrl: string;
  readonly terminalId?: string;
}

/**
 * 8.2 The fiscal evidence surface (`OperationsFiscalEvidenceController`, ADR 0166).
 *
 * HorecaOS records the evidence and the requests; the tenant's own legal entity is the fiscal agent on every path
 * and issues any correction. Nothing here composes a receipt, and nothing here can return a marking code: the
 * server returns a count and no code under any purpose.
 */
@Injectable({ providedIn: 'root' })
export class FiscalEvidenceApi {
  private readonly api = inject(ApiClient);

  /**
   * The receipts filed for an order. With a `purpose` the identifiers come back in full: that needs the
   * `fiscal.evidence.reveal` capability (403 without it) and writes one audit fact per document.
   */
  async forOrder(
    tenantId: string,
    orderId: string,
    purpose?: FiscalEvidencePurpose,
  ): Promise<FiscalEvidenceView> {
    const result = await firstValueFrom(
      this.api.get<FiscalEvidenceView>(financePaths.fiscalEvidence(tenantId, orderId), {
        params: { purpose },
      }),
    );
    return result.value;
  }

  async paymentTypes(tenantId: string): Promise<readonly FiscalPaymentTypeView[]> {
    const result = await firstValueFrom(
      this.api.get<{ readonly types: readonly FiscalPaymentTypeView[] }>(
        financePaths.fiscalPaymentTypes(tenantId),
      ),
    );
    return result.value?.types ?? [];
  }

  async openCorrections(tenantId: string): Promise<readonly FiscalCorrectionRequestView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly FiscalCorrectionRequestView[]>(
        financePaths.fiscalCorrectionRequests(tenantId),
      ),
    );
    return result.value ?? [];
  }

  /**
   * Asks the tenant's fiscal agent to correct or void an issued receipt. Under an approval: the first call may
   * answer 422 `SECOND_APPROVER_REQUIRED` naming the request a second person must decide; once they have, the same
   * call goes through. `expectedVersion` is the document's version.
   */
  async requestCorrection(
    tenantId: string,
    documentId: string,
    expectedVersion: number,
    body: FiscalCorrectionRequestBody,
  ): Promise<FiscalCorrectionRaisedView> {
    return firstValueFrom(
      this.api.post<FiscalCorrectionRequestBody, FiscalCorrectionRaisedView>(
        financePaths.fiscalDocumentCorrectionRequests(tenantId, documentId),
        { key: newIdempotencyKey(), body },
        { expectedVersion },
      ),
    );
  }

  /**
   * Closes a request that will not be attached: the agent declined, or it was raised in error. The document is
   * issued again with its evidence as it was, and nothing is filed. `expectedVersion` is the document's version.
   */
  async closeRequest(
    tenantId: string,
    requestId: string,
    expectedVersion: number,
    body: FiscalCorrectionCloseBody,
  ): Promise<FiscalCorrectionClosedView> {
    return firstValueFrom(
      this.api.post<FiscalCorrectionCloseBody, FiscalCorrectionClosedView>(
        financePaths.fiscalCorrectionClosures(tenantId, requestId),
        { key: newIdempotencyKey(), body },
        { expectedVersion },
      ),
    );
  }

  /** Attaches the reference the agent returned. `expectedVersion` is the original document's version. */
  async attach(
    tenantId: string,
    requestId: string,
    expectedVersion: number,
    body: FiscalAttachmentBody,
  ): Promise<FiscalCorrectionAttachedView> {
    return firstValueFrom(
      this.api.post<FiscalAttachmentBody, FiscalCorrectionAttachedView>(
        financePaths.fiscalCorrectionAttachments(tenantId, requestId),
        { key: newIdempotencyKey(), body },
        { expectedVersion },
      ),
    );
  }
}
