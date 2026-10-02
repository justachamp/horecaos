import type { PhysicalFacts } from '../utils/physical';

/** Menu item variant (size/option) */
export interface MenuItemVariant {
  id: string;
  name: string;
  /** False means sold out (86'd): shown, not hidden. */
  active: boolean;
  /**
   * Row 4.2g: false means this variant has its own sale schedule and the
   * current moment falls outside every window on it -- shown, distinct from
   * `active` (sold out), so a customer can tell "gone for today" from "not on
   * the menu at this hour". Always true for a variant with no schedule.
   */
  onSaleNow: boolean;
  preparation_time: number;
  price: number;
  price_without_discount: number;
  /**
   * The portion the menu's author marked as the default, when they marked one.
   * Optional: a variant built without it is simply not the default, and
   * `preferredSellableVariant` (utils/item-availability) falls back to list order.
   */
  isDefault?: boolean;
  /**
   * Rows 4.4c/4.4d: a low remaining count for a QUANTITY-tracked item,
   * shown only once stock has dropped to a small threshold -- never above
   * it. `null` is the ordinary case (plenty of stock, or not
   * QUANTITY-tracked at all), never "unlimited".
   */
  remainingQuantity: number | null;
  /**
   * ADR 0137: what the variant physically is -- its weight or volume, whether it is sold by a
   * weight only known at handover (`catchweight`: then `price` is per `catchweightQuantumGrams`,
   * not per unit), whether it may be ordered by the portion, and its КБЖУ. Absent for a fixed
   * unit sold whole, which is most of the menu.
   */
  physical?: PhysicalFacts | null;
}

/**
 * One selectable option inside a modifier group (e.g. "extra cheese").
 *
 * `label` is the option's name in the customer's language when the menu carries
 * one (`MenuModifierOption.name`, ADR 0136), and otherwise its `code` -- an
 * authoring identifier -- which is what a menu published before options carried
 * a name still sends. This mirrors `MenuService`'s own fallback for a variant
 * with no translated label: shown as what the platform sent rather than invented.
 */
export interface MenuItemModifierOption {
  id: string;
  label: string;
  /** Extra charge for choosing this option. Null means no surcharge. */
  amountMinor: number | null;
  /** How many times this one option may be picked within its group. */
  maximumQuantity: number;
}

/** One group of modifier options a product offers (e.g. "Toppings"). */
export interface MenuItemModifierGroup {
  id: string;
  name: string;
  /** Least one selection must satisfy add-to-cart when this is true. */
  required: boolean;
  minimumSelections: number;
  maximumSelections: number;
  allowSameOptionMultipleTimes: boolean;
  options: MenuItemModifierOption[];
}

/**
 * ADR 0136: one dish or drink offered inside a combo's choice, with what it costs in this combo.
 *
 * `amountMinor` is per unit and null when no price applies -- never zero, never free. `id` is the
 * component (the pairing of the choice with the variant): what a pick names and what the price is
 * keyed to.
 */
export interface MenuItemComboComponent {
  id: string;
  name: string;
  /** The size or form, when the variant carries wording of its own. */
  variantName: string | null;
  /** Units one pick puts on the order. */
  defaultQuantity: number;
  /** False means sold out: shown, not pickable. */
  active: boolean;
  amountMinor: number | null;
}

/** ADR 0136: one choice a combo asks the customer to make ("choose a main"). */
export interface MenuItemComboGroup {
  id: string;
  name: string;
  minimumSelections: number;
  maximumSelections: number;
  /** Whether one component may be picked more than once. */
  allowSameComponentMultipleTimes: boolean;
  components: MenuItemComboComponent[];
}

/** Menu item (product) */
export interface MenuItem {
  id: string;
  name: string;
  description: string;
  active: boolean;
  has_discount: boolean;
  preparation_time: number;
  price: number;
  price_without_discount: number;
  image: string | null;
  start: string | null;
  finish: string | null;
  discount: unknown;
  is_favourite: boolean;
  delivery_duration: number;
  variants: MenuItemVariant[];
  /** The modifier groups this product offers, resolved from the publication. */
  modifierGroups: MenuItemModifierGroup[];
  /**
   * ADR 0136: the choices this product's combo asks for, empty on every product that is no combo.
   * A combo's own variant has no price: `price` is then the least a customer can pay, and the
   * choices are what the product page asks about.
   */
  comboGroups?: MenuItemComboGroup[];
}

/** Menu category (id + name only) */
export interface MenuCategory {
  id: string;
  name: string;
}

/** Category with its items */
export interface CategoryItem {
  id: string;
  name: string;
  items: MenuItem[];
  items_count: number;
}

/** Response from GET /customers/ui/categories/:categoryId/items */
export interface CategoryItemsResponse {
  name: string;
  items: MenuItem[];
}

/** Menu section of the response */
export interface CustomerUiMenu {
  categories: MenuCategory[];
  category_items: CategoryItem[];
  category_items_count: number;
}

/** Offer item (banner/promo) */
export interface OfferItem {
  id: string;
  name: string;
  image: string | null;
  priority: number;
}

/** Offer section */
export interface CustomerUiOffer {
  id: number;
  name: string;
  items: OfferItem[];
  items_count: number;
}

/** Popular category (e.g. "Tayyor taomlar to'plami") */
export interface PopularCategory {
  id: number;
  name: string;
  items: MenuItem[];
  items_count: number;
}

/** Address from UI response (optional) */
export interface CustomerUiAddress {
  label?: string;
  value?: string;
}

/** Response from GET /customers/ui/ */
export interface CustomerUiResponse {
  category: unknown;
  offer: CustomerUiOffer | null;
  populars: PopularCategory[];
  populars_count: number;
  menu: CustomerUiMenu;
  address?: CustomerUiAddress;
}
