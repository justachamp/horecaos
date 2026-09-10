import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/** An order sent to a tenant's POS whose outcome nobody could establish. Carries no customer detail. */
export interface PosExportView {
  readonly exportId: string;
  readonly orderId: string;
  /** UNCERTAIN until the POS has been asked; AWAITING_OPERATOR once only a person can decide. */
  readonly state: 'UNCERTAIN' | 'AWAITING_OPERATOR' | (string & {});
  readonly attemptCount: number;
  readonly correlationReference: string | null;
  readonly externalOrderId: string | null;
  readonly venue: string;
  readonly requestedAt: string;
}

/** A POS order that resembles the export. Only `correlationEchoed` is proof it is ours. */
export interface PosExportCandidate {
  readonly externalOrderId: string;
  readonly externalStatus: string | null;
  readonly externalCreatedAt: string | null;
  readonly correlationEchoed: boolean;
  readonly phoneMatches: boolean;
  readonly fingerprintMatches: boolean;
  readonly timeDeltaSeconds: number | null;
}

export type PosExportDecision = 'LANDED' | 'ABSENT' | 'ABANDON';

/** Orders stuck between HorecaOS and a tenant's POS, and the decision that settles them. */
@Injectable({ providedIn: 'root' })
export class PosExportsApi {
  private readonly api = inject(ApiClient);

  async awaiting(tenantId: string, limit = 100): Promise<Page<PosExportView>> {
    return firstValueFrom(
      this.api.get<Page<PosExportView>>(`/api/v1/control-plane/tenants/${tenantId}/pos-exports`, {
        query: { limit },
      }),
    );
  }

  async candidates(tenantId: string, exportId: string): Promise<Page<PosExportCandidate>> {
    return firstValueFrom(
      this.api.get<Page<PosExportCandidate>>(
        `/api/v1/control-plane/tenants/${tenantId}/pos-exports/${exportId}/candidates`,
      ),
    );
  }

  /** Reads the POS for what happened; never re-sends the order. */
  async discover(tenantId: string, exportId: string): Promise<{ status: string; errorCode: string; detail: string }> {
    return firstValueFrom(
      this.api.post<{ status: string; errorCode: string; detail: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/pos-exports/${exportId}/discovery`,
        {},
      ),
    );
  }

  async resolve(
    tenantId: string,
    exportId: string,
    decision: PosExportDecision,
    reason: string,
    externalOrderId?: string,
  ): Promise<{ changed: boolean }> {
    return firstValueFrom(
      this.api.post<{ changed: boolean }>(
        `/api/v1/control-plane/tenants/${tenantId}/pos-exports/${exportId}/resolution`,
        { decision, externalOrderId, reason },
      ),
    );
  }
}
