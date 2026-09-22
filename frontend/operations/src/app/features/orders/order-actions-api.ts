import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Command, IntentCommandRegistry } from '../../core/api/idempotency';
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
 * `OperationsOrderController.DeliveryCancellationOutcome` (wave P44, gap map
 * row 1.2g) — what happened to this order's delivery plan the instant it was
 * cancelled, if it had one.
 */
export interface DeliveryCancellationOutcome {
  /**
   * `ShipmentCancellationPort.Result`'s own name: `NOTHING_TO_CANCEL` |
   * `PLAN_CANCELLED` | `INTERNAL_CANCELLED` | `PROVIDER_CANCELLED` |
   * `PROVIDER_CANCELLED_CHARGEABLE` | `PROVIDER_UNCERTAIN` | `PROVIDER_FAILED`.
   * The last two mean an operator must still resolve this by hand — see the
   * order detail's own delivery-exception band.
   */
  readonly outcome: string;
  readonly providerType: string | null;
}

/**
 * `OperationsOrderController.OrderCancellationResponse` — `.../cancellations`'
 * own response shape (wave P44, gap map row 1.2g), everything {@link
 * DecisionResponse} carries plus `deliveryCancellation`. `null` for an order
 * that never had a delivery plan (pickup, dine-in) and for a decision this
 * call lost the race for (`!applied`).
 */
export interface OrderCancellationResponse extends DecisionResponse {
  readonly deliveryCancellation: DeliveryCancellationOutcome | null;
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
 *
 * HK: this service is `providedIn: 'root'` — one instance for the app's
 * lifetime — so it is exactly the kind of "state" `idempotency.ts` means when
 * it says a key is minted once per intent and reused on retry. Each mutation
 * below holds its own {@link IntentCommandRegistry}, keyed by `orderId`, and
 * builds the `Idempotency-Key` from it instead of minting one inline per
 * call the way `command(request)` used to: two calls for the same order
 * carrying an identical body (a manual retry of a lost response, or a second
 * click before the row re-renders busy) get the SAME key, so the platform
 * replays the first attempt instead of executing a second one; a body that
 * differs (the operator picked a different reason, or `DecisionIdRegistry`
 * rotated `decisionId` after the previous decision settled) gets a fresh
 * key, because that is honestly a new intent. `tap` forgets the held
 * command on a successful response — the server has then confirmed the
 * intent completed, so any further call for that order is a new one, not a
 * retry of this one.
 */
@Injectable({ providedIn: 'root' })
export class OrderActionsApi {
  private readonly api = inject(ApiClient);

  private readonly decisionIntents = new IntentCommandRegistry<{
    decisionId: string;
    action: string;
    reasonCode?: string;
    note?: string;
  }>();
  private readonly advanceIntents = new IntentCommandRegistry<{
    targetStatus: string;
    reasonCode: string;
  }>();
  private readonly cancelIntents = new IntentCommandRegistry<{
    reasonCode: string;
    reasonId?: string;
    note?: string;
  }>();
  private readonly completeIntents = new IntentCommandRegistry<{ reasonId?: string }>();

  /**
   * `Принять` (§4.3). No `If-Match`: the decision endpoint is settled by
   * `decisionId` compare-and-set, not by the order's aggregate version — the
   * same reason `OperationsOrderController.decide` never calls
   * `AggregateVersion.requireIfMatch`.
   */
  approve(scope: LocationScope, orderId: string, decisionId: string): Observable<DecisionResponse> {
    const intent = this.decisionIntents.next(orderId, { decisionId, action: 'APPROVE' });
    return this.api
      .post<{ decisionId: string; action: string }, DecisionResponse>(
        operationsPaths.orderApprovalDecisions(scope, orderId),
        intent,
      )
      .pipe(tap(() => this.decisionIntents.forget(orderId)));
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
    const intent = this.decisionIntents.next(orderId, {
      decisionId,
      action: 'REJECT',
      reasonCode,
      note: note ? note : undefined,
    }) as Command<{ decisionId: string; action: string; reasonCode: string; note?: string }>;
    return this.api
      .post<
        { decisionId: string; action: string; reasonCode: string; note?: string },
        DecisionResponse
      >(operationsPaths.orderApprovalDecisions(scope, orderId), intent)
      .pipe(tap(() => this.decisionIntents.forget(orderId)));
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
    const intentId = versionedIntentId(orderId, expectedVersion);
    const intent = this.advanceIntents.next(intentId, {
      targetStatus,
      reasonCode: advanceReasonCode(targetStatus),
    });
    return this.api
      .post<{ targetStatus: string; reasonCode: string }, DecisionResponse>(
        operationsPaths.orderStateActions(scope, orderId),
        intent,
        { expectedVersion },
      )
      .pipe(tap(() => this.advanceIntents.forget(intentId)));
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
  ): Observable<OrderCancellationResponse> {
    const intentId = versionedIntentId(orderId, expectedVersion);
    const intent = this.cancelIntents.next(intentId, {
      reasonCode,
      note: note ? note : undefined,
    });
    return this.api
      .post<{ reasonCode: string; note?: string }, OrderCancellationResponse>(
        operationsPaths.orderCancellations(scope, orderId),
        intent,
        { expectedVersion },
      )
      .pipe(tap(() => this.cancelIntents.forget(intentId)));
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
  ): Observable<OrderCancellationResponse> {
    const intentId = versionedIntentId(orderId, expectedVersion);
    const intent = this.cancelIntents.next(intentId, {
      reasonCode,
      reasonId,
      note: note ? note : undefined,
    }) as Command<{ reasonCode: string; reasonId: string; note?: string }>;
    return this.api
      .post<{ reasonCode: string; reasonId: string; note?: string }, OrderCancellationResponse>(
        operationsPaths.orderCancellations(scope, orderId),
        intent,
        { expectedVersion },
      )
      .pipe(tap(() => this.cancelIntents.forget(intentId)));
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
    const intentId = versionedIntentId(orderId, expectedVersion);
    const intent = this.completeIntents.next(intentId, {
      reasonId: reasonId ? reasonId : undefined,
    });
    return this.api
      .post<{ reasonId?: string }, DecisionResponse>(
        operationsPaths.orderCompletion(scope, orderId),
        intent,
        { expectedVersion },
      )
      .pipe(tap(() => this.completeIntents.forget(intentId)));
  }
}

/**
 * `IntentCommandRegistry` compares only the tracked body it is given
 * (`sameBody`, `idempotency.ts`) — `expectedVersion` travels to the server
 * as the separate `If-Match` header and is invisible to that comparison. The
 * platform's own idempotency store hashes only the raw request body
 * (`IdempotencyInterceptor.bodyOf`), so a retry that changes only `If-Match`
 * — an operator resubmitting after a `409 STALE_VERSION` correction, with an
 * otherwise-unchanged reason/note — is indistinguishable from the original
 * request and would replay its stored 409 forever (bug-hunt H1).
 *
 * Folding `expectedVersion` into the registry's *id* (rather than into the
 * tracked body, which must stay exactly the wire body) means a version change
 * always misses the held command for the old id and mints a fresh key, while
 * an identical retry at the same version still hits the same id and is
 * compared by body as before.
 */
function versionedIntentId(orderId: string, expectedVersion: number): string {
  return `${orderId}:${expectedVersion}`;
}
