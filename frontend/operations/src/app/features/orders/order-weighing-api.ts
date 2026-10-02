import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** `OperationsOrderWeighingController.ActualWeightResponse`. */
export interface ActualWeightResult {
  readonly orderId: string;
  readonly lineId: string;
  /** False when the weight was the one already on the line: nothing was written. */
  readonly changed: boolean;
  readonly actualWeightGrams: number;
  /** The weighed line's own corrected amount. */
  readonly lineFinalAmountMinor: number;
  readonly totalMinor: number;
  /** The order total after minus before, signed. */
  readonly deltaTotalMinor: number;
  readonly revision: number;
  /** The order's version after the write — what the next write must quote in `If-Match`. */
  readonly orderVersion: number;
}

/** `ActualWeightRequest`: the weighed total of the whole line, all its units together. */
interface ActualWeightRequest {
  readonly actualWeightGrams: number;
}

/**
 * `PUT .../orders/{orderId}/lines/{lineId}/actual-weight` (ADR 0137, gap map row 4.2c) — the
 * scale at the pass. A catchweight line is priced at its nominal weight until it is weighed; this
 * writes the weight and the platform re-prices the order from it, appends a `CATCHWEIGHT`
 * revision and, for an order still to be paid at the door, restates what the courier collects.
 *
 * The order's version rides in `If-Match` (two screens can be weighing the same order), and the
 * `Idempotency-Key` is held per line and weight so a retry after a lost response is the same
 * intent rather than a second weighing.
 */
@Injectable({ providedIn: 'root' })
export class OrderWeighingApi {
  private readonly api = inject(ApiClient);
  private readonly intents = new IntentCommandRegistry<ActualWeightRequest>();

  captureActualWeight(
    scope: LocationScope,
    orderId: string,
    lineId: string,
    actualWeightGrams: number,
    expectedVersion: number,
  ): Observable<ActualWeightResult> {
    const id = `${orderId}:${lineId}`;
    return this.api
      .put<ActualWeightRequest, ActualWeightResult>(
        operationsPaths.orderLineActualWeight(scope, orderId, lineId),
        this.intents.next(id, { actualWeightGrams }),
        { expectedVersion },
      )
      .pipe(tap(() => this.intents.forget(id)));
  }
}
