import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { advanceReasonCode } from './order-actions';

/**
 * `DecisionResponse` — the one response shape `approval-decisions`,
 * `state-actions` and `cancellations` all return (`OperationsOrderController`).
 *
 * @property applied whether *this* call's command is the one that moved the
 *   order. False on a lost approval race — see {@link effectiveAction}.
 * @property effectiveDecisionId the decision that actually settled the order
 *   when `applied` is false; may be another operator's.
 * @property effectiveAction `"APPROVE"` or `"REJECT"` — what the order was
 *   actually settled with, when this call lost the race. Null for
 *   `state-actions` and `cancellations`, which have no competing decisions.
 */
export interface DecisionResponse {
  readonly orderId: string;
  readonly status: string;
  readonly version: number;
  readonly applied: boolean;
  readonly effectiveDecisionId: string | null;
  readonly effectiveAction: string | null;
}

/**
 * `POST .../approval-decisions`, `.../state-actions`, `.../cancellations` —
 * orders.md §4.3, the three mutations §4.2's `actions[]` can name today.
 *
 * Every method here takes the `decisionId` or `expectedVersion` the caller
 * already resolved rather than inventing either: `decisionId` comes from
 * `DecisionIdRegistry` (`order-actions.ts`) so a retried click is one
 * decision, and `expectedVersion` is the version the caller last read the
 * order at, so a stale write fails loudly (§4.1) instead of silently
 * clobbering another operator's change.
 */
@Injectable({ providedIn: 'root' })
export class OrderActionsApi {
  private readonly api = inject(ApiClient);

  /**
   * `Принять` (§4.3). No `If-Match`: the decision endpoint is settled by
   * `decisionId` compare-and-set, not by the order's aggregate version — the
   * same reason `OperationsOrderController.decide` never calls
   * `AggregateVersion.requireIfMatch`.
   */
  approve(scope: LocationScope, orderId: string, decisionId: string): Observable<DecisionResponse> {
    return this.api.post<{ decisionId: string; action: string }, DecisionResponse>(
      operationsPaths.orderApprovalDecisions(scope, orderId),
      command({ decisionId, action: 'APPROVE' }),
    );
  }

  /**
   * `Отклонить` (§4.3, wave 24). `reasonCode` names one of `GET
   * .../reject-reasons`' curated codes; `note` is required exactly when that
   * reason's `requiresNote` said so — `OrderRejectReasonDialog` is what
   * enforces that before this is ever called.
   */
  reject(
    scope: LocationScope,
    orderId: string,
    decisionId: string,
    reasonCode: string,
    note?: string,
  ): Observable<DecisionResponse> {
    return this.api.post<
      { decisionId: string; action: string; reasonCode: string; note?: string },
      DecisionResponse
    >(
      operationsPaths.orderApprovalDecisions(scope, orderId),
      command({ decisionId, action: 'REJECT', reasonCode, note: note ? note : undefined }),
    );
  }

  /**
   * `Продвинуть` (§4.3). `StateActionRequest` requires a non-blank
   * `reasonCode` the operator is never prompted for here — §4.3 marks this
   * action's confirm column "no" — so {@link advanceReasonCode} supplies one.
   */
  advance(
    scope: LocationScope,
    orderId: string,
    targetStatus: string,
    expectedVersion: number,
  ): Observable<DecisionResponse> {
    return this.api.post<{ targetStatus: string; reasonCode: string }, DecisionResponse>(
      operationsPaths.orderStateActions(scope, orderId),
      command({ targetStatus, reasonCode: advanceReasonCode(targetStatus) }),
      { expectedVersion },
    );
  }

  /**
   * `Отменить`, reasonless (§4.3). Refused by `OrderActionsPolicy.
   * canCancelWithoutReason` from `CONFIRMED` onward — {@link cancelWithReason}
   * is what the order detail pane's dialog uses there (wave P09, gap map
   * `1.2k`).
   */
  cancel(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    reasonCode: string,
    note?: string,
  ): Observable<DecisionResponse> {
    return this.api.post<{ reasonCode: string; note?: string }, DecisionResponse>(
      operationsPaths.orderCancellations(scope, orderId),
      command({ reasonCode, note: note ? note : undefined }),
      { expectedVersion },
    );
  }

  /**
   * `Отменить`, with a reason from `ordering.order_outcome_reasons`
   * (orders.md §4.5, wave P09 gap map `1.2k`) — the path `OrderActionsPolicy`
   * now offers `CANCEL` for at any non-terminal status, `CONFIRMED` onward
   * included. `CancelRequest.reasonCode` stays `@NotBlank` on the wire even
   * on this path (the server ignores it once `reasonId` is present); the
   * reason's own `systemCategory` — a closed, non-PII code — is what the
   * dialog sends there rather than inventing free text of its own.
   */
  cancelWithReason(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    reasonId: string,
    reasonCode: string,
    note?: string,
  ): Observable<DecisionResponse> {
    return this.api.post<{ reasonCode: string; reasonId: string; note?: string }, DecisionResponse>(
      operationsPaths.orderCancellations(scope, orderId),
      command({ reasonCode, reasonId, note: note ? note : undefined }),
      { expectedVersion },
    );
  }

  /**
   * `Завершить` (orders.md §4.6, wave P09 gap map `1.2j`) —
   * `POST .../completion`, naming how the order finished instead of the
   * generic advance every console completion booked as `DELIVERED_OWN_COURIER`
   * before this wave. `reasonId` omitted records the completion category the
   * fulfilment mode implies, with no `OutcomeResponse` recorded — the order
   * detail pane only omits it when the tenant's registry has no active
   * reason for this order's mode at all.
   */
  complete(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    reasonId?: string,
  ): Observable<DecisionResponse> {
    return this.api.post<{ reasonId?: string }, DecisionResponse>(
      operationsPaths.orderCompletion(scope, orderId),
      command({ reasonId: reasonId ? reasonId : undefined }),
      { expectedVersion },
    );
  }
}
