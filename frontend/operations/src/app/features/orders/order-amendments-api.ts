import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { AmendmentResponse } from './order-amendments';

/** `OperationsOrderController.AddLineRequest` — one line `ADD_LINES` carries. */
export interface AmendmentAddLine {
  readonly variantId: string;
  readonly quantity: number;
  readonly modifierOptionIds: readonly string[];
}

/** `OperationsOrderController.DeliveryAddressRequest` — ADR 0039 `CHANGE_DELIVERY_ADDRESS`. */
export interface AmendmentDeliveryAddress {
  readonly line1: string;
  readonly line2?: string | null;
  readonly city: string;
  readonly district?: string | null;
  readonly postalCode?: string | null;
  readonly entrance?: string | null;
  readonly floor?: string | null;
  readonly apartment?: string | null;
  readonly landmark?: string | null;
  readonly latitude: number;
  readonly longitude: number;
  readonly deliveryInstructions?: string | null;
}

/** One issued command — exactly the fields `OperationsOrderController.AmendmentCommandRequest` reads for its type. */
type AmendmentCommandRequest =
  | { readonly type: 'SET_KITCHEN_NOTE'; readonly kitchenNote: string }
  | { readonly type: 'SET_CALLBACK_REQUESTED'; readonly callbackRequested: boolean }
  | { readonly type: 'SET_CASH_TENDERED'; readonly cashTenderedMinor: number }
  | { readonly type: 'SET_COURIER_NOTE'; readonly courierNote: string }
  | { readonly type: 'SET_INTERNAL_NOTE'; readonly internalNote: string }
  // -------------------------------------------------- wave 10 financial commands
  | { readonly type: 'ADD_LINES'; readonly lines: readonly AmendmentAddLine[] }
  | {
      readonly type: 'CHANGE_LINE_QUANTITY';
      readonly orderLineId: string;
      readonly quantity: number;
    }
  | { readonly type: 'CHANGE_PAYMENT_METHOD'; readonly paymentMethodCode: string }
  | {
      readonly type: 'CHANGE_DELIVERY_ADDRESS';
      readonly deliveryAddress: AmendmentDeliveryAddress;
      readonly recipientName: string;
      readonly recipientPhone: string;
    }
  | { readonly type: 'CHANGE_FULFILLMENT_TIME'; readonly promisedAt: string }
  | {
      readonly type: 'CHANGE_CONTACT';
      readonly recipientName: string;
      readonly recipientPhone: string;
    };

/**
 * `POST/GET .../orders/{orderId}/amendments`, `POST .../amendments/{id}/confirmation`
 * (ADR 0039, wave P10 — the console's first amendment client, gap map `1.2h`).
 *
 * Every mutation carries `Idempotency-Key` ({@link command}) and `If-Match`
 * (`expectedVersion`, per {@link ApiClient.post}), exactly as orders.md §4.1
 * requires of every mutation on this platform. `reasonCode` is fixed per
 * method rather than operator-entered: none of the eleven built commands has
 * a dialog collecting a free-form reason (§4.4's own table marks every one of
 * the five originals' "Consequences the dialog must state before confirm"
 * columns "none", and the six wave-10 financial commands instead show their
 * own priced-delta/attestation step — see `order-amendment-confirm-dialog.ts`
 * — rather than a reason field), so this is a real, auditable statement of
 * which command ran — the same reason `order-actions.ts`'s own
 * `advanceReasonCode` exists — rather than a placeholder.
 *
 * The six wave-10 methods below ({@link addLines} through {@link
 * changeContact}) are grouped separately: three reprice
 * (`applyImmediately: false`, always followed by the priced-delta
 * confirmation step) and three never do (`applyImmediately: true`, applied
 * in the same call exactly like the five originals). See each method's own
 * doc for which.
 */
@Injectable({ providedIn: 'root' })
export class OrderAmendmentsApi {
  private readonly api = inject(ApiClient);

