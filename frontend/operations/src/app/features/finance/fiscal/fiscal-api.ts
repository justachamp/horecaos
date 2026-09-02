import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { financePaths } from '../../../core/api/finance-paths';
import { newIdempotencyKey } from '../../../core/api/idempotency';

/** Mirrors `FiscalDocumentController.BlockedDocumentResponse`. */
export interface FiscalDocumentView {
  readonly documentId: string;
  readonly orderId: string;
  readonly legalEntityId: string;
  readonly documentType: string;
  readonly responsibility: string;
  readonly providerType: string;
  readonly status: string;
  readonly reasonCode: string;
  readonly reasonNote: string | null;
  readonly hasEvidence: boolean;
  readonly attemptCount: number;
  readonly version: number;
  readonly submittedAt: string | null;
  readonly reportingDeadlineAt: string | null;
  readonly blockedAt: string | null;
}

/** Mirrors `FiscalDocumentController.BlockedWorklistResponse`. */
export interface FiscalBlockedWorklistView {
  readonly count: number;
  readonly documents: readonly FiscalDocumentView[];
  readonly warning: string | null;
}

/** Mirrors `FiscalDocumentController.CoverageResponse`. */
export interface FiscalCoverageView {
  readonly from: string;
  readonly to: string;
  readonly saleDocuments: number;
  readonly issued: number;
  readonly notApplicable: number;
  readonly notApplicableCash: number;
  readonly blocked: number;
  readonly failed: number;
  readonly awaitingProvider: number;
  readonly unreceipted: number;
  readonly issuedShareBasisPoints: number;
  readonly notApplicableShareBasisPoints: number;
  readonly unreceiptedShareBasisPoints: number;
  readonly providerPathIsMinority: boolean;
  readonly warning: string | null;
}

/** Mirrors `FiscalDocumentController.ResolutionResponse`. */
export interface FiscalResolutionView {
  readonly documentId: string;
  readonly outcome: string;
  readonly version: number;
  readonly warning: string | null;
}

/**
 * 8.2 Fiscal receipts (`FiscalDocumentController`, ADR 0038). Every read and
 * write here already exists on the server — this wave adds no fiscal
 * endpoint, only the console that reads them.
 */
@Injectable({ providedIn: 'root' })
export class FiscalApi {
  private readonly api = inject(ApiClient);

  async blocked(tenantId: string, reasonCode?: string): Promise<FiscalBlockedWorklistView> {
    const result = await firstValueFrom(
      this.api.get<FiscalBlockedWorklistView>(financePaths.fiscalDocumentsBlocked(tenantId), {
        params: { reasonCode },
      }),
    );
    return result.value;
  }

  async forOrder(tenantId: string, orderId: string): Promise<readonly FiscalDocumentView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly FiscalDocumentView[]>(
        financePaths.fiscalOrderDocuments(tenantId, orderId),
      ),
    );
    return result.value ?? [];
  }

  async coverage(tenantId: string, from: string, to: string): Promise<FiscalCoverageView> {
    const result = await firstValueFrom(
      this.api.get<FiscalCoverageView>(financePaths.fiscalCoverage(tenantId), {
        params: { from, to },
      }),
    );
    return result.value;
  }

  async retry(
    tenantId: string,
    documentId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<FiscalResolutionView> {
    return firstValueFrom(
      this.api.post<{ reason: string }, FiscalResolutionView>(
        financePaths.fiscalDocumentRetries(tenantId, documentId),
        { key: newIdempotencyKey(), body: { reason } },
        { expectedVersion },
      ),
    );
  }

  async unblock(
    tenantId: string,
    documentId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<FiscalResolutionView> {
    return firstValueFrom(
      this.api.post<{ reason: string }, FiscalResolutionView>(
        financePaths.fiscalDocumentUnblocks(tenantId, documentId),
        { key: newIdempotencyKey(), body: { reason } },
        { expectedVersion },
      ),
    );
  }
}
