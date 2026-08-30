import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type {
  CategoryItem,
  CategoryItemsResponse,
  CustomerUiResponse,
  MenuItem,
  MenuItemVariant,
} from '../types/home.types';

/**
 * The published menu, and every browse screen built on it.
 *
 * Replaces `CustomerUiService`, which called five legacy endpoints —
 * `/customers/ui/`, `/ui/categories/{id}/items`, `/ui/items/{id}`, a search and a
 * recently-searched list. The platform serves **one**: the whole published menu
 * for a location, unauthenticated, cached for thirty seconds with the
 * publication id as its ETag. Category browse, the product page and search are
 * all reads of that one document, so they are done here rather than over the
 * wire.
 *
 * <h2>What the platform does not send, and what this refuses to invent</h2>
 *
 * **No offers or popular sections.** The legacy home screen had a promo banner
 * carousel and a "populars" rail, both assembled by the old backend. Nothing on
 * the platform produces either; `marketing` is an operations surface, not a
 * storefront one. They resolve to empty rather than to a fabricated selection —
 * a "popular" list that is really just the first five products is a lie the
 * screen tells confidently.
 *
 * **No favourite flag.** There is no favourites backend at all, so every item
 * reports `is_favourite: false`.
 *
 * **No variant name.** A variant carries a `sku` and a `unitCode`, which are
 * authoring identifiers; translations are published for categories, products and
 * modifier groups only. Printing a SKU as a size label would show the customer a
 * database value, so a single-variant product exposes no picker and a
 * multi-variant one falls back to the unit code, which at least means something.
 *
 * **No preparation time or delivery duration.** Both were on the legacy item and
 * neither is on the publication. Serviceability answers preparation time per
 * branch, not per dish.
 */
