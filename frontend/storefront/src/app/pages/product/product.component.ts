import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, from, switchMap } from 'rxjs';
import { CartHintBadgeComponent } from '../../shared/cart-hint-badge/cart-hint-badge.component';
import { FoodCarouselComponent } from '../../shared/food-carousel/food-carousel.component';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import type {
  MenuItem,
  MenuItemComboGroup,
  MenuItemCommentPreset,
  MenuItemModifierGroup,
  MenuItemVariant,
} from '../../types/home.types';
import { ComboChoicesComponent } from '../../shared/combo-choices/combo-choices.component';
import {
  type ComboPickWire,
  type ComboPicks,
  canBeSatisfied,
  comboUnitAmountMinor,
  comboValid,
  picksOnTheWire,
  samePicks,
} from '../../utils/combo-selection';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';
import { FavouritesService } from '../../services/favourites.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { FEATURES } from '../../core/config/features';
import { Session } from '../../core/auth/session';
import { EcommerceItem, pushEcommerceEvent } from '../../core/analytics/ecommerce-events';
import type { CartResponseItem } from '../../types/cart.types';
import { presetLabelFor } from '../../utils/preset-label';
import { PhysicalFactsComponent } from '../../shared/physical-facts/physical-facts.component';
import {
  formatQuantity,
  initialQuantity,
  portionStep,
  unitPriceMinor,
  type PhysicalFacts,
} from '../../utils/physical';

/** Matches `CartOrderStatusComponent`'s own fallback -- the storefront's launch scope is UZS-only. */
const FALLBACK_CURRENCY = 'UZS';

export interface ProductVariantDisplay {
  id: string;
  label: string;
  price: number;
  /**
   * Row 4.2g: false means this variant's own sale schedule excludes the
   * current moment. Shown (unlike an unorderable variant, which never
   * reaches this display at all -- see {@link menuItemToDisplay}'s own
   * filter), but add-to-cart must refuse it.
   */
  onSaleNow: boolean;
  /** Rows 4.4c/4.4d: a low remaining count, or null for the ordinary case. See `MenuItemVariant`'s own doc. */
  remainingQuantity: number | null;
  /** ADR 0137: the variant's weight, portions, weighed pricing and КБЖУ; null for a fixed unit sold whole. */
  physical: PhysicalFacts | null;
  /**
   * What the add button reads: the price, or for a variant sold by weight an estimate for one unit
   * at its nominal weight (its price row is per quantum, which is not what a unit costs).
   */
  priceLabel: string;
}

export interface ProductDisplay {
  id: string;
  title: string;
  description: string;
  image: string;
  price: string;
  badge?: string;
  variants: ProductVariantDisplay[];
}

function menuItemToDisplay(item: MenuItem, formatPriceFn: (n: number) => string): ProductDisplay {
  const badge =
    item.has_discount && item.price !== item.price_without_discount
      ? `-${Math.round((1 - item.price / item.price_without_discount) * 100)} %`
      : undefined;
  const variants: ProductVariantDisplay[] = (item.variants ?? [])
    .filter((v: MenuItemVariant) => v.active)
    .map((v: MenuItemVariant) => ({
      id: v.id,
      label: v.name,
      price: v.price,
      onSaleNow: v.onSaleNow,
      remainingQuantity: v.remainingQuantity,
      physical: v.physical ?? null,
      priceLabel: v.physical?.catchweight
        ? `≈ ${formatPriceFn(unitPriceMinor(v.price, v.physical))}`
        : formatPriceFn(v.price),
    }));
  return {
    id: item.id,
    title: item.name,
    description: item.description ?? '',
    image: item.image ?? '/assets/logo/placeholder-item.png',
    price: formatPriceFn(item.price),
    badge,
    variants,
  };
}

/** The cart line for one variant with exactly this modifier selection and these combo picks. */
function lineMatches(
  line: {
    variant_id: string;
    modifierOptionIds: readonly string[];
    comboPicks?: readonly ComboPickWire[];
  },
  variantId: string,
  selection: readonly string[],
  picks: readonly ComboPickWire[],
): boolean {
  return (
    line.variant_id === variantId &&
    sameOptionIds(line.modifierOptionIds, selection) &&
    samePicks(line.comboPicks ?? [], picks)
  );
}

