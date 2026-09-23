import { Injectable, inject } from '@angular/core';
import { firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
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
  /**
   * Gap-map row 1.2i's fix path: `'VARIANT'` or `'MODIFIER'` when {@link lastErrorCode}
   * is `LINE_UNMAPPED`/`MODIFIER_UNMAPPED` and the server found a currently-unmapped
   * line or modifier on a live re-check — null otherwise, including when the error
   * code names a mapping gap but every line now resolves (an operator may already
   * have fixed it since the last attempt).
   */
  readonly unmappedEntityType: 'VARIANT' | 'MODIFIER' | null;
  /** The id to pre-select on the ADR 0012 mapping screen, paired one-to-one with {@link unmappedEntityType}. */
  readonly unmappedHorecaosEntityId: string | null;
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

  /**
   * No `If-Match` on {@link push} — the export attempt is not the order's own
   * aggregate version, so nothing else guards a double click (2026-09-21
   * audit follow-up (a)). Keyed by orderId: an unchanged retry (the operator
   * clicking "Push" again before the panel shows busy) reuses the key so the
   * platform replays the first attempt instead of sending the same line to
   * the POS a second time; an intentional retry of a settled failure reads
   * as a new intent once the previous push's response forgot the held one.
   */
  private readonly pushIntents = new IntentCommandRegistry<{ reason: string }>();

  async forOrder(scope: TenantScope, orderId: string): Promise<OrderPosExportView> {
    const result = await firstValueFrom(
      this.api.get<OrderPosExportView>(posPaths.orderPosExport(scope, orderId)),
    );
    return result.value;
  }

  /** Push a pending export, or retry one the state machine still permits sending. `reason` is required and audited (ADR 0027). */
  async push(scope: TenantScope, orderId: string, reason: string): Promise<PosExportPushResult> {
    const intent = this.pushIntents.next(orderId, { reason });
    return firstValueFrom(
      this.api
        .post<{ reason: string }, PosExportPushResult>(posPaths.orderPosExportPush(scope, orderId), intent)
        .pipe(tap(() => this.pushIntents.forget(orderId))),
    );
  }
}
