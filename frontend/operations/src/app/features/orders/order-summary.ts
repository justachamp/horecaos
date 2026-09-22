import { OrderActionResponse } from './order-actions';

/**
 * Mirrors `OrderSummaryResponse` in `OperationsOrderController.java` — the
 * entire wire shape `GET /api/v1/tenants/{t}/brands/{b}/locations/{l}/orders`
 * returns today, newest first, and the summary embedded in
 * `OrderDetailResponse.summary`.
 *
 * This is far short of `docs/operations-spec/orders.md` §2.5's default
 * column set: no branch name, no customer, no line summary, no payment
 * projection, no courier, no process state. `order-queue.ts` documents
 * exactly which columns render because the data exists and which are
 * withheld because it does not — never fabricated client-side.
 */
export interface OrderSummaryResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  /** One of the twelve in `order-status.ts`, or something newer this client does not know yet. */
  readonly status: string;
  /** RFC 3339, UTC. */
  readonly createdAt: string;
  /** RFC 3339, UTC. Present only once the order has entered `AWAITING_APPROVAL`. */
  readonly approvalDeadlineAt?: string | null;
  readonly channelCode?: string | null;
  /** `DELIVERY` | `PICKUP` | `DINE_IN` (`uz.horecaos.platform.tenancy.api.FulfillmentMode`), or unset. */
  readonly fulfillmentMode?: string | null;
  /**
   * ADR 0036's promise, decided once at checkout (ADR 0102 put it on this
   * response; wave P06 is the first reader). RFC 3339, UTC. Null means the
   * order carries no promise at all — `order-severity.ts`'s own no-promise
   * fallback applies then, never a fabricated time.
   */
  readonly promisedAt?: string | null;
  /**
   * `MANUAL_ACTION_REQUIRED` or `FAILED_RETRYABLE` when a process needs
   * attention, null otherwise (ADR 0102). `order-severity.ts`'s `BLOCKED`
   * level is exactly `processAttention === 'MANUAL_ACTION_REQUIRED'` —
   * `FAILED_RETRYABLE` is retried automatically and is not this severity's
   * concern.
   */
  readonly processAttention?: string | null;
  readonly totalMinor: number;
  readonly currency: string;
  /**
   * H4: `OperationsOrderController.OrderSummaryResponse` has carried these
   * two `long` fields (always sent, never omitted) since 2026-09-11 —
   * `feeMinor` from `CheckoutOrderWriter`'s checkout-quote fee, `discountMinor`
   * from the applied promotions. §1.3's five-row money-panel rule
   * (`subtotal + tax + fee − discount = total`) needs both; see
   * `order-money.ts` and `order-detail-pane.html`'s Деньги section for where
   * they render.
   */
  readonly feeMinor: number;
  readonly discountMinor: number;
  readonly version?: number;
  /**
   * The server-supplied `actions[]` array (orders.md §4.2): exactly what
   * `OrderActionsPolicy` permits for this order's status and fulfilment mode
   * right now. Absent on a response from before this field existed — treat
   * that the same as empty, never as "unknown, so show everything".
   */
  readonly actions?: readonly OrderActionResponse[];
}
