import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { OrderAddressReveal, OrderLineNoteReveal, OrderPhoneReveal } from './order-detail';

/**
 * The three ADR 0029 PII reveal calls (orders.md §1.5, §3.4, §3.8): the
 * customer's phone, their delivery address, and one line's note. Each is a
 * separate, capability-gated `GET` carrying a stated `purpose` — never folded
 * into the order read, and never cached across a reveal and a later copy: see
 * the "never reads prefetched data" note on {@link revealPhone} below, which
 * is why this class has no state of its own.
 */
@Injectable({ providedIn: 'root' })
export class OrderRevealApi {
  private readonly api = inject(ApiClient);

  /**
   * `GET .../customer/phone?purpose=`.
   *
   * Called fresh for *every* reveal — the click-to-call control and the
   * copy-to-clipboard control both invoke this independently, each with its
   * own `purpose`, rather than either one reading a value the other already
   * fetched. "Copy-to-clipboard of a phone counts as a reveal... it now
   * performs the reveal call rather than copying from a payload that already
   * contained the number" (§1.5) — reusing an in-memory value here would be
   * the same mistake the spec calls out, just moved one layer up.
   */
  revealPhone(scope: LocationScope, orderId: string, purpose: string): Observable<OrderPhoneReveal> {
    return this.api
      .get<OrderPhoneReveal>(operationsPaths.orderCustomerPhone(scope, orderId), { params: { purpose } })
      .pipe(map((response) => response.value));
  }

  /** `GET .../customer/address?purpose=` — §3.8, structured fields plus the coordinate. */
  revealAddress(
    scope: LocationScope,
    orderId: string,
    purpose: string,
  ): Observable<OrderAddressReveal> {
    return this.api
      .get<OrderAddressReveal>(operationsPaths.orderCustomerAddress(scope, orderId), {
        params: { purpose },
      })
      .pipe(map((response) => response.value));
  }

  /** `GET .../lines/{lineId}/note?purpose=` — §3.4, the customer's own words about one line. */
  revealLineNote(
    scope: LocationScope,
    orderId: string,
    lineId: string,
    purpose: string,
  ): Observable<OrderLineNoteReveal> {
    return this.api
      .get<OrderLineNoteReveal>(operationsPaths.orderLineNote(scope, orderId, lineId), {
        params: { purpose },
      })
      .pipe(map((response) => response.value));
  }
}
