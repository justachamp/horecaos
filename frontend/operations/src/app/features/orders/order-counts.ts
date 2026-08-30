import { Injectable } from '@angular/core';

import { OrderSeverityInput, computeOrderSeverity } from './order-severity';
import { ORDER_TABS, OrderTabId, isOrderTabMember } from './order-tabs';

export type TabCounts = Readonly<Record<OrderTabId, number>>;

/** What {@link OrderCounts.forOrders} needs from an order: severity's inputs, nothing more. */
export type CountableOrder = OrderSeverityInput;

export function zeroTabCounts(): TabCounts {
  return {
    attention: 0,
    new: 0,
    preparing: 0,
    delivering: 0,
    completed: 0,
    cancelled: 0,
    all: 0,
  };
}

/**
 * Per-tab badge counts — **the seam** `docs/operations-spec/orders.md` §2.3
 * asks for.
 *
 * The spec's real source is `COUNTERS` (ADR 0045, not built) or, until then,
 * `GET .../orders/counts` (also not built). Neither exists, so
 * {@link forOrders} derives counts from whatever page of orders the board
 * already fetched. That is wrong in the one way the spec calls out — a count
 * from a truncated page can undercount a tab, whereas §2.3 wants each tab's
 * count computed "before the other filters apply within that tab's own
 * scope" — and right in every way that matters for a first render: it needs
 * no new endpoint, and it isolates the wrongness to this one class.
 *
 * **The swap, when the endpoint exists:** replace this method's body with an
 * `ApiClient.get<TabCounts>(operationsPaths.orderCounts(scope))` call. Nothing
 * that calls {@link forOrders} — `order-queue.ts`'s tab bar — has to change,
 * because the return shape is already the real one.
 */
@Injectable({ providedIn: 'root' })
export class OrderCounts {
  forOrders(orders: readonly CountableOrder[], now: Date): TabCounts {
    const members = orders.map((order) => ({
      status: order.status,
      severityLevel: computeOrderSeverity(order, now).level,
    }));

    const counts = { ...zeroTabCounts() } as Record<OrderTabId, number>;
    for (const tab of ORDER_TABS) {
      counts[tab] = members.filter((member) => isOrderTabMember(tab, member)).length;
    }
    return counts;
  }
}