  /** `Комментарий кухне` (§3.6, §4.4) — arrives on the payload; `OrderDetailResponse.kitchenNote` is where it reads back. */
  setKitchenNote(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    note: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'SET_KITCHEN_NOTE', {
      type: 'SET_KITCHEN_NOTE',
      kitchenNote: note,
    });
  }

  /**
   * `Требуется звонок` (§3.7, §4.4) — the same command both raises and clears
   * the flag; `requested: false` is what ADR 0039 calls resolution.
   */
  setCallbackRequested(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    requested: boolean,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'SET_CALLBACK_REQUESTED', {
      type: 'SET_CALLBACK_REQUESTED',
      callbackRequested: requested,
    });
  }

  /**
   * `Сдача с` (§3.5, §4.4). A later call that pushes the total above
   * `amountMinor` comes back with `CASH_TENDERED_INSUFFICIENT` in
   * {@link AmendmentResponse.warnings} — acknowledgeable, never a refusal, the
   * customer can simply hand over more.
   */
  setCashTendered(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    amountMinor: number,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'SET_CASH_TENDERED', {
      type: 'SET_CASH_TENDERED',
      cashTenderedMinor: amountMinor,
    });
  }

  /**
   * `Комментарий курьеру` (§3.6) — ADR 0113 (wave P10). Operator to courier,
   * never rendered to the customer. No `ordering.orders` column exists for it
   * (this wave carried no migration number for one), so unlike
   * {@link setKitchenNote} there is nothing to read back from the order
   * detail — {@link history} is the only read path.
   */
  setCourierNote(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    note: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'SET_COURIER_NOTE', {
      type: 'SET_COURIER_NOTE',
      courierNote: note,
    });
  }

  /** `Внутренняя заметка` (§3.6) — ADR 0113. The same shape as {@link setCourierNote}, operator to operator. */
  setInternalNote(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    note: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'SET_INTERNAL_NOTE', {
      type: 'SET_INTERNAL_NOTE',
      internalNote: note,
    });
  }

  // -------------------------------------------------- wave 10 financial commands (rows 1.2c/2.1d)

  /**
   * `ADD_LINES` — increase only, priced through the real `CartPricingPort`
   * path (`OrderAmendmentService`'s own doc). Always proposed with
   * `applyImmediately: false`: a line addition raises the total, and the
   * server refuses `applyImmediately` for anything that does (see
   * {@link AmendRequest}'s own doc on the Java side) — `order-detail-pane.ts`
   * always follows this call with the priced-delta confirmation step
   * ({@link confirm}), never assumes it applied.
   */
  addLines(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    lines: readonly AmendmentAddLine[],
  ): Observable<AmendmentResponse> {
    return this.propose(
      scope,
      orderId,
      expectedVersion,
      'ADD_LINES',
      { type: 'ADD_LINES', lines },
      false,
    );
  }

  /** `CHANGE_LINE_QUANTITY` — increase only, same not-applied-yet shape as {@link addLines}. */
  changeLineQuantity(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    orderLineId: string,
    quantity: number,
  ): Observable<AmendmentResponse> {
    return this.propose(
      scope,
      orderId,
      expectedVersion,
      'CHANGE_LINE_QUANTITY',
      { type: 'CHANGE_LINE_QUANTITY', orderLineId, quantity },
      false,
    );
  }

  /**
   * `CHANGE_PAYMENT_METHOD` — `CASH` at either end applies directly (never
   * reprices), so this proposes with `applyImmediately: true` like the five
   * non-financial commands above. Anything else the server cannot settle
   * this build (a provider-paid order moving to another online method) comes
   * back as `PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND`, a plain refusal
   * `order-errors.ts#describeApiError` already renders.
   */
  changePaymentMethod(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    paymentMethodCode: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'CHANGE_PAYMENT_METHOD', {
      type: 'CHANGE_PAYMENT_METHOD',
      paymentMethodCode,
    });
  }

  /**
   * `CHANGE_DELIVERY_ADDRESS` — reprices the ADR 0037 zone fee, which can
   * raise or lower the total (the one command among the six that can lower
   * it), so this always proposes `applyImmediately: false`, exactly like
   * {@link addLines}.
   */
  changeDeliveryAddress(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    deliveryAddress: AmendmentDeliveryAddress,
    recipientName: string,
    recipientPhone: string,
  ): Observable<AmendmentResponse> {
    return this.propose(
      scope,
      orderId,
      expectedVersion,
      'CHANGE_DELIVERY_ADDRESS',
      { type: 'CHANGE_DELIVERY_ADDRESS', deliveryAddress, recipientName, recipientPhone },
      false,
    );
  }

  /** `CHANGE_FULFILLMENT_TIME` — a field update; never reprices, so `applyImmediately: true`. */
  changeFulfillmentTime(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    promisedAt: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'CHANGE_FULFILLMENT_TIME', {
      type: 'CHANGE_FULFILLMENT_TIME',
      promisedAt,
    });
  }

  /**
   * `CHANGE_CONTACT` — ADR 0029-protected recipient name/phone. Financial in
   * ADR 0039's matrix only in the sense that it is a wave-10 command; it
   * never reprices, so `applyImmediately: true`.
   */
  changeContact(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    recipientName: string,
    recipientPhone: string,
  ): Observable<AmendmentResponse> {
    return this.propose(scope, orderId, expectedVersion, 'CHANGE_CONTACT', {
      type: 'CHANGE_CONTACT',
      recipientName,
      recipientPhone,
    });
  }

  private propose(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    reasonCode: string,
    issuedCommand: AmendmentCommandRequest,
    applyImmediately = true,
  ): Observable<AmendmentResponse> {
    return this.api.post<
      {
        commands: readonly AmendmentCommandRequest[];
        applyImmediately: boolean;
        reasonCode: string;
      },
      AmendmentResponse
    >(
      operationsPaths.orderAmendments(scope, orderId),
      command({
        commands: [issuedCommand],
        applyImmediately,
        reasonCode: `OPERATIONS_${reasonCode}`,
      }),
      { expectedVersion },
    );
  }

  /**
   * Records the customer's recorded agreement to an amendment that raised the
   * total (§4.4). None of the five commands above ever needs this — all take
   * `PRICED -> APPLIED` directly — but the path is real and this is its one
   * caller today, ready for the first financial command that does.
   */
  confirm(
    scope: LocationScope,
    orderId: string,
    amendmentId: string,
    expectedVersion: number,
    channel: string,
  ): Observable<void> {
    return this.api.post<{ channel: string }, void>(
      operationsPaths.orderAmendmentConfirmation(scope, orderId, amendmentId),
      command({ channel }),
      { expectedVersion },
    );
  }

  /** The amendment history view (§3.6/§3.10, `:713`) — every amendment this order has ever had, applied or not. */
  async history(scope: LocationScope, orderId: string): Promise<readonly AmendmentResponse[]> {
    const result = await firstValueFrom(
      this.api.get<AmendmentResponse[]>(operationsPaths.orderAmendments(scope, orderId)),
    );
    return result.value;
  }
}
