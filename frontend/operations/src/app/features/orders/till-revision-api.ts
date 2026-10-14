import { Injectable, inject } from '@angular/core';
import { firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { TenantScope, posPaths } from '../../core/api/pos-paths';

/** `OpenAPI`: `OPEN` until the till was told or a person changed it; `TOLD` and `ACKNOWLEDGED` are the two ways it ends. */
export type TillRevisionStatus = 'OPEN' | 'TOLD' | 'ACKNOWLEDGED';

/** Mirrors `OrderPosRevisionController.NoticeView`: money and codes only, never a customer or a line. */
export interface TillRevisionNotice {
  readonly noticeId: string;
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
  'TOLD' | 'NO_OPEN_NOTICE' | 'NOT_AVAILABLE' | 'REJECTED' | 'RETRYABLE' | 'UNCERTAIN';

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
  private readonly intents = new IntentCommandRegistry<Record<string, never>>();

  async forOrder(scope: TenantScope, orderId: string): Promise<TillRevisionView> {
    const result = await firstValueFrom(
      this.api.get<TillRevisionView>(posPaths.orderTillRevision(scope, orderId)),
    );
    return result.value;
  }

  send(scope: TenantScope, orderId: string): Promise<TillRevisionSendResult> {
    const id = `send:${orderId}`;
    return firstValueFrom(
      this.api
        .post<Record<string, never>, TillRevisionSendResult>(
          posPaths.orderTillRevisionSend(scope, orderId),
          this.intents.next(id, {}),
        )
        .pipe(tap(() => this.intents.forget(id))),
    );
  }

  acknowledge(scope: TenantScope, orderId: string): Promise<TillRevisionView> {
    const id = `acknowledge:${orderId}`;
    return firstValueFrom(
      this.api
        .post<Record<string, never>, TillRevisionView>(
          posPaths.orderTillRevisionAcknowledge(scope, orderId),
          this.intents.next(id, {}),
        )
        .pipe(tap(() => this.intents.forget(id))),
    );
  }
}
