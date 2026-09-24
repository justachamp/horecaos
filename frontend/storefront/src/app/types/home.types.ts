/** Menu item variant (size/option) */
export interface MenuItemVariant {
  id: string;
  name: string;
  active: boolean;
  preparation_time: number;
  price: number;
  price_without_discount: number;
  /**
   * Row 4.2g: false means this variant's own sale schedule excludes the
   * current moment -- shown (unlike `active`, which the platform already
   * drops an unorderable variant off of before this shape is even built),
   * but the product page must refuse adding it. Distinct from `active`
   * (86'd): `MenuService.toMenuItem`'s own doc explains the difference.
   */
  onSaleNow: boolean;
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

/**
 * Row 2.1b: one coded kitchen-instruction preset a product offers on its
 * line -- "without onions", "extra spicy". Every locale, so the storefront
 * renders whichever the customer's own `langId` picked; matches
 * `StorefrontCatalogQuery.CommentPresetOption`.
 */
export interface MenuItemCommentPreset {
  code: string;
  labelRu: string;
  labelUz: string;
  labelEn: string;
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
  /** Row 2.1b: the coded comment presets this product offers, in the catalogue's own order. */
  commentPresets: MenuItemCommentPreset[];
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
