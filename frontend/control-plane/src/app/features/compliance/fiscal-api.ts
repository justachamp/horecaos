import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/** FiscalDocumentController.BlockedDocumentResponse. */
export interface BlockedDocumentResponse {
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

export interface BlockedWorklistResponse {
  readonly count: number;
  readonly documents: readonly BlockedDocumentResponse[];
  readonly warning: string | null;
}

/**
 * `FiscalDocumentController` (ADR 0038) -- tenant-scoped in the API's own
 * path (`/api/v1/tenants/{tenantId}/fiscal/**`, the `operations` OpenAPI
 * surface), unlike the rest of `PlatformIntegrationAdminController`'s
 * cross-tenant siblings: no cross-tenant fiscal aggregate exists, so IA 6.1's
 * screen picks one tenant and reads its real worklist rather than showing
 * nothing. The same surface-group crossing ADR 0065 already recorded for
 * merchant bindings ("sits on the operations OpenAPI surface while its
 * screen lives in control-plane") -- reachable here because
 * `FISCAL_DOCUMENT_READ`/`_RESOLVE` at `TENANT` scope is satisfied by the
 * `PLATFORM_ADMIN` bundle, which covers every capability but one.
 */
@Injectable({ providedIn: 'root' })
export class FiscalApi {
  private readonly api = inject(ApiClient);

  async blocked(tenantId: string, reasonCode?: string): Promise<BlockedWorklistResponse> {
    return firstValueFrom(
      this.api.get<BlockedWorklistResponse>(`/api/v1/tenants/${tenantId}/fiscal/documents/blocked`, {
        query: { reasonCode },
      }),
    );
  }

  async retry(
    tenantId: string,
    documentId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<{ documentId: string; outcome: string; version: number; warning: string | null }> {
    return firstValueFrom(
      this.api.post<{ documentId: string; outcome: string; version: number; warning: string | null }>(
        `/api/v1/tenants/${tenantId}/fiscal/documents/${documentId}/retries`,
        { reason },
        { expectedVersion },
      ),
    );
  }

  async unblock(
    tenantId: string,
    documentId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<{ documentId: string; outcome: string; version: number }> {
    return firstValueFrom(
      this.api.post<{ documentId: string; outcome: string; version: number }>(
        `/api/v1/tenants/${tenantId}/fiscal/documents/${documentId}/unblocks`,
        { reason },
        { expectedVersion },
      ),
    );
  }
}
