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
 * `LangService.langId` (`'uz' | 'ru' | 'en'`) to the BCP 47 tag
 * `toLocaleString` actually needs. `'uz'` is the fallback for the same
 * reason it is `LangService.langId`'s own default: this storefront opens in
 * Uzbek until a customer changes it.
 */
export function localeTag(langId: string | undefined | null): string {
  switch (langId) {
    case 'ru':
      return 'ru-RU';
    case 'en':
      return 'en-US';
    default:
      return 'uz-UZ';
  }
}

/**
 * The order's placed-at timestamp as the platform sent it, formatted for
 * display -- never guessed. Used as the order card's subtitle line: the list
 * response (`OrderSummaryResponse`) carries no item count or distance (see
 * `OrderItem.itemCount`/`distanceKm` above), so this is the one genuinely
 * available fact about an order a list card can show besides its number,
 * status and price.
 *
 * @param locale a BCP 47 tag (see {@link localeTag}) -- defaults to Uzbek
 *        rather than to `toLocaleString`'s own runtime-default locale, which
 *        is whatever the browser or CI environment happens to be set to
 *        (typically `en-US`) and not the language the rest of the screen is
 *        actually rendered in.
 */
export function formatPlacedAt(value: string | undefined | null, locale = 'uz-UZ'): string {
  if (!value) return '';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toLocaleString(locale);
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
  /**
   * The platform's own public order number (`OrderResponse.publicOrderNumber`,
   * e.g. "0922-001") -- a string, never coerced to `number`. `orders.data.ts`'s
   * `OrderItem.orderNumber` carries the same wire value the same way; see its
   * own note. `Number("0922-001")` is `NaN`, which is what the detail header
   * used to show before this was fixed to match the list.
   */
  orderNumber: number | string;
  /** 2026-09-21 audit follow-up (d): the branch this order was placed at, when the API sent one. */
  locationId?: string;
  /** `DELIVERY`, `PICKUP` or `DINE_IN`, when the API sent one. */
  fulfillmentMode?: string;
  lineItems: OrderLineItem[];
  subtotal: string;
  /**
   * `OrderResponse.taxMinor` (StorefrontOrderingController), when it is
   * actually non-zero. Shown as its own row rather than folded into
   * `subtotal` -- under an INCLUSIVE tax profile `subtotalMinor` is net of
   * tax, and subtotal + delivery alone never summed to `total`.
   */
  tax?: string;
  /**
   * `OrderResponse.feeMinor` (StorefrontOrderingController), when it is
   * actually non-zero. Absent for a PICKUP/DINE_IN order or a waived
   * delivery fee -- showing "0 so'm" there would claim delivery was priced
   * at zero rather than not charged at all, matching how `packaging` below
   * is already handled.
   */
  deliveryFee?: string;
  total: string;
  /** Packaging fee when > 0 */
  packaging?: string;
  /** Available actions from API, e.g. ['cancel'] */
  actions?: string[];
}
