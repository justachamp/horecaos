import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
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
 * "retries only the failures". So a retry from the result panel is this
 * class's `submit` called again, with a **fresh** `Idempotency-Key` (via
 * {@link command}, minted per intent per that helper's own contract) and an
 * `orders` list narrowed to only the items that failed — never the ones that
 * already applied, which a fresh key would otherwise re-execute.
 */
@Injectable({ providedIn: 'root' })
export class OrderBulkActionsApi {
  private readonly api = inject(ApiClient);

  submit(scope: LocationScope, request: BulkActionRequest): Observable<BulkActionResponse> {
    return this.api.post<BulkActionRequest, BulkActionResponse>(
      operationsPaths.orderBulkActions(scope),
      command(request),
    );
  }
}