/** Sorted-value comparison; the order a customer picked options in never matters. */
function sameOptionIds(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const sortedA = [...a].sort();
  const sortedB = [...b].sort();
  return sortedA.every((id, index) => id === sortedB[index]);
}

@Component({
  selector: 'app-product',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FoodCarouselComponent,
    CartHintBadgeComponent,
    ComboChoicesComponent,
    PhysicalFactsComponent,
    TranslatePipe,
  ],
  templateUrl: './product.component.html',
  styleUrl: './product.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly history = inject(NavigationHistoryService);
  private readonly menuService = inject(MenuService);
  private readonly langService = inject(LangService);
  private readonly translate = inject(TranslateService);
  private readonly favourites = inject(FavouritesService);
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  readonly cartService = inject(UiCartService);

  /** Gates the favourites heart until the backend exists. See `FEATURES`. */
  readonly favouritesEnabled = FEATURES.favourites;

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly product = signal<ProductDisplay | null>(null);
  readonly favouriting = signal(false);
  /** Raw menu item for variant IDs (cart API) */
  private readonly rawItem = signal<MenuItem | null>(null);

  variants = computed(() => this.product()?.variants ?? []);

  /** True when the product has at least one orderable variant (shown as in-body option rows). */
  readonly hasActiveVariants = computed(() => this.variants().length > 0);

  readonly hasCartItems = computed(() => this.cartService.totalItemsCount() > 0);

  /**
   * Variant id for the single-product add-to-cart bar (the first active
   * variant, or `null` when none is orderable).
   *
   * Never falls back to the product's own id: `item.id` and a variant id are
   * different identifier spaces on the wire, and a product with every
   * variant unorderable (86'd or otherwise inactive) has no id here that the
   * cart API would accept as a `variant_id`. `add()`/`increaseVariant()`
   * already refuse a falsy id, so `null` here is what keeps the sole-variant
   * bottom bar's add button from ever sending the product id as a variant.
   */
  variantId = computed(() => {
    const item = this.rawItem();
    const activeVariants = (item?.variants ?? []).filter((v: MenuItemVariant) => v.active);
    return activeVariants[0]?.id ?? null;
  });

  /** True when the product has no orderable variant at all -- the sole-variant bottom bar's add button must disable rather than silently no-op. */
  readonly noOrderableVariant = computed(() => this.variantId() === null);

  /**
   * ADR 0136: the choices this product's combo asks for, empty when it is no combo. A combo's own
   * variant is never priced or sold on its own: what the customer adds is the components picked here.
   */
  readonly comboGroups = computed<MenuItemComboGroup[]>(() => this.rawItem()?.comboGroups ?? []);

  readonly isCombo = computed(() => this.comboGroups().length > 0);

  /** A group that no orderable component can fill makes the whole combo unorderable for now. */
  readonly comboUnavailable = computed(() => this.comboGroups().some((g) => !canBeSatisfied(g)));

  /** componentId -> how many times it was picked. */
  readonly comboPicks = signal<ComboPicks>({});

  /** Whether the customer has touched the combo, so its hint names the groups still short instead of greeting them with errors. */
  readonly comboTouched = signal(false);

  setComboPicks(next: ComboPicks): void {
    this.comboPicks.set(next);
    this.comboTouched.set(true);
  }

  /** What one combo costs with the picks so far, or null while a picked component has no price. */
  readonly comboUnitAmount = computed(() =>
    comboUnitAmountMinor(this.comboGroups(), this.comboPicks()),
  );

  private flattenedComboPicks(): readonly ComboPickWire[] {
    return picksOnTheWire(this.comboGroups(), this.comboPicks());
  }

  /** The modifier groups this product offers (add-ons, sizes-of-topping, and so on); none on a combo, whose choices are its components. */
  readonly modifierGroups = computed<MenuItemModifierGroup[]>(() =>
    this.isCombo() ? [] : (this.rawItem()?.modifierGroups ?? []),
  );

  /** Row 2.1b: the coded comment presets this product offers, in the catalogue's own order. */
  readonly commentPresets = computed<MenuItemCommentPreset[]>(
    () => this.rawItem()?.commentPresets ?? [],
  );

  /** groupId -> the option ids currently chosen within it. */
  private readonly selectedOptions = signal<Record<string, readonly string[]>>({});

  /** Row 2.1b: the preset codes currently checked. */
  private readonly selectedPresetCodes = signal<ReadonlySet<string>>(new Set());

  isPresetSelected(code: string): boolean {
    return this.selectedPresetCodes().has(code);
  }

  togglePreset(code: string): void {
    this.selectedPresetCodes.update((current) => {
      const next = new Set(current);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  /** The checked codes, in the product's own offered order -- never click order. */
  private flattenedPresetSelection(): readonly string[] {
    const checked = this.selectedPresetCodes();
    return this.commentPresets()
      .filter((preset) => checked.has(preset.code))
      .map((preset) => preset.code);
  }

  /**
   * Row 4.2g: whether the given variant is currently sellable at all --
   * `active` (86'd) is already the display's own filter (see {@link
   * menuItemToDisplay}), so this only ever answers the sale-window half.
   */
  private variantOnSaleNow(variantId: string): boolean {
    return this.variants().find((variant) => variant.id === variantId)?.onSaleNow ?? true;
  }

  /** Row 4.2g: shown next to a blocked add-to-cart control, the sale-window twin of `modifiersIncomplete`. */
  isOutOfSaleWindow(variantId: string): boolean {
    return !this.variantOnSaleNow(variantId);
  }

  /** Row 4.2g: the single-variant bottom bar's own guard -- `variantId()` is null before the menu loads, never treated as "out of window". */
  readonly soleVariantOutOfSaleWindow = computed(() => {
    const id = this.variantId();
    return id !== null && this.isOutOfSaleWindow(id);
  });

  /** A preset's label in the customer's own language, matching `MenuService`'s own locale selection. */
  presetLabel(preset: MenuItemCommentPreset): string {
    return presetLabelFor(preset, this.langService.langId());
  }

  /**
   * Whether every required group has a selection within its min/max bounds.
   *
   * Blocks add-to-cart rather than sending a line the platform would reject
   * (or, worse, accept as "no modifiers" when the customer meant to choose
   * one) -- there is no server-side echo of what was picked to correct a
   * client guess against.
   */
  readonly modifiersValid = computed(() => {
    const selections = this.selectedOptions();
    const modifiersOk = this.modifierGroups().every((group) => {
      const count = (selections[group.id] ?? []).length;
      const min = group.required ? Math.max(group.minimumSelections, 1) : group.minimumSelections;
      const max = group.maximumSelections > 0 ? group.maximumSelections : Number.POSITIVE_INFINITY;
      return count >= min && count <= max;
    });
    // ADR 0136: and a combo is complete only when every one of its choices is within its range.
    return modifiersOk && (!this.isCombo() || comboValid(this.comboGroups(), this.comboPicks()));
  });

  /** The chosen options, flattened for the wire -- order does not matter to the platform. */
  private flattenedSelection(): readonly string[] {
    return Object.values(this.selectedOptions()).flat();
  }

  isOptionSelected(groupId: string, optionId: string): boolean {
    return (this.selectedOptions()[groupId] ?? []).includes(optionId);
  }

  /**
   * Toggles one option, respecting the group's own selection limit.
   *
   * A group whose `maximumSelections` is 1 behaves like a radio: choosing a
   * second option replaces the first rather than adding to it, because two
   * selections in a one-choice group is not a state the platform would accept
   * either.
   */
  toggleOption(group: MenuItemModifierGroup, optionId: string): void {
    this.selectedOptions.update((current) => {
      const chosen = current[group.id] ?? [];
      const isSelected = chosen.includes(optionId);
      let next: readonly string[];
      if (isSelected) {
        next = chosen.filter((id) => id !== optionId);
      } else if (group.maximumSelections === 1) {
        next = [optionId];
      } else if (group.maximumSelections > 0 && chosen.length >= group.maximumSelections) {
        return current;
      } else {
        next = [...chosen, optionId];
      }
      return { ...current, [group.id]: next };
    });
  }

  /** Quantity in cart for the current variant *and* the currently chosen modifiers. */
  qty = computed(() => {
    const vid = this.variantId();
    if (!vid) return 0;
    return this.qtyForVariant(vid);
  });

  /** Cart line for current variant and selection (for line total in bottom bar) */
  readonly cartLine = computed(() => {
    const vid = this.variantId();
    if (!vid) return null;
    const selection = this.flattenedSelection();
    const picks = this.flattenedComboPicks();
    return this.cartService.items().find((i) => lineMatches(i, vid, selection, picks)) ?? null;
  });

  /** Formatted line total (unit × qty) for bottom bar */
  readonly variantLineTotal = computed(() => {
    this.translate.current();
    const line = this.cartLine();
    if (!line) return '';
    return this.lineTotalText(line);
  });

  /** Recommendations from API populars, excluding current */
  readonly recommendationItems = signal<MenuItem[]>([]);

  readonly isFavourite = computed(() => {
    const item = this.rawItem();
    if (!item) return false;
    this.favourites.addedIds();
    this.favourites.removedIds();
    this.favourites.loaded();
    return this.favourites.isFavourite(item.id, item.is_favourite ?? false);
  });

  constructor() {
    // Mirrors BottomNavComponent's own guard: an anonymous visitor can never
    // hold a cart (see increaseVariant above), so there is nothing this read
    // could find but a stray local cart id from a previous session -- and
    // asking for it anyway would only 401.
    if (!this.cartService.cartData() && this.session.isAuthenticated()) {
      void this.cartService.load();
    }

    this.route.paramMap
      .pipe(
        takeUntilDestroyed(),
        switchMap((params) => {
          const id = params.get('id');
          if (!id) {
            this.loading.set(false);
            return EMPTY;
          }
          this.loading.set(true);
          this.error.set(null);
          return from(
            this.menuService.item(id, this.langService.langId()).then((item) => {
              if (!item) {
                // The menu is one document, so a product that is not in it is a
                // product this location does not serve -- a dead link or a dish
                // withdrawn since the customer opened the page.
                throw new Error('No such product on this menu.');
              }
              return item;
            }),
          );
        }),
      )
      .subscribe({
        next: (item) => {
          this.loading.set(false);
          this.rawItem.set(item);
          // A fresh product is a fresh set of choices -- a selection left over
          // from the previous item on this route could name an option id that
          // does not even exist on this one.
          this.selectedOptions.set({});
          this.selectedPresetCodes.set(new Set());
          this.comboPicks.set({});
          this.comboTouched.set(false);
          this.product.set(menuItemToDisplay(item, (n) => this.formatPrice(n)));
          this.loadRecommendations(item.id, item);
          this.trackViewItem(item);
          if (typeof window !== 'undefined') window.scrollTo(0, 0);
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(err?.error?.message ?? err?.message ?? "Ma'lumot yuklanmadi");
        },
      });
  }

  /**
   * Other things on the menu, as a recommendation rail.
   *
   * The legacy screen used the "populars" section the old backend assembled.
   * Nothing on the platform produces one, so this shows other orderable items
   * from the same menu instead. It is honestly a weaker rail and it is labelled
   * as what it is; inventing a popularity ranking from the first eight products
   * would be a claim the platform has no data to support.
   */
  private loadRecommendations(excludeId: string, _currentItem: MenuItem): void {
    this.menuService
      .home(this.langService.langId())
      .then((data) => {
        const items = data.menu.category_items
          .flatMap((category) => category.items)
          .filter((item) => item.id !== excludeId && item.active)
          .slice(0, 8);
        this.recommendationItems.set(items);
      })
      .catch(() => this.recommendationItems.set([]));
  }

  add(): void {
    const vid = this.variantId();
    if (!vid) return;
    this.increaseVariant(vid);
  }

  remove(): void {
    const vid = this.variantId();
    if (!vid) return;
    this.decreaseVariant(vid);
  }

  qtyForVariant(variantId: string): number {
    const selection = this.flattenedSelection();
    const picks = this.flattenedComboPicks();
    return (
      this.cartService.items().find((i) => lineMatches(i, variantId, selection, picks))?.quantity ??
      0
    );
  }

  lineTotalForVariant(variantId: string): string {
    this.translate.current();
    const selection = this.flattenedSelection();
    const picks = this.flattenedComboPicks();
    const line = this.cartService.items().find((i) => lineMatches(i, variantId, selection, picks));
    if (!line) return '';
    return this.lineTotalText(line);
  }

  /** A line's amount, marked as an estimate when it is sold by weight (ADR 0137). */
  private lineTotalText(line: CartResponseItem): string {
    const amount = this.formatPrice(this.cartService.lineAmount(line));
    return line.physical?.catchweight ? `≈ ${amount}` : amount;
  }

  private physicalOf(variantId: string): PhysicalFacts | null {
    return (this.rawItem()?.variants ?? []).find((v) => v.id === variantId)?.physical ?? null;
  }

  /** `0,5 порц.`, `3 шт` — a quantity written as the customer's language writes it. */
  quantityLabel(quantity: number, variantId: string): string {
    this.translate.current();
    const unit = this.translate.get(
      this.physicalOf(variantId)?.splittable ? 'physical.portionsUnit' : 'common.itemsUnit',
    );
    return `${formatQuantity(quantity, this.langService.langId())} ${unit}`;
  }

  /**
   * The price a variant's add button reads. A combo's container has no price: while the picks are
   * incomplete this is the least a combo can cost, once they are complete it is what this one costs,
   * and a combo with a pick that has no price says so rather than showing a smaller sum.
   */
  variantPriceLabel(variant: ProductVariantDisplay): string {
    this.translate.current();
    if (!this.isCombo()) {
      // Its own label, which for a variant sold by weight is the estimate of one unit (ADR 0137).
      return variant.priceLabel;
    }
    if (comboValid(this.comboGroups(), this.comboPicks())) {
      const unit = this.comboUnitAmount();
      return unit === null ? this.translate.get('product.comboNotPriced') : this.formatPrice(unit);
    }
    return this.translate.getWithParams('product.fromPrice', {
      price: this.formatPrice(variant.price),
    });
  }

  /**
   * Adds to, or bumps, the line for this variant and the currently selected
   * modifiers.
   *
   * Blocked while a required group is unsatisfied -- the template disables
   * the button on `!modifiersValid()`, and this is the second guard, since a
   * disabled button is not a security boundary against a stray call. Row
   * 4.2g adds a second block, for the identical reason: a variant outside
   * its own sale window is shown but must not reach the cart from here --
   * the platform's own `ITEM_OUT_OF_SALE_WINDOW` refusal is the last line,
   * not the first.
   */
  increaseVariant(variantId: string): void {
    if (
      !variantId ||
      this.cartService.updating() ||
      !this.modifiersValid() ||
      !this.variantOnSaleNow(variantId)
    )
      return;
    // No anonymous cart on the platform: POST /carts requires a session. See
    // app.routes.ts's comment on /cart for the other half of this boundary.
    if (!this.session.isAuthenticated()) {
      this.router.navigate(['/auth/login']).catch(() => {});
      return;
    }
    const selection = this.flattenedSelection();
    const presetSelection = this.flattenedPresetSelection();
    const comboPicks = this.flattenedComboPicks();
    const run = (): void => {
      const line = this.cartService
        .items()
        .find((i) => lineMatches(i, variantId, selection, comboPicks));
      // ADR 0106, gap-map row 10.8e: fired for both branches below -- a
      // bumped existing line and a brand-new one are the same customer
      // action, one more unit of this variant landing in the cart -- right
      // here rather than earlier in increaseVariant(), so a call the
      // session/modifier guards above refuse never counts as an add.
      this.trackAddToCart(variantId);
      if (line) {
        this.cartService.increaseQuantity(line);
      } else {
        // ADR 0137: a first tap puts one whole portion in the basket, or the first quantity the
        // cart accepts for a portion size that does not divide one. A combo's container has no
        // physical facts, so it starts at one.
        const quantity = initialQuantity(portionStep(this.physicalOf(variantId)));
        if (comboPicks.length > 0) {
          void this.cartService.add(
            variantId,
            quantity,
            undefined,
            selection,
            presetSelection,
            comboPicks,
          );
        } else {
          void this.cartService.add(variantId, quantity, undefined, selection, presetSelection);
        }
      }
    };
    if (!this.cartService.cartData()) {
      void this.cartService.load().then(run);
    } else {
      run();
    }
  }

  decreaseVariant(variantId: string): void {
    const selection = this.flattenedSelection();
    const picks = this.flattenedComboPicks();
    const line = this.cartService.items().find((i) => lineMatches(i, variantId, selection, picks));
    if (!line || this.cartService.updating()) return;
    this.cartService.decreaseQuantity(line);
  }

  back(): void {
    this.history.back('/home');
  }

  toggleFavourite(event?: Event): void {
    if (event) {
      event.stopPropagation();
      event.preventDefault();
    }
    if (!this.favouritesEnabled || this.favouriting()) return;
    // Ownership-authorised (/me/favourites); no guest state for a heart, so
    // an anonymous tap goes to sign-in rather than an optimistic flip that a
    // 401 immediately reverts.
    if (!this.session.isAuthenticated()) {
      this.router.navigate(['/auth/login']).catch(() => {});
      return;
    }
    const item = this.rawItem();
    if (!item) return;
    this.favouriting.set(true);
    // Optimistic: the heart flips at once and is put back if the platform
    // refuses, so the screen never keeps a state the server rejected.
    const marked = this.isFavourite();
    const change = marked ? this.favourites.remove(item.id) : this.favourites.add(item.id);
    change
      .catch(() => {
        // Reported by the error interceptor; the flip has already been undone.
      })
      .finally(() => this.favouriting.set(false));
  }

  formatPrice(value: number): string {
    const c = this.translate.get('common.currency') || "so'm";
    return `${value.toLocaleString('uz-UZ')} ${c}`;
  }

  /**
   * ADR 0106, gap-map row 10.8e: `view_item`, GA4 ecommerce event contract
   * v1, fired once per product load. No PII -- an id, a name and a price,
   * the same three fields {@link CartOrderStatusComponent}'s own `purchase`
   * event carries per line.
   */
  private trackViewItem(item: MenuItem): void {
    const ecommerceItem: EcommerceItem = {
      item_id: item.id,
      item_name: item.name,
      price: item.price,
      quantity: 1,
    };
    pushEcommerceEvent('view_item', {
      currency: this.menuService.currency() ?? FALLBACK_CURRENCY,
      value: item.price,
      items: [ecommerceItem],
    });
  }

  /**
   * ADR 0106, gap-map row 10.8e: `add_to_cart`, fired once per unit the
   * customer actually commits to adding -- `increaseVariant` always adds
   * exactly one, so `quantity` is always `1` here regardless of how many are
   * now in the line.
   */
  private trackAddToCart(variantId: string): void {
    const ecommerceItem = this.ecommerceItemForVariant(variantId);
    if (!ecommerceItem) {
      return;
    }
    pushEcommerceEvent('add_to_cart', {
      currency: this.menuService.currency() ?? FALLBACK_CURRENCY,
      value: ecommerceItem.price,
      items: [ecommerceItem],
    });
  }

  /**
   * Resolves a variant id to its own name and price for the GA4 event
   * contract -- a product with variants names one of them. `variantId` no
   * longer ever hands this a bare item id (see its own doc), but
   * `increaseVariant` takes a plain string and this stays defensive against a
   * direct call carrying the item's own id -- the item's own name and price
   * are the right answer for that case too.
   */
  private ecommerceItemForVariant(variantId: string): EcommerceItem | null {
    const item = this.rawItem();
    if (!item) {
      return null;
    }
    const variant = (item.variants ?? []).find((v: MenuItemVariant) => v.id === variantId);
    if (variant) {
      return { item_id: variant.id, item_name: variant.name, price: variant.price, quantity: 1 };
    }
    if (variantId === item.id) {
      return { item_id: item.id, item_name: item.name, price: item.price, quantity: 1 };
    }
    return null;
  }
}
