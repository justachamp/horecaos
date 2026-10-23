import { Injectable, inject } from '@angular/core';
import { firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** `DROPPED`, `GAINED` or `CHANGED`: what a re-quote would do to one promotion on the order. */
export type RequoteChange = 'DROPPED' | 'GAINED' | 'CHANGED';

/** Mirrors `ScheduledOrderRequoteController.PromotionChangeResponse`. */
export interface RequotePromotionChange {
  readonly promotionId: string;
  readonly change: RequoteChange;
  /** The promotion's signed effect on the order the guest holds; a discount is negative. */
  readonly heldMinor: number;
  /** The same if the order were priced again at the checkpoint. */
  readonly requoteMinor: number;
}

/**
 * Mirrors `ScheduledOrderRequoteController.RequoteResponse` (ADR 0140, ADR 0019).
 *
 * A finding is evidence and never an instruction: the order keeps the price the guest was quoted. When
 * the finding says `CHANGED`, a person phones the guest or cancels the order with the ordinary cancel
 * path; this screen offers neither.
 */
export interface RequoteFinding {
  readonly id: string;
  /** `CHECKPOINT` (the sweep an hour before the promised time) or `OPERATOR` (a re-check somebody asked for). */
  readonly trigger: 'CHECKPOINT' | 'OPERATOR';
  readonly checkpointAt: string;
  /** `UNCHANGED`, `CHANGED` (the order keeps the price it was taken at) or `NOT_PRICEABLE`. */
  readonly outcome: 'UNCHANGED' | 'CHANGED' | 'NOT_PRICEABLE';
  /** Why it could not be priced again, for `NOT_PRICEABLE`. */
  readonly refusalCode: string | null;
  readonly currency: string;
  readonly heldTotalMinor: number;
  readonly heldDiscountMinor: number;
  readonly requoteTotalMinor: number | null;
  readonly requoteDiscountMinor: number | null;
  /** What the guest would pay more (positive) or less (negative) if the order were priced again. */
  readonly deltaTotalMinor: number | null;
  readonly promotionChanges: readonly RequotePromotionChange[];
  readonly createdAt: string;
}

/**
 * `ScheduledOrderRequoteController` (row `6.1`): what the checkpoint found about a scheduled order's
 * promotions, and the operator's re-check. Neither changes the order.
 */
@Injectable({ providedIn: 'root' })
export class OrderRequoteApi {
  private readonly api = inject(ApiClient);

  /**
   * A re-check carries no body, so the order is what identifies the intent: a second click while the first
   * is in flight, or a retry after a lost response, reuses the key and is replayed rather than judged twice.
   * Forgotten on a settled answer, so the next click is a new check.
   */
  private readonly recheckIntents = new IntentCommandRegistry<Record<string, never>>();

  /** At most twenty findings, newest first. */
  async findings(scope: LocationScope, orderId: string): Promise<readonly RequoteFinding[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RequoteFinding[]>(
        operationsPaths.orderPromotionRequotes(scope, orderId),
      ),
    );
    return result.value;
  }

  /**
   * Prices the order's live basket again now and records the difference. Refused (409,
   * `NOT_AWAITING_CHECKPOINT`) for an order that is not a scheduled order its kitchen has not started.
   */
  async recheck(scope: LocationScope, orderId: string): Promise<RequoteFinding> {
    const intent = this.recheckIntents.next(orderId, {});
    return firstValueFrom(
      this.api
        .post<Record<string, never>, RequoteFinding>(
          operationsPaths.orderPromotionRequotes(scope, orderId),
          intent,
        )
        .pipe(tap(() => this.recheckIntents.forget(orderId))),
    );
  }
}