@Injectable({ providedIn: 'root' })
export class MenuService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /**
   * The last menu read, kept so category browse, the product page and search do
   * not each re-fetch it. The publication id is the server's own ETag, so a
   * changed menu is a changed document rather than a stale cache to invalidate.
   */
  private cached: { key: string; menu: PublishedMenu } | null = null;

  readonly currency = signal<string | null>(null);

  /** The whole menu for a location, from cache when the key has not moved. */
  async menu(locale: string, locationId?: string): Promise<PublishedMenu> {
    const location = locationId ?? this.config.defaultLocationId;
    if (!location) {
      throw new Error('No location is configured for this storefront.');
    }
    const key = `${location}|${locale}|${this.config.channel}`;
    if (this.cached?.key === key) {
      return this.cached.menu;
    }
    const menu = await this.api.get<PublishedMenu>(
      `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
        `/locations/${location}/menu`,
      {
        // The channel is required and is this deployment's own: ADR 0036 makes it
        // supply both the publication and the price plane, so a menu fetched on
        // another channel is a menu whose prices change at checkout.
        query: { locale, channel: this.config.channel },
        anonymous: true,
      },
    );
    this.cached = { key, menu };
    this.currency.set(menu.currency);
    return menu;
  }

  /** Drops the cache, so the next read re-fetches. */
  forget(): void {
    this.cached = null;
  }

  /** The home screen's shape, from the one menu document. */
  async home(locale: string, locationId?: string): Promise<CustomerUiResponse> {
    const menu = await this.menu(locale, locationId);
    const byId = new Map(menu.products.map((product) => [product.productId, product]));

    const categoryItems: CategoryItem[] = menu.categories.map((category) => {
      const items = category.productIds
        .map((id) => byId.get(id))
        .filter((product): product is PublishedProduct => product !== undefined)
        .map((product) => this.toMenuItem(product, menu.currency));
      return {
        id: category.categoryId,
        name: category.name,
        items,
        items_count: items.length,
      };
    });

    return {
      category: null,
      // Neither has a platform source. Empty, not invented.
      offer: null,
      populars: [],
      populars_count: 0,
      menu: {
        categories: menu.categories.map((category) => ({
          id: category.categoryId,
          name: category.name,
        })),
        category_items: categoryItems,
        category_items_count: categoryItems.length,
      },
    };
  }

  async categoryItems(categoryId: string, locale: string): Promise<CategoryItemsResponse> {
    const menu = await this.menu(locale);
    const category = menu.categories.find((entry) => entry.categoryId === categoryId);
    if (!category) {
      return { name: '', items: [] };
    }
    const byId = new Map(menu.products.map((product) => [product.productId, product]));
    return {
      name: category.name,
      items: category.productIds
        .map((id) => byId.get(id))
        .filter((product): product is PublishedProduct => product !== undefined)
        .map((product) => this.toMenuItem(product, menu.currency)),
    };
  }

  async item(productId: string, locale: string): Promise<MenuItem | null> {
    const menu = await this.menu(locale);
    const product = menu.products.find((entry) => entry.productId === productId);
    return product ? this.toMenuItem(product, menu.currency) : null;
  }

  /**
   * Search, over the loaded menu rather than over the wire.
   *
   * The platform has no search endpoint, and for a single location's menu it
   * does not need one: the whole document is already here and is a few hundred
   * items at most. Matching is on the customer-facing name and description and
   * never on `code` or `sku`, which are authoring identifiers a customer has
   * never seen.
   */
  async search(text: string, locale: string): Promise<MenuItem[]> {
    const needle = text.trim().toLocaleLowerCase();
    if (!needle) {
      return [];
    }
    const menu = await this.menu(locale);
    return menu.products
      .filter(
        (product) =>
          product.name.toLocaleLowerCase().includes(needle) ||
          (product.description ?? '').toLocaleLowerCase().includes(needle),
      )
      .map((product) => this.toMenuItem(product, menu.currency));
  }

  /**
   * Projects a published product onto the shape the existing screens read.
   *
   * `price` is the preferred variant's amount in **minor units**, which for UZS
   * is whole som. The legacy field carried the same units, so nothing downstream
   * divides — and nothing must start.
   */
  private toMenuItem(product: PublishedProduct, currency: string | null): MenuItem {
    const preferred = preferredVariant(product);
    const variants: MenuItemVariant[] = product.variants.map((variant) => ({
      id: variant.variantId,
      // Not a name. See the class comment: the wire carries no customer-facing
      // text for a variant, and a SKU printed as a label is a database value.
      name: variant.unitCode ?? '',
      active: variant.orderable,
      preparation_time: 0,
      price: variant.amountMinor ?? 0,
      price_without_discount: variant.amountMinor ?? 0,
    }));

    return {
      id: product.productId,
      name: product.name,
      description: product.description ?? '',
      active: product.variants.some((variant) => variant.orderable),
      // Promotions are not surfaced on the menu, so nothing claims a discount.
      has_discount: false,
      preparation_time: 0,
      price: preferred?.amountMinor ?? 0,
      price_without_discount: preferred?.amountMinor ?? 0,
      image: product.imageUrls[0] ?? null,
      start: null,
      finish: null,
      discount: null,
      is_favourite: false,
      delivery_duration: 0,
      variants,
    };
  }
}

/**
 * The variant a screen should preselect: the authored default when orderable,
 * otherwise the first orderable one, otherwise the default, otherwise the first.
 */
function preferredVariant(product: PublishedProduct): PublishedVariant | null {
  const { variants } = product;
  return (
    variants.find((variant) => variant.isDefault && variant.orderable) ??
    variants.find((variant) => variant.orderable) ??
    variants.find((variant) => variant.isDefault) ??
    variants[0] ??
    null
  );
}

/** `StorefrontCatalogQuery.StorefrontMenu`, transcribed from the controller. */
export interface PublishedMenu {
  readonly publicationId: string;
  readonly locale: string;
  /** Null when this brand has no active price book here; then no amount is set. */
  readonly currency: string | null;
  readonly categories: readonly PublishedCategory[];
  readonly products: readonly PublishedProduct[];
  readonly modifierGroups: readonly PublishedModifierGroup[];
}

export interface PublishedCategory {
  readonly categoryId: string;
  readonly code: string | null;
  readonly name: string;
  readonly parentCategoryId: string | null;
  readonly sortOrder: number;
  readonly productIds: readonly string[];
}

export interface PublishedProduct {
  readonly productId: string;
  readonly code: string | null;
  readonly name: string;
  readonly description: string | null;
  readonly mediaAssetIds: readonly string[];
  /** Platform URLs, one per asset id, that redirect to a short-lived signed URL. */
  readonly imageUrls: readonly string[];
  readonly variants: readonly PublishedVariant[];
  readonly modifierGroupIds: readonly string[];
}

export interface PublishedVariant {
  readonly variantId: string;
  readonly sku: string | null;
  readonly unitCode: string | null;
  readonly isDefault: boolean;
  /** False means shown and sold out, not hidden. The server already dropped what
   * this location does not offer. */
  readonly orderable: boolean;
  /** Null when unpriced. Never zero for "no price". */
  readonly amountMinor: number | null;
}

export interface PublishedModifierGroup {
  readonly modifierGroupId: string;
  readonly code: string | null;
  readonly name: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameOptionMultipleTimes: boolean;
  readonly options: readonly PublishedModifierOption[];
}

export interface PublishedModifierOption {
  readonly optionId: string;
  readonly code: string | null;
  readonly maximumQuantity: number;
  readonly amountMinor: number | null;
}
