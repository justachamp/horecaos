/** Menu item variant (size/option) */
export interface MenuItemVariant {
  id: string;
  name: string;
  active: boolean;
  preparation_time: number;
  price: number;
  price_without_discount: number;
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
