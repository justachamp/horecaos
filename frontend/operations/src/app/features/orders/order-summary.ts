import { OrderActionResponse } from './order-actions';

/**
 * Mirrors `OrderSummaryResponse` in `OperationsOrderController.java` — the
 * entire wire shape `GET .../orders/board` (wave P04, ADR 0102) returns per
 * row, cursor-paged, and the summary embedded in `OrderDetailResponse.summary`.
 * The legacy `GET .../orders` this superseded returned a strict subset of the
 * same fields; every field below is present on both, so this interface still
 * mirrors either response, not just the board's.
 *
 * Still short of `docs/operations-spec/orders.md` §2.5's default column set —
 * **no branch name** (`/board` is already scoped to one location; §2.5's own
 * rule auto-hides that column for a single-location tenant), **no decrypted
 * customer name or phone** (§2.5 column 7 reads `order_customer_snapshots`,
 * a table this response deliberately does not join — ADR 0029 PERSONAL data
 * belongs behind an audited reveal, not a list row every `ORDER_READ` holder
 * can page through; `customerAccountId`/`guestReferenceHash` below are the
 * opaque identifiers this response carries instead), and **no line summary**
 * (`order_lines`, a per-order query this list read does not make).
 * `order-queue.ts` documents exactly which columns render because the data
 * exists and which are withheld because it does not — never fabricated
 * client-side.
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
   * they render. Also §2.5's "behind the picker" Доставка/Скидка columns on
   * the board (`order-queue.ts`'s `formatFee`) — `0` for a pickup/dine-in
   * order or nothing discounted, never negative, never omitted.
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

  /**
   * `ordering.orders.promise_basis` (ADR 0036) — how `promisedAt` was
   * decided. Not yet read by any column here; carried for a future reader
   * rather than dropped on the floor.
   */
  readonly promiseBasis?: string | null;
  /**
   * §2.5 column 10 (Оплата) — `ordering.orders.payment_status_projection`.
   * See `order-payment-status.ts` for the seven values and the `NOT_REQUIRED`
   * dash rule.
   */
  readonly paymentStatusProjection?: string | null;
  /** Set for a signed-in customer's order; null for a guest — see {@link guestReferenceHash}. Never a name or phone (ADR 0029). */
  readonly customerAccountId?: string | null;
  /** Set for a guest order in place of {@link customerAccountId}. An opaque hash, never the raw phone (ADR 0029). */
  readonly guestReferenceHash?: string | null;
  /** §2.5's "behind the picker" Создал column — who took the order. */
  readonly createdByActorType?: string | null;
  readonly createdByActorId?: string | null;
  /** §2.5's "behind the picker" Принял column — who accepted it, once decided. */
  readonly acceptedByActorType?: string | null;
  readonly acceptedByActorId?: string | null;
  /**
   * §2.5 column 12 (Курьер), gap map row 1.1: the in-house courier carrying
   * this order's active shipment, read server-side through {@code
   * ActiveCourierAssignmentsPort} rather than a raw join — null when none is
   * assigned, the order is not a delivery, or it is carried by an external
   * partner rather than the tenant's own fleet. An opaque id, never a name
   * or a display reference (ADR 0029 keeps this response free of personal
   * data even though a courier id is not personal data itself) — resolve it
   * against the roster `order-queue.ts` already fetches for the toolbar's
   * own Курьер filter, the same way `order-detail-pane.ts`'s
   * `courierDisplayReference` resolves its own.
   */
  readonly courierId?: string | null;
}
