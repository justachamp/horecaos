/** Menu item variant (size/option) */
export interface MenuItemVariant {
  id: string;
  name: string;
  active: boolean;
  preparation_time: number;
  price: number;
  price_without_discount: number;
}

/**
 * One selectable option inside a modifier group (e.g. "extra cheese").
 *
 * `label` is what the publication actually carries for an option: a `code`,
 * which is an authoring identifier, and never a customer-facing name -- the
 * wire's `MenuModifierOption` has no name field at all. This mirrors
 * `MenuService`'s own fallback for a variant with no translated label: shown
 * as what the platform sent rather than invented.
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
