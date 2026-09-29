import { Injectable } from '@angular/core';

import { CartService } from './cart.service';

/**
 * The basket a guest fills at a table (ADR 0047, `ORDER_AND_PAY`): a `DINE_IN`
 * cart, opened on the table's own `QR_TABLE` channel and remembered against the
 * table's open session.
 *
 * A separate instance of {@link CartService}, on purpose. That class holds
 * *one* cart in a signal and remembers its id against the location, which is
 * exactly right for the delivery and pickup basket and exactly wrong for a table:
 *
 * - a guest who scans a table at the branch they also order delivery from would
 *   have the table's cart come back as their delivery basket (and their delivery
 *   basket come back on the table), because both are "the cart at that
 *   location";
 * - every delivery screen re-`ensure`s its own cart into the shared signal, so a
 *   table screen that shared it would price and check out whichever cart was
 *   loaded last.
 *
 * Everything else -- versioned writes, pricing, payment methods, checkout -- is
 * inherited unchanged: a table order is an ordinary order, placed by the
 * customer's own session. What ties it to the table is the cart's binding
 * ({@link CartService.bindTable}, made through `DineInService.bindCartToTable`),
 * which makes checkout put the order on the table's bill itself; the round attach
 * afterwards remains for a cart that was never bound.
 *
 * <h2>What the id is remembered against</h2>
 *
 * The table's session, not the location: callers pass the session id as the
 * `scope` of {@link CartService.ensure} and {@link CartService.discard}. A
 * basket half-filled on one evening therefore is not reopened by the next
 * evening's scan of the same branch, whose session is a different one.
 */
@Injectable({ providedIn: 'root' })
export class DineInCartService extends CartService {
  protected override get storagePrefix(): string {
    return 'horecaos_cart_dinein_';
  }
}
