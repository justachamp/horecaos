import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, from, switchMap } from 'rxjs';
import { CartHintBadgeComponent } from '../../shared/cart-hint-badge/cart-hint-badge.component';
import { FoodCarouselComponent } from '../../shared/food-carousel/food-carousel.component';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import type { MenuItem, MenuItemVariant, PopularCategory } from '../../types/home.types';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';
import { FavouritesService } from '../../services/favourites.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';

export interface ProductVariantDisplay {
  id: string;
  label: string;
  price: number;
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
    .map((v: MenuItemVariant) => ({ id: v.id, label: v.name, price: v.price }));
  return {
    id: item.id,
    title: item.name,
    description: item.description ?? '',
    image: item.image ?? '/jizbiz/logo/Logo-sq.png',
    price: formatPriceFn(item.price),
    badge,
    variants,
  };
}

@Component({
  selector: 'app-product',
  standalone: true,
  imports: [CommonModule, RouterLink, FoodCarouselComponent, CartHintBadgeComponent, TranslatePipe],
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
  readonly cartService = inject(UiCartService);

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

  /** Variant id for the single-product add-to-cart bar (first active variant, else item id). */
  variantId = computed(() => {
    const item = this.rawItem();
    const activeVariants = (item?.variants ?? []).filter((v: MenuItemVariant) => v.active);
    return activeVariants[0]?.id ?? item?.id ?? null;
  });

  /** Quantity in cart - synced from cart */
  qty = computed(() => {
    const vid = this.variantId();
    if (!vid) return 0;
    const items = this.cartService.items();
    const found = items.find((i) => i.variant_id === vid);
    return found?.quantity ?? 0;
  });

  /** Cart line for current variant (for line total in bottom bar) */
  readonly cartLine = computed(() => {
    const vid = this.variantId();
    if (!vid) return null;
    this.cartService.items();
    return this.cartService.items().find((i) => i.variant_id === vid) ?? null;
  });

  /** Formatted line total (unit × qty) for bottom bar */
  readonly variantLineTotal = computed(() => {
    this.translate.current();
    const line = this.cartLine();
    if (!line) return '';
    return this.formatPrice(line.price * line.quantity);
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
    if (!this.cartService.cartData()) {
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
        })
      )
      .subscribe({
        next: (item) => {
          this.loading.set(false);
          this.rawItem.set(item);
          this.product.set(menuItemToDisplay(item, (n) => this.formatPrice(n)));
          this.loadRecommendations(item.id, item);
          if (typeof window !== 'undefined') window.scrollTo(0, 0);
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(
            err?.error?.message ?? err?.message ?? "Ma'lumot yuklanmadi"
          );
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
    return this.cartService.items().find((i) => i.variant_id === variantId)?.quantity ?? 0;
  }

  lineTotalForVariant(variantId: string): string {
    this.translate.current();
    const line = this.cartService.items().find((i) => i.variant_id === variantId);
    if (!line) return '';
    return this.formatPrice(line.price * line.quantity);
  }

  increaseVariant(variantId: string): void {
    if (!variantId || this.cartService.updating()) return;
    const run = (): void => {
      const line = this.cartService.items().find((i) => i.variant_id === variantId);
      if (line) {
        this.cartService.increaseQuantity(line);
      } else {
        void this.cartService.add(variantId, 1);
      }
    };
    if (!this.cartService.cartData()) {
      void this.cartService.load().then(run);
    } else {
      run();
    }
  }

  decreaseVariant(variantId: string): void {
    const line = this.cartService.items().find((i) => i.variant_id === variantId);
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
    if (this.favouriting()) return;
    const item = this.rawItem();
    if (!item) return;
    this.favouriting.set(true);
    // Optimistic: the heart flips at once and is put back if the platform
    // refuses, so the screen never keeps a state the server rejected.
    const marked = this.isFavourite();
    const change = marked
      ? this.favourites.remove(item.id)
      : this.favourites.add(item.id);
    change.catch(() => {
      // Reported by the error interceptor; the flip has already been undone.
    }).finally(() => this.favouriting.set(false));
  }

  formatPrice(value: number): string {
    const c = this.translate.get('common.currency') || "so'm";
    return `${value.toLocaleString('uz-UZ')} ${c}`;
  }

}
