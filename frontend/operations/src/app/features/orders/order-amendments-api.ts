import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { AmendmentResponse } from './order-amendments';

/** One issued command — exactly the fields `OperationsOrderController.AmendmentCommandRequest` reads for its type. */
type AmendmentCommandRequest =
  | { readonly type: 'SET_KITCHEN_NOTE'; readonly kitchenNote: string }
  | { readonly type: 'SET_CALLBACK_REQUESTED'; readonly callbackRequested: boolean }
  | { readonly type: 'SET_CASH_TENDERED'; readonly cashTenderedMinor: number }
  | { readonly type: 'SET_COURIER_NOTE'; readonly courierNote: string }
  | { readonly type: 'SET_INTERNAL_NOTE'; readonly internalNote: string };

/**
 * `POST/GET .../orders/{orderId}/amendments`, `POST .../amendments/{id}/confirmation`
 * (ADR 0039, wave P10 — the console's first amendment client, gap map `1.2h`).
 *
 * Every mutation carries `Idempotency-Key` ({@link command}) and `If-Match`
 * (`expectedVersion`, per {@link ApiClient.post}), exactly as orders.md §4.1
 * requires of every mutation on this platform. `reasonCode` is fixed per
 * method rather than operator-entered: none of the five built commands has a
 * dialog collecting one (§4.4's own table marks every one of their
 * "Consequences the dialog must state before confirm" columns "none"), so
 * this is a real, auditable statement of which command ran — the same reason
 * `order-actions.ts`'s own `advanceReasonCode` exists — rather than a
 * placeholder.
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

  private propose(
    scope: LocationScope,
    orderId: string,
    expectedVersion: number,
    reasonCode: string,
    issuedCommand: AmendmentCommandRequest,
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
        applyImmediately: true,
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
