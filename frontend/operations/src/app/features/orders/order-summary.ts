/**
 * Mirrors `OrderSummaryResponse` in
 * `platform/api/openapi/v1/horecaos-api.json` — the entire wire shape
 * `GET /api/v1/tenants/{t}/brands/{b}/locations/{l}/orders`
 * (`OperationsOrderController`) returns today, newest first.
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
  readonly totalMinor: number;
  readonly currency: string;
  readonly version?: number;
}
