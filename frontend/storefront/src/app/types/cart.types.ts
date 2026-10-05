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
 * `delivery` is priced by its own endpoint against a destination (ADR 0037), and
 * `vendor` is a legacy block with nothing behind it. They are kept so the
 * templates compile and are documented here so nobody reads a zero as a fact.
 * `promo_code` is the exception: ADR 0072 gave it a real platform source
 * (`PlatformCart.appliedPromoCode`), and `UiCartService.project` fills it.
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
  /** ADR 0136: set on a second-level answer, naming the first-level option that opened it. */
  readonly parentOptionId?: string;
}

/**
 * Row 2.1b: one preset the line currently carries, resolved against the menu
 * for display -- the wire's own `CartLineResponse.commentPresetCodes` is
 * codes only, no label.
 */
export interface CartResponseCommentPresetSelection {
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly labels?: Readonly<Record<string, string>>;
  readonly label?: string;
}

/**
 * ADR 0136: one component of a combo line, resolved against the menu for display. `quantity` is the
 * units this line puts on the order for the component (picks times units per pick), per combo.
 */
export interface CartResponseComboComponent {
  readonly componentId: string;
  readonly name: string;
  readonly variantName: string | null;
  readonly quantity: number;
  /** Per unit; null when the component is no longer priced, which the screen shows as unpriced. */
  readonly amountMinor: number | null;
}

/** Cart item from GET /customers/carts/ response */
export interface CartResponseItem {
  variant_id: string;
  price: number;
  item_id: string;
  name: string;
  active: boolean;
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
   * The first-level options the line holds: the platform's own echo of them, else decoded from the
   * line's own key. Must be resent on every write to this
   * line -- see `CartService.putLine` -- or a quantity change silently strips
   * whatever the customer chose.
   */
  modifierOptionIds: readonly string[];
  /** The same selections, resolved against the menu for display. */
  modifiers: readonly CartResponseModifierSelection[];
  /**
   * Row 2.1b: the line's own coded presets, straight off the platform's
   * `CartLineResponse`. Resent on every write to this line -- see
   * `UiCartService.setQuantity`'s own doc -- the same rule `modifierOptionIds`
   * above already follows.
   */
  commentPresetCodes: readonly string[];
  /** The same codes, resolved against the menu for display. */
  commentPresets: readonly CartResponseCommentPresetSelection[];
  /**
   * ADR 0136: what was picked inside a combo, straight off the platform's `CartLineResponse`.
   * Resent whole on every write to this line, like `modifierOptionIds`. Empty on every line that is
   * no combo, and `variant_id` is then the combo's container: never sold on its own.
   */
  comboPicks?: readonly { readonly componentId: string; readonly quantity: number }[];
  /** The same picks, resolved against the menu for display; `price` is then what one combo costs. */
  comboComponents?: readonly CartResponseComboComponent[];
  /**
   * ADR 0136: the second-level answers, straight off the platform's `CartLineResponse`, each under
   * the first-level option that opened it. Resent whole on every write to this line, like
   * `modifierOptionIds`. Empty on most lines.
   */
  nestedModifiers?: readonly { readonly parentOptionId: string; readonly optionId: string }[];
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
