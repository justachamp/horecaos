export interface OrderItem {
  id: string;
  title: string;
  subtitle: string;
  /**
   * The platform's own {@code OrderStatus} name (e.g. `'CONFIRMED'`,
   * `'FULFILLING'`), exactly as `StorefrontOrderingController.OrderResponse`
   * and `OrderSummaryResponse` send it -- never a legacy lowercase token, and
   * never invented when the API sent nothing. Look it up in
   * {@link ORDER_STATUS_I18N_KEY} to render it; see that constant's doc
   * comment for why nothing here guesses.
   */
  status: string;
  /**
   * When the order was placed, formatted for display. The order list
   * response carries no item count or distance (see `itemCount` and
   * `distanceKm` below), so this is what actually fills the subtitle line in
   * practice.
   */
  date: string;
  price: string;
  image: string;
  /** Order number shown as "Order N: X" (active orders) */
  orderNumber?: number;
  /**
   * e.g. "4 ta" -- set only when the API actually reports a count.
   *
   * `StorefrontOrderingController.OrderSummaryResponse` (the row this app's
   * order list reads) deliberately carries no line items -- see its Javadoc:
   * "no lines... open one order to read those." A list built from that
   * response has no honest way to know how many items are on an order, so
   * this stays unset there rather than defaulting to `0`, which used to
   * render as the very wrong "0 ta" on every single order card.
   */
  itemCount?: string;
  /**
   * e.g. "8.8" for "8.8 km" -- set only when the API actually reports a
   * distance. Nothing in the storefront's order responses carries a delivery
   * distance today, so this is always unset; it is kept as a field, rather
   * than deleted, so a future response that does add one needs no template
   * change to show it.
   */
  distanceKm?: string;
  /** Available actions from API, e.g. ['cancel'] */
  actions?: string[];
}

/**
 * The platform's own order-status vocabulary (`ordering.domain.OrderStatus`),
 * translated. Nothing here is invented, and nothing here is the legacy
 * lowercase tab vocabulary (`new`/`accepted`/`cooking`/...) that
 * `OrdersService`'s tab filters still use as tab *identity* only -- a real
 * order's `status` field is never one of those tokens.
 *
 * Every key names a real `en.json`/`ru.json`/`uz.json` entry under
 * `orders.platformStatus`; `orders.service.spec.ts` and this page's own specs
 * assert that every status the API can send resolves through here to an
 * actual translated string, not a raw key left on screen.
 */
export const ORDER_STATUS_I18N_KEY: Readonly<Record<string, string>> = {
  RECEIVED: 'orders.platformStatus.RECEIVED',
  PAYMENT_AUTHORIZING: 'orders.platformStatus.PAYMENT_AUTHORIZING',
  AWAITING_APPROVAL: 'orders.platformStatus.AWAITING_APPROVAL',
  CONFIRMED: 'orders.platformStatus.CONFIRMED',
  PREPARING: 'orders.platformStatus.PREPARING',
  READY: 'orders.platformStatus.READY',
  FULFILLING: 'orders.platformStatus.FULFILLING',
  COMPLETED: 'orders.platformStatus.COMPLETED',
  CANCELLED: 'orders.platformStatus.CANCELLED',
  REJECTED: 'orders.platformStatus.REJECTED',
  EXPIRED: 'orders.platformStatus.EXPIRED',
  PAYMENT_FAILED: 'orders.platformStatus.PAYMENT_FAILED',
};

/**
 * The order's placed-at timestamp as the platform sent it, formatted for
 * display -- never guessed. Used as the order card's subtitle line: the list
 * response (`OrderSummaryResponse`) carries no item count or distance (see
 * `OrderItem.itemCount`/`distanceKm` above), so this is the one genuinely
 * available fact about an order a list card can show besides its number,
 * status and price.
 */
export function formatPlacedAt(value: string | undefined | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toLocaleString();
}

/** Line item for order detail view */
export interface OrderLineItem {
  name: string;
  image: string;
  quantity: number;
  unitPrice: string;
  /** For @for track when items can share the same name */
  variantId?: string;
}

/** Full order detail for /orders/detail/:id */
export interface OrderDetail {
  id: string;
  orderNumber: number;
  lineItems: OrderLineItem[];
  subtotal: string;
  /**
   * Absent when the platform's order response does not break out a delivery
   * fee (it never does today -- `OrderResponse` has no such field, and the
   * amount is folded into `total` with no breakdown). Showing "0 so'm" here
   * used to claim delivery was free when it may not have been; omitting the
   * row is the honest answer, matching how `packaging` below is already
   * handled and how `cart-order-status.component` shows no delivery line at
   * all.
   */
  deliveryFee?: string;
  total: string;
  /** Packaging fee when > 0 */
  packaging?: string;
  /** Available actions from API, e.g. ['cancel'] */
  actions?: string[];
}
