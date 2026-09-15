import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { TenantScope, posPaths } from '../../core/api/pos-paths';

/** Mirrors `ExportState` (Java, `pos.domain`) — every state one export can be in (ADR 0011). */
export type PosExportState =
  | 'PENDING'
  | 'SENT'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'UNCERTAIN'
  | 'AWAITING_OPERATOR'
  | 'RESOLVED_LANDED'
  | 'RESOLVED_ABSENT'
  | 'ABANDONED';

/** Mirrors `OrderPosExportController.ExportView`. Never the control-plane's own recovery candidates (ADR 0011). */
export interface PosExportView {
  readonly exportId: string;
  readonly state: PosExportState;
  /** The §3.11 amendment interlock's own signal — mirrors `ExportState#permitsAmendment`, never re-derived from `state` here. */
  readonly permitsAmendment: boolean;
  readonly attemptCount: number;
  readonly externalOrderId: string | null;
  readonly requestedAt: string;
  readonly firstSentAt: string | null;
  readonly settledAt: string | null;
  /** The adapter's own diagnostic code, e.g. `LINE_UNMAPPED` — an ADR 0012 mapping gap, never a customer fact. */
  readonly lastErrorCode: string | null;
  readonly lastError: string | null;
  readonly resolutionKind: string | null;
  readonly resolutionReason: string | null;
  readonly resolvedAt: string | null;
}

/** Mirrors `OrderPosExportController.OrderPosExportResponse`. */
export interface OrderPosExportView {
  /** False suppresses the whole affordance — the location's own POS binding does not declare `ORDER_EXPORT` (ADR 0011). */
  readonly posCapable: boolean;
  /** Null when `posCapable` is false, or when no export has opened for this order yet. */
  readonly export: PosExportView | null;
}

/** Mirrors `ProviderOutcome.Status` (Java, `integration.api.provider`). */
export type PosExportPushStatus = 'SUCCESS' | 'REJECTED' | 'RETRYABLE' | 'UNCERTAIN';

/** Mirrors `OrderPosExportController.PushResultResponse`. Always a `200` -- a refusal the state machine already understood is an ordinary answer, never a thrown fault. */
export interface PosExportPushResult {
  readonly status: PosExportPushStatus;
  readonly state: PosExportState;
  readonly errorCode: string | null;
  readonly detail: string | null;
}

/**
 * `OrderPosExportController` (wave P42, gap map row `1.2i`) — the order
 * detail pane's own POS export panel, and the §3.11 amendment interlock both
 * read from here. One read, reused for two purposes: the panel renders it,
 * and `order-detail-pane.ts` derives whether AMEND should render disabled
 * from the same `permitsAmendment` flag rather than asking twice.
 */
@Injectable({ providedIn: 'root' })
export class OrderPosExportApi {
  private readonly api = inject(ApiClient);

  async forOrder(scope: TenantScope, orderId: string): Promise<OrderPosExportView> {
    const result = await firstValueFrom(
      this.api.get<OrderPosExportView>(posPaths.orderPosExport(scope, orderId)),
    );
    return result.value;
  }

  /** Push a pending export, or retry one the state machine still permits sending. `reason` is required and audited (ADR 0027). */
  async push(scope: TenantScope, orderId: string, reason: string): Promise<PosExportPushResult> {
    return firstValueFrom(
      this.api.post<{ reason: string }, PosExportPushResult>(
        posPaths.orderPosExportPush(scope, orderId),
        command({ reason }),
      ),
    );
  }
}
