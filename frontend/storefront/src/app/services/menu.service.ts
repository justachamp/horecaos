import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type { PhysicalFacts } from '../utils/physical';
import type {
  CategoryItem,
  CategoryItemsResponse,
  CustomerUiResponse,
  MenuItem,
  MenuItemComboGroup,
  MenuItemCommentPreset,
  MenuItemModifierGroup,
  MenuItemVariant,
} from '../types/home.types';
import { comboFromAmountMinor } from '../utils/combo-selection';

/**
 * The published menu, and every browse screen built on it.
 *
 * Replaces `CustomerUiService`, which called five legacy endpoints —
 * `/customers/ui/`, `/ui/categories/{id}/items`, `/ui/items/{id}`, a search and a
 * recently-searched list. The platform serves **one**: the whole published menu
 * for a location, unauthenticated. Rows 4.4c/4.4d folded live inventory
 * availability into that same response, so the server no longer hands out a
 * flat thirty-second cache window for it (ADR 0033) — it is revalidated
 * against the origin on every read (a fresh `ETag` combining the publication
 * id with an availability fingerprint) rather than replayed blind, so a stop
 * taken between two reads is never served stale. Category browse, the product
 * page and search are all reads of that one document, so they are done here
 * rather than over the
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
   * The in-flight read for the current key, if one is already on the wire.
   *
   * Without this, the home screen's own `menuService.home()` and a
   * signed-in customer's cart re-projecting itself (`UiCartService.project`)
   * both ask for the same menu on the same page load, and two identical
   * `GET .../menu` requests would land instead of one. Sharing the pending
   * promise, the same way `CartService.price` already shares its own, is
   * what collapses them back to one -- but only for callers racing the same
   * read; it is dropped the moment that read settles, so the *next* call
   * always goes back to the origin rather than replaying what it saw.
   *
   * There is deliberately no cache kept past that: the server stopped
   * handing out a flat cache window for exactly this response (ADR 0033,
   * rows 4.4c/4.4d) so a stop taken between two reads is never served
   * stale, and an application-level cache that outlives one request would
   * quietly undo that on the one layer that decides what a customer sees.
   */
  private pending: { key: string; promise: Promise<PublishedMenu> } | null = null;

  readonly currency = signal<string | null>(null);

  /**
   * The whole menu for a location: the already-in-flight read for that key
   * if one is on the wire, otherwise a fresh read from the origin.
   *
   * @param channel overrides this deployment's own configured channel. The
   *        one caller today is the dine-in QR flow (`DineInService`): a
   *        table's `QR_TABLE` channel is resolved per scan, from the guest
   *        admission, and is never this build's `config.channel` -- see
   *        `AppConfig.channel`'s own doc on why a static build config cannot
   *        answer that.
   */
  async menu(locale: string, locationId?: string, channel?: string): Promise<PublishedMenu> {
    const location = locationId ?? this.config.defaultLocationId;
    if (!location) {
      throw new Error('No location is configured for this storefront.');
    }
    const effectiveChannel = channel ?? this.config.channel;
    const key = `${location}|${locale}|${effectiveChannel}`;
    if (this.pending?.key === key) {
      return this.pending.promise;
    }
    const promise = this.api
      .get<PublishedMenu>(
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
          `/locations/${location}/menu`,
        {
          // The channel is required: ADR 0036 makes it supply both the
          // publication and the price plane, so a menu fetched on another
          // channel is a menu whose prices change at checkout.
          query: { locale, channel: effectiveChannel },
          anonymous: true,
        },
      )
      .then((menu) => {
        this.currency.set(menu.currency);
        return menu;
      })
      .finally(() => {
        if (this.pending?.key === key) {
          this.pending = null;
        }
      });
    this.pending = { key, promise };
    return promise;
  }

  /** The home screen's shape, from the one menu document. */
  async home(locale: string, locationId?: string): Promise<CustomerUiResponse> {
    const menu = await this.menu(locale, locationId);
    const byId = new Map(menu.products.map((product) => [product.productId, product]));

    const groupsById = modifierGroupsById(menu);
    const categoryItems: CategoryItem[] = menu.categories.map((category) => {
      const items = category.productIds
        .map((id) => byId.get(id))
        .filter((product): product is PublishedProduct => product !== undefined)
        .map((product) =>
          this.toMenuItem(product, menu.currency, groupsById, comboGroupsById(menu)),
        );
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
    const groupsById = modifierGroupsById(menu);
    return {
      name: category.name,
      items: category.productIds
        .map((id) => byId.get(id))
        .filter((product): product is PublishedProduct => product !== undefined)
        .map((product) =>
          this.toMenuItem(product, menu.currency, groupsById, comboGroupsById(menu)),
        ),
    };
  }

  async item(productId: string, locale: string): Promise<MenuItem | null> {
    const menu = await this.menu(locale);
    const product = menu.products.find((entry) => entry.productId === productId);
    return product
      ? this.toMenuItem(product, menu.currency, modifierGroupsById(menu), comboGroupsById(menu))
      : null;
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
    const groupsById = modifierGroupsById(menu);
    return menu.products
      .filter(
        (product) =>
          product.name.toLocaleLowerCase().includes(needle) ||
          (product.description ?? '').toLocaleLowerCase().includes(needle),
      )
      .map((product) => this.toMenuItem(product, menu.currency, groupsById, comboGroupsById(menu)));
  }

  /**
   * Projects a published product onto the shape the existing screens read.
   *
   * `price` is the preferred variant's amount in **minor units**, which for UZS
   * is whole som. The legacy field carried the same units, so nothing downstream
   * divides — and nothing must start.
   */
  private toMenuItem(
    product: PublishedProduct,
    currency: string | null,
    modifierGroups: ReadonlyMap<string, PublishedModifierGroup>,
    comboGroups: ReadonlyMap<string, PublishedComboGroup> = new Map(),
  ): MenuItem {
    const preferred = preferredVariant(product);
    const combos = (product.comboGroupIds ?? [])
      .map((id) => comboGroups.get(id))
      .filter((group): group is PublishedComboGroup => group !== undefined)
      .map(toMenuItemComboGroup);
    // A combo's own variant has no price. What the menu shows is the least a customer can pay:
    // each choice's cheapest way of reaching its minimum. Null when a needed component is
    // unpriced, which shows as no price rather than as the sum of the priced ones.
    const comboFrom = combos.length > 0 ? comboFromAmountMinor(combos) : null;
    const variants: MenuItemVariant[] = product.variants.map((variant) => ({
      id: variant.variantId,
      // Not a name. See the class comment: the wire carries no customer-facing
      // text for a variant, and a SKU printed as a label is a database value.
      name: variant.unitCode ?? '',
      active: variant.orderable,
      preparation_time: 0,
      price: (combos.length > 0 ? comboFrom : variant.amountMinor) ?? 0,
      price_without_discount: (combos.length > 0 ? comboFrom : variant.amountMinor) ?? 0,
      onSaleNow: variant.onSaleNow,
      remainingQuantity: variant.remainingQuantity,
      physical: variant.physical ?? null,
      ...variantGroups(product, variant, modifierGroups),
    }));

    return {
      id: product.productId,
      name: product.name,
      description: product.description ?? '',
      active: product.variants.some((variant) => variant.orderable),
      // Promotions are not surfaced on the menu, so nothing claims a discount.
      has_discount: false,
      preparation_time: 0,
      price: (combos.length > 0 ? comboFrom : preferred?.amountMinor) ?? 0,
      price_without_discount: (combos.length > 0 ? comboFrom : preferred?.amountMinor) ?? 0,
      image: product.imageUrls[0] ?? null,
      start: null,
      finish: null,
      discount: null,
      is_favourite: false,
      delivery_duration: 0,
      variants,
      modifierGroups: product.modifierGroupIds
        .map((id) => modifierGroups.get(id))
        .filter((group): group is PublishedModifierGroup => group !== undefined)
        .map((group) =>
          withPolicies(toMenuItemModifierGroup(group, modifierGroups), [
            product.modifierGroupPolicies,
          ]),
        ),
      commentPresets: product.commentPresets.map(toMenuItemCommentPreset),
      comboGroups: combos,
    };
  }
}

/**
 * The rules published for a group where a product, and a portion of it, state their own (ADR 0136).
 * The published values are already the effective ones, so they replace the group's outright --
 * and a later list replaces an earlier one, the portion's row over the product's, as the cart
 * enforces it -- and the add-to-cart guard asks for exactly what the cart will enforce.
 */
function withPolicies(
  group: MenuItemModifierGroup,
  policyLists: readonly (readonly PublishedModifierGroupPolicy[] | undefined)[],
): MenuItemModifierGroup {
  let result = group;
  for (const list of policyLists) {
    const policy = (list ?? []).find((p) => p.modifierGroupId === group.id);
    if (policy) {
      result = {
        ...result,
        required: policy.required,
        minimumSelections: policy.minimumSelections,
        maximumSelections: policy.maximumSelections,
      };
    }
  }
  return result;
}

/**
 * The whole list of groups one portion is offered with, when it differs from its product's: the
 * product's, then the ones the portion carries of its own, each under the product's rule and then
 * the portion's. Nothing for a portion that adds and overrides nothing, which uses the product's.
 */
function variantGroups(
  product: PublishedProduct,
  variant: PublishedVariant,
  modifierGroups: ReadonlyMap<string, PublishedModifierGroup>,
): { modifierGroups?: MenuItemModifierGroup[] } {
  const own = variant.modifierGroupIds ?? [];
  const overrides = variant.modifierGroupPolicies ?? [];
  if (own.length === 0 && overrides.length === 0) {
    return {};
  }
  const ids = [
    ...product.modifierGroupIds,
    ...own.filter((id) => !product.modifierGroupIds.includes(id)),
  ];
  return {
    modifierGroups: ids
      .map((id) => modifierGroups.get(id))
      .filter((group): group is PublishedModifierGroup => group !== undefined)
      .map((group) =>
        withPolicies(toMenuItemModifierGroup(group, modifierGroups), [
          product.modifierGroupPolicies,
          overrides,
        ]),
      ),
  };
}

function toMenuItemComboGroup(group: PublishedComboGroup): MenuItemComboGroup {
  return {
    id: group.comboGroupId,
    name: group.name,
    minimumSelections: group.minimumSelections,
    maximumSelections: group.maximumSelections,
    allowSameComponentMultipleTimes: group.allowSameComponentMultipleTimes,
    components: group.components.map((component) => ({
      id: component.componentId,
      name: component.name,
      variantName: component.variantName ?? null,
      defaultQuantity: component.defaultQuantity,
      active: component.orderable,
      amountMinor: component.amountMinor,
    })),
  };
}

/**
 * The choices a product's combo asks for, in the author's order, for a screen that reads the menu
 * document itself rather than a projected `MenuItem` (the dine-in table). Empty on a product that is
 * no combo.
 */
export function comboGroupsOfProduct(
  menu: PublishedMenu,
  product: PublishedProduct,
): MenuItemComboGroup[] {
  const byId = comboGroupsById(menu);
  return (product.comboGroupIds ?? [])
    .map((id) => byId.get(id))
    .filter((group): group is PublishedComboGroup => group !== undefined)
    .map(toMenuItemComboGroup);
}

/** Indexes a menu's combo groups by id, for resolving a product's ids against. */
function comboGroupsById(menu: PublishedMenu): ReadonlyMap<string, PublishedComboGroup> {
  return new Map((menu.comboGroups ?? []).map((group) => [group.comboGroupId, group]));
}

function toMenuItemCommentPreset(preset: PublishedCommentPreset): MenuItemCommentPreset {
  return {
    code: preset.code,
    labelRu: preset.labelRu,
    labelUz: preset.labelUz,
    labelEn: preset.labelEn,
    ...(preset.labels ? { labels: preset.labels } : {}),
    ...(preset.label ? { label: preset.label } : {}),
  };
}

/** Indexes a menu's modifier groups by id, for resolving a product's ids against. */
function modifierGroupsById(menu: PublishedMenu): ReadonlyMap<string, PublishedModifierGroup> {
  return new Map(menu.modifierGroups.map((group) => [group.modifierGroupId, group]));
}

/**
 * A published group as the screens read it. At the first level an option that opens choices carries
 * them as `nestedGroups`, each resolved from the menu's own groups under the rules published for it
 * under that option; one level and no more, so the options of a nested group open nothing further.
 */
function toMenuItemModifierGroup(
  group: PublishedModifierGroup,
  all?: ReadonlyMap<string, PublishedModifierGroup>,
): MenuItemModifierGroup {
  return {
    id: group.modifierGroupId,
    name: group.name,
    required: group.required,
    minimumSelections: group.minimumSelections,
    maximumSelections: group.maximumSelections,
    allowSameOptionMultipleTimes: group.allowSameOptionMultipleTimes,
    options: group.options.map((option) => {
      const nested = all
        ? (option.nestedGroups ?? [])
            .map((policy) => {
              const published = all.get(policy.modifierGroupId);
              return published
                ? withPolicies(toMenuItemModifierGroup(published), [[policy]])
                : null;
            })
            .filter((entry): entry is MenuItemModifierGroup => entry !== null)
        : [];
      return {
        id: option.optionId,
        // The option's name in the customer's language when the menu carries one, else the
        // authoring code a menu published before options were named still sends.
        label: option.name || option.code || '',
        amountMinor: option.amountMinor,
        maximumQuantity: option.maximumQuantity,
        ...(nested.length > 0 ? { nestedGroups: nested } : {}),
      };
    }),
  };
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
  /** ADR 0136: the choices every combo on this menu asks for; absent from a platform that sells none. */
  readonly comboGroups?: readonly PublishedComboGroup[];
}

/** `StorefrontCatalogQuery.MenuComboGroup`, transcribed. The container is the combo's own variant: never priced, never sold directly. */
export interface PublishedComboGroup {
  readonly comboGroupId: string;
  readonly containerVariantId: string;
  readonly code: string | null;
  readonly name: string;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameComponentMultipleTimes: boolean;
  readonly sortOrder: number;
  readonly components: readonly PublishedComboComponent[];
}

/** `StorefrontCatalogQuery.MenuComboComponent`, transcribed. `amountMinor` is per unit; null is no price, never free. */
export interface PublishedComboComponent {
  readonly componentId: string;
  readonly variantId: string;
  readonly productId: string | null;
  readonly name: string;
  readonly variantName: string | null;
  readonly defaultQuantity: number;
  readonly sortOrder: number;
  readonly orderable: boolean;
  readonly amountMinor: number | null;
}

/** `StorefrontCatalogQuery.MenuModifierGroupPolicy`: how one product uses a group, already the effective values. */
export interface PublishedModifierGroupPolicy {
  readonly modifierGroupId: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
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
  /** Row 2.1b: the coded comment presets this product offers, in the catalogue's own order. */
  readonly commentPresets: readonly PublishedCommentPreset[];
  /** ADR 0136: the combo groups whose container is one of this product's variants; absent or empty on a product that is no combo. */
  readonly comboGroupIds?: readonly string[];
  /** ADR 0136: this product's own required/min/max for an attached group, where it overrides the shared group's. */
  readonly modifierGroupPolicies?: readonly PublishedModifierGroupPolicy[];
}

/** `StorefrontCatalogQuery.CommentPresetOption`, transcribed from the controller. */
export interface PublishedCommentPreset {
  readonly code: string;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  /** Every wording the preset has, by locale: the triple plus any locale a brand offers beyond it. */
  readonly labels?: Readonly<Record<string, string>>;
  /** The wording resolved for the requested language, then the brand's default. */
  readonly label?: string;
}

export interface PublishedVariant {
  readonly variantId: string;
  /** ADR 0136: the groups this portion carries of its own, on top of its product's; absent or empty when it carries none. */
  readonly modifierGroupIds?: readonly string[];
  /** ADR 0136: the rules this portion holds the customer to for those groups (and any of its product's it overrides), already the effective values. */
  readonly modifierGroupPolicies?: readonly PublishedModifierGroupPolicy[];
  readonly sku: string | null;
  readonly unitCode: string | null;
  readonly isDefault: boolean;
  /** False means shown and sold out, not hidden. The server already dropped what
   * this location does not offer. */
  readonly orderable: boolean;
  /** Null when unpriced. Never zero for "no price". */
  readonly amountMinor: number | null;
  /**
   * Row 4.2g: false means this variant's own sale schedule excludes the
   * current moment -- shown, distinct from `orderable` (86'd). The product
   * page must refuse adding a variant while this is false.
   */
  readonly onSaleNow: boolean;
  /**
   * Rows 4.4c/4.4d: set only for a QUANTITY-tracked item once remaining
   * stock has dropped to a small displayed threshold, and never above it
   * (ADR 0017's own "quantity need not be exposed publicly") -- omitted
   * (null) is the ordinary case, not "unlimited".
   */
  readonly remainingQuantity: number | null;
  /**
   * ADR 0137: `StorefrontCatalogQuery.PhysicalFacts`. Omitted for a fixed unit sold whole (most
   * of the menu); for a catchweight variant `amountMinor` is the price per
   * `catchweightQuantumGrams`.
   */
  readonly physical?: PhysicalFacts | null;
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
  /** What the customer reads, in their language then the brand's; null/absent when nobody named the option. */
  readonly name?: string | null;
  /** ADR 0136: the choices taking this option opens, with the rules for them; absent or empty when it opens nothing. */
  readonly nestedGroups?: readonly PublishedModifierGroupPolicy[];
}
