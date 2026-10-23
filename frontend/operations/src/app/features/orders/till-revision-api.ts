import { Injectable, inject } from '@angular/core';
import { firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope } from '../../core/api/operations-paths';
import { posPaths } from '../../core/api/pos-paths';

/** `OpenAPI`: `OPEN` until the till was told or a person changed it; `TOLD` and `ACKNOWLEDGED` are the two ways it ends. */
export type TillRevisionStatus = 'OPEN' | 'TOLD' | 'ACKNOWLEDGED';

/** Mirrors `OrderPosRevisionController.NoticeView`: money and codes only, never a customer or a line. */
export interface TillRevisionNotice {
  readonly noticeId: string;
  /**
   * The order revision whose total the till does not hold. It moves when the order is weighed again, so it is
   * what an action quotes: the platform closes the notice the operator looked at and refuses one that moved.
   */
  readonly revision: number;
  readonly status: TillRevisionStatus;
  /** What moved the total: `CATCHWEIGHT`, a captured weight. */
  readonly source: string;
  readonly currency: string;
  /** What the till holds. */
  readonly tillTotalMinor: number;
  /** What the order is worth now. */
  readonly revisedTotalMinor: number;
  /** Revised minus till, signed. */
  readonly deltaMinor: number;
  readonly openedAt: string;
  readonly resolvedAt: string | null;
  readonly attemptCount: number;
  /** A stable code for the last unsuccessful try to tell the till; never the vendor's own words. */
  readonly failureCode: string | null;
}

/** Mirrors `OrderPosRevisionController.TillRevisionResponse`. */
export interface TillRevisionView {
  /** The open notice, or the latest closed one; null when the till and the order never disagreed. */
  readonly notice: TillRevisionNotice | null;
  /** True only while a notice is open and the binding it was exported through can be told (`ORDER_REVISION`). */
  readonly canTell: boolean;
}

/** Mirrors `PosOrderRevisionService.Outcome`. */
export type TillRevisionSendOutcome =
  | 'TOLD'
  | 'NO_OPEN_NOTICE'
  | 'NOT_AVAILABLE'
  | 'REJECTED'
  | 'RETRYABLE'
  | 'UNCERTAIN'
  | 'SUPERSEDED';

/** The body of both actions: the revision of the notice the operator saw (`STALE_VERSION` when it has moved). */
interface TillRevisionAction {
  readonly expectedRevision: number;
}

/** Mirrors `OrderPosRevisionController.SendResponse`. Always a `200`: a refusal is an ordinary answer. */
export interface TillRevisionSendResult {
  readonly outcome: TillRevisionSendOutcome;
  readonly code: string | null;
  readonly message: string | null;
  readonly revision: TillRevisionView;
}

/**
 * `OrderPosRevisionController` (ADR 0137, gap map row `4.2c`): an order the till already holds whose
 * total moved after a weight was captured.
 *
 * Each mutation is one intent held per order until the platform has answered: an unchanged click after a
 * lost response reuses the key, so a request that landed is replayed rather than repeated. Any answer at
 * all, including a refusal or an unknown outcome, settles the intent, because the next click is then a
 * deliberate second attempt and replaying the first answer would never reach the till.
 */
@Injectable({ providedIn: 'root' })
export class TillRevisionApi {
  private readonly api = inject(ApiClient);
  private readonly intents = new IntentCommandRegistry<TillRevisionAction>();

  async forOrder(scope: LocationScope, orderId: string): Promise<TillRevisionView> {
    const result = await firstValueFrom(
      this.api.get<TillRevisionView>(posPaths.orderTillRevision(scope, orderId)),
    );
    return result.value;
  }

  /**
   * Tells the till the total of the notice the operator saw. A changed revision is a different intent, so it
   * gets a fresh key: replaying the answer to the old one would tell the till the old amount.
   */
  send(
    scope: LocationScope,
    orderId: string,
    expectedRevision: number,
  ): Promise<TillRevisionSendResult> {
    const id = `send:${orderId}`;
    return firstValueFrom(
      this.api
        .post<TillRevisionAction, TillRevisionSendResult>(
          posPaths.orderTillRevisionSend(scope, orderId),
          this.intents.next(id, { expectedRevision }),
        )
        .pipe(tap(() => this.intents.forget(id))),
    );
  }

  acknowledge(
    scope: LocationScope,
    orderId: string,
    expectedRevision: number,
  ): Promise<TillRevisionView> {
    const id = `acknowledge:${orderId}`;
    return firstValueFrom(
      this.api
        .post<TillRevisionAction, TillRevisionView>(
          posPaths.orderTillRevisionAcknowledge(scope, orderId),
          this.intents.next(id, { expectedRevision }),
        )
        .pipe(tap(() => this.intents.forget(id))),
    );
  }
}
