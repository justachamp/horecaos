import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ScanMethod } from '../../shared/ui/datamatrix-input';

/** `OperationsOrderMarksController.CaptureMarkResponse`. The response never carries the code. */
export interface CapturedMark {
  readonly markId: string;
  readonly sequence: number;
  /** The product the code claims: a product identifier and not a secret. */
  readonly gtin: string;
  readonly captured: number;
  readonly required: number;
  readonly orderMarksComplete: boolean;
  /** False when the product's classification states no barcode to compare with. */
  readonly gtinChecked: boolean;
}

/** `OperationsOrderMarksController.RemoveMarkResponse`. */
export interface RemovedMark {
  readonly captured: number;
  readonly required: number;
}

/** `OperationsOrderMarksController.MarkResponse`. */
export interface ScannedUnit {
  readonly markId: string;
  readonly sequence: number;
  readonly gtin: string;
  readonly stage: 'PICK' | 'HANDOVER';
  readonly capturedAt: string;
  readonly capturedBy: string;
}

/** `OperationsOrderMarksController.MarkedLineResponse`. */
export interface MarkedLine {
  readonly orderLineId: string;
  readonly lineNumber: number;
  readonly productName: string;
  readonly required: number;
  readonly captured: number;
  readonly marks: readonly ScannedUnit[];
}

/** `OperationsOrderMarksController.OrderMarksResponse`. */
export interface OrderMarks {
  readonly orderId: string;
  readonly lines: readonly MarkedLine[];
}

/** The coded reasons a scan may be taken back for (`MarkReleaseReason`): no free text, where a customer's name would arrive. */
export const REMOVAL_REASONS = ['MISSCAN', 'UNIT_REPLACED', 'UNIT_DAMAGED'] as const;
export type RemovalReason = (typeof REMOVAL_REASONS)[number];

/**
 * ADR 0161, gap map row `X.35` -- marked goods at the pass: capture one scanned unit, take one back, read the
 * progress.
 *
 * Every scan carries a **fresh** `Idempotency-Key` and the code only ever travels in the request body. The
 * command is built per call and dropped when the call settles, deliberately not held in an
 * `IntentCommandRegistry`: a registry keeps the last body for a retry, and the body is a marking code. A scan
 * whose response was lost is simply scanned again; the server answers `MARKING_CODE_ALREADY_CAPTURED` with
 * `onThisLine`, which the panel reads as "already scanned" and not as a duplicate.
 */
@Injectable({ providedIn: 'root' })
export class OrderMarkingApi {
  private readonly api = inject(ApiClient);

  list(scope: LocationScope, orderId: string): Observable<OrderMarks> {
    return this.api
      .get<OrderMarks>(operationsPaths.orderMarks(scope, orderId))
      .pipe(map((result) => result.value));
  }

  capture(
    scope: LocationScope,
    orderId: string,
    lineId: string,
    code: string,
    entryMethod: ScanMethod,
  ): Observable<CapturedMark> {
    return this.api.post<{ code: string; entryMethod: ScanMethod }, CapturedMark>(
      operationsPaths.orderLineMarks(scope, orderId, lineId),
      command({ code, entryMethod }),
    );
  }

  remove(
    scope: LocationScope,
    orderId: string,
    lineId: string,
    markId: string,
    reasonCode: RemovalReason,
  ): Observable<RemovedMark> {
    return this.api.post<{ reasonCode: RemovalReason }, RemovedMark>(
      operationsPaths.orderLineMarkRemovals(scope, orderId, lineId, markId),
      command({ reasonCode }),
    );
  }
}
