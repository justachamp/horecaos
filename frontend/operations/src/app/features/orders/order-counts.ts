import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { OrderCountsResponse } from './order-detail';
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
 * Per-tab badge counts — `docs/operations-spec/orders.md` §2.3.
 *
 * **The swap this class now performs.** `GET .../orders/counts` exists
 * (`OperationsOrderController.counts`), and six of the seven tabs map onto it
 * exactly — see {@link fromEndpoint}. `forOrders` calls it first and only
 * falls back to deriving every tab from the already-fetched `orders` page (the
 * original implementation, kept verbatim in {@link deriveAll}) when the call
 * fails: a denied capability, a network error, or a server that has not yet
 * deployed the endpoint. Nothing that calls `forOrders` — `order-queue.ts`'s
 * tab bar — had to change shape for this, because the return type was always
 * the real one.
 *
 * **`Внимание` is the exception, on purpose, not an oversight.**
 * `JdbcOrderStore.counts`'s own Java doc says why the aggregate excludes it:
 * "Внимание's live severity queue (late orders, stuck processes) is
 * deliberately not among these columns... a count computed here would be
 * wrong five seconds after it was cached." The endpoint also does not break
 * `PAYMENT_FAILED` out from the other terminal-adjacent statuses, which
 * `Внимание` needs too. So `attention` is *always* derived client-side from
 * `orders`, regardless of whether the endpoint call below succeeds — it is
 * the one tab this seam can never close, by the server's own design.
 */
@Injectable({ providedIn: 'root' })
export class OrderCounts {
  private readonly api = inject(ApiClient);

  async forOrders(
    scope: LocationScope,
    orders: readonly CountableOrder[],
    now: Date,
  ): Promise<TabCounts> {
    const attention = countMembers('attention', orders, now);

    try {
      const result = await firstValueFrom(
        this.api.get<OrderCountsResponse>(operationsPaths.orderCounts(scope)),
      );
      if (!isOrderCountsResponse(result.value)) {
        // Not a shape the current server sends. Safer to fall back than to
        // render `NaN`/`undefined` badges from a response this client
        // misread — the same "throw on the unexpected" instinct as
        // `money.ts`'s unknown-currency guard.
        return this.deriveAll(orders, now);
      }
      return fromEndpoint(result.value, attention);
    } catch {
      // Network failure, a capability the operator does not hold, or a server
      // that has not deployed the endpoint yet — the documented fallback,
      // wrong only in the way the class doc above already accepts.
      return this.deriveAll(orders, now);
    }
  }

  private deriveAll(orders: readonly CountableOrder[], now: Date): TabCounts {
    const counts = { ...zeroTabCounts() } as Record<OrderTabId, number>;
    for (const tab of ORDER_TABS) {
      counts[tab] = countMembers(tab, orders, now);
    }
    return counts;
  }
}

function isOrderCountsResponse(value: unknown): value is OrderCountsResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<OrderCountsResponse>;
  return (
    typeof candidate.newOrders === 'number' &&
    typeof candidate.inKitchen === 'number' &&
    typeof candidate.ready === 'number' &&
    typeof candidate.fulfilling === 'number' &&
    typeof candidate.completed === 'number' &&
    typeof candidate.cancelled === 'number' &&
    typeof candidate.total === 'number'
  );
}

function countMembers(tab: OrderTabId, orders: readonly CountableOrder[], now: Date): number {
  return orders.filter((order) =>
    isOrderTabMember(tab, { status: order.status, severityLevel: computeOrderSeverity(order, now).level }),
  ).length;
}

/**
 * `OrderCountsResponse`'s fields, as `JdbcOrderStore.counts`'s SQL defines
 * them, onto the six tabs it can answer for:
 *
 *   - `newOrders`      = RECEIVED ∪ PAYMENT_AUTHORIZING ∪ AWAITING_APPROVAL  → `new`, exactly
 *   - `inKitchen`+`ready` = CONFIRMED ∪ PREPARING ∪ READY                    → `preparing`, exactly
 *   - `fulfilling`                                                          → `delivering`, exactly
 *   - `completed`                                                           → `completed`, exactly
 *   - `cancelled`       = CANCELLED ∪ REJECTED ∪ EXPIRED                     → `cancelled`, exactly
 *   - `total`                                                               → `all`, exactly
 */
function fromEndpoint(response: OrderCountsResponse, attention: number): TabCounts {
  return {
    attention,
    new: response.newOrders,
    preparing: response.inKitchen + response.ready,
    delivering: response.fulfilling,
    completed: response.completed,
    cancelled: response.cancelled,
    all: response.total,
  };
}
