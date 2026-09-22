import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** `OrderBulkActionService.BulkActionType` — bulk courier assignment is explicitly out of scope (orders.md §2.10, wave P07). */
export type BulkActionType = 'ADVANCE' | 'CANCEL';

/** `OrderBulkActionService.BulkItemStatus`. */
export type BulkItemStatus = 'PENDING' | 'APPLIED' | 'FAILED';

export interface BulkOrderRef {
  readonly orderId: string;
  readonly expectedVersion: number;
}

/**
 * Mirrors `OperationsOrderController.BulkActionRequest`.
 *
 * @property targetStatus required for `ADVANCE` — `PREPARING`, `READY` or `FULFILLING`
 * @property reasonCode required for `ADVANCE`, exactly like a single state action
 * @property cancelReasonId required for `CANCEL`, from the tenant's outcome-reason registry
 */
export interface BulkActionRequest {
  readonly actionType: BulkActionType;
  readonly orders: readonly BulkOrderRef[];
  readonly targetStatus?: string;
  readonly reasonCode?: string;
  readonly cancelReasonId?: string;
  readonly cancelNote?: string;
}

/** Mirrors `OperationsOrderController.BulkActionItemResponse`. */
export interface BulkActionItemResponse {
  readonly orderId: string;
  readonly itemStatus: BulkItemStatus;
  readonly itemProblemCode?: string | null;
  readonly resultingOrderVersion?: number | null;
}

/** Mirrors `OperationsOrderController.BulkActionResponse`. */
export interface BulkActionResponse {
  readonly bulkOperationId: string;
  readonly actionType: BulkActionType;
  readonly requestedCount: number;
  readonly appliedCount: number;
  readonly failedCount: number;
  /** True when this response is a replay of an already-recorded submission (the same `Idempotency-Key` resubmitted). */
  readonly replayed: boolean;
  readonly items: readonly BulkActionItemResponse[];
}

/**
 * `POST .../orders/bulk-actions` (ADR 0039, orders.md §2.10, wave P07) — the
 * one caller of a path that had none: built, capability-gated on
 * `ORDER_BULK_ACTION`, 200 orders per call, always `202` with a per-item
 * outcome list.
 *
 * **"Повторить проблемные" is a new submission, never a resubmission under
 * the same key.** `OrderBulkActionService`'s own doc is explicit that a
 * resubmission under the same `Idempotency-Key` "changes nothing and returns
 * the outcome already recorded, applied or failed alike" — literally, not
 * "retries only the failures". So a retry from the result panel narrows
 * `orders` to only the items that failed, never the ones that already
 * applied.
 *
 * That narrowing is exactly what {@link IntentCommandRegistry} already treats
 * as a new intent — a different `orders` list is a different body, so it
 * mints a fresh key on its own, preserving the panel's behaviour above with
 * no special case. What holding one key across the *unchanged* case buys
 * (2026-09-21 audit follow-up (a)): a lost response to the same, un-narrowed
 * submission — a double click on "Apply" before the panel shows busy — used
 * to mint two independent keys and could run the same bulk action twice on
 * up to 200 orders at once.
 */
@Injectable({ providedIn: 'root' })
export class OrderBulkActionsApi {
  private readonly api = inject(ApiClient);

  /**
   * One bulk-action panel at a time is the real shape of this screen (the
   * order queue's own selection is singular), so a fixed id is enough —
   * unlike `OrderActionsApi`, there is no per-order key to hold many
   * concurrent intents under.
   */
  private readonly submitIntents = new IntentCommandRegistry<BulkActionRequest>();

  submit(scope: LocationScope, request: BulkActionRequest): Observable<BulkActionResponse> {
    const intent = this.submitIntents.next('bulk-actions', request);
    return this.api
      .post<BulkActionRequest, BulkActionResponse>(operationsPaths.orderBulkActions(scope), intent)
      .pipe(tap(() => this.submitIntents.forget('bulk-actions')));
  }
}
