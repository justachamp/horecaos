/**
 * The basket as the *screens* read it, not as the platform sends it.
 *
 * These shapes outlived the legacy API on purpose. A dozen templates bind to
 * `items[].name`, `items[].price` and `total.price`, and the platform's cart
 * carries none of them — it is lines, quantities and a version, with no money
 * at all. `UiCartService` assembles this projection from the cart, the menu and
 * the pricing call, so the templates did not have to be rewritten alongside
 * every service.
 *
 * Several members below are therefore filled with zero or empty rather than a
 * number the platform computed: `packaging` has no platform equivalent at all,
 * `delivery` is priced by its own endpoint against a destination (ADR 0037),
 * and `vendor` is a legacy block with nothing behind it. They are kept so the
 * templates compile and are documented here so nobody reads a zero as a fact.
 *
 * `promo_code` is the exception: ADR 0072 gave it a real platform source
 * (`PlatformCart.appliedPromoCode`), and `UiCartService.project` now fills it
 * from there instead of leaving it null.
 */

import type { PhysicalFacts } from '../utils/physical';

/** Price + discount pair from cart response */
export interface CartPriceDiscount {
  price: number;
  discount: number;
}

/**
 * One chosen modifier option, resolved against the menu for display.
 *
 * The cart itself never carries this -- `CartLineResponse` has no field for
 * it, only the `lineKey` that encodes it (see
 * `modifierOptionIdsFromLineKey`). `label` follows the same rule as
 * `MenuItemModifierOption`: the wire has no customer-facing name for an
 * option, only a `code`.
 */
export interface CartResponseModifierSelection {
  readonly optionId: string;
  readonly groupName: string;
  readonly label: string;
  readonly amountMinor: number | null;
}

/** Cart item from GET /customers/carts/ response */
export interface CartResponseItem {
  variant_id: string;
  price: number;
  item_id: string;
  name: string;
  /** False when the menu no longer lets the customer buy this line right now. */
  active: boolean;
  /**
   * Why {@link active} is false: 'SOLD_OUT' (86'd) or 'OUT_OF_SALE_WINDOW'
   * (row 4.2g: its own schedule excludes this moment). Absent for an ordinary line.
   */
  unavailableReason?: 'SOLD_OUT' | 'OUT_OF_SALE_WINDOW';
  image: string | null;
  /** A decimal for a variant ordered by the portion (ADR 0137): `0.5` is half a portion. */
  quantity: number;
  /**
   * ADR 0137: the variant's physical facts, joined from the menu. `price` is the price row --
   * per unit, or per `catchweightQuantumGrams` for a weighed variant -- so a line's amount is
   * `UiCartService.lineAmount`, never `price * quantity`.
   */
  physical?: PhysicalFacts | null;
  note: string | null;
  /**
   * Decoded from the line's own key. Must be resent on every write to this
   * line -- see `CartService.putLine` -- or a quantity change silently strips
   * whatever the customer chose.
   */
  modifierOptionIds: readonly string[];
  /** The same selections, resolved against the menu for display. */
  modifiers: readonly CartResponseModifierSelection[];
}

/** Vendor from cart response */
export interface CartVendor {
  id: string;
  name: string;
  phone: string;
  active: boolean;
  pre_order: boolean;
  start: string;
  finish: string;
}

/** Address from cart response */
export interface CartAddress {
  id: string;
  name: string;
  address: string;
  latitude: number;
  longitude: number;
}

/** Response from GET /customers/carts/ */
export interface CartResponse {
  subtotal: CartPriceDiscount;
  delivery: CartPriceDiscount;
  packaging: CartPriceDiscount;
  total: CartPriceDiscount;
  items: CartResponseItem[];
  vendor: CartVendor;
  address: CartAddress | null;
  items_count: number;
  delivery_time: number | null;
  delivery_distance: number;
  delivery_date_display: string | null;
  delivery_time_display: string | null;
  promo_code: string | null;
  delivery_duration: number;
}
