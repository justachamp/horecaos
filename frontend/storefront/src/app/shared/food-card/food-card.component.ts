import { ChangeDetectionStrategy, Component, input, signal, inject, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { TranslateService } from '../../services/translate.service';
import { FavouritesService } from '../../services/favourites.service';
import { UiCartService } from '../../services/ui-cart.service';
import { LangService } from '../../services/lang.service';
import { TranslatePipe } from '../translate/translate.pipe';
import { PhysicalFactsComponent } from '../physical-facts/physical-facts.component';
import {
  formatQuantity,
  formatWeight,
  initialQuantity,
  portionStep,
  type PhysicalFacts,
} from '../../utils/physical';
import type { MenuItem, MenuItemVariant } from '../../types/home.types';
import { FEATURES } from '../../core/config/features';
import { Session } from '../../core/auth/session';

@Component({
  selector: 'app-food-card',
  standalone: true,
  imports: [CommonModule, RouterLink, PhysicalFactsComponent, TranslatePipe],
  templateUrl: './food-card.component.html',
  styleUrl: './food-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FoodCardComponent {
  /** Full menu item from API (response.menu.category_items[].items or populars) */
  item = input.required<MenuItem>();

  /** Gates the favourites heart until the backend exists. See `FEATURES`. */
  readonly favouritesEnabled = FEATURES.favourites;

  favouriting = signal(false);

  private readonly translate = inject(TranslateService);
  private readonly lang = inject(LangService);
  private readonly favourites = inject(FavouritesService);
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  readonly cart = inject(UiCartService);

  /** First active variant id for cart API (fallback: menu item id) */
  readonly variantId = computed(() => {
    const menu = this.item();
    const active = (menu.variants ?? []).filter((v: MenuItemVariant) => v.active);
    return active[0]?.id ?? menu.id;
  });

  /** Cart line for this variant, if any */
  readonly cartLine = computed(() => {
    const vid = this.variantId();
    this.cart.items();
    return this.cart.items().find((i) => i.variant_id === vid) ?? null;
  });

  readonly qty = computed(() => this.cartLine()?.quantity ?? 0);

  /** The variant the card sells, and what it physically is (ADR 0137); null for a fixed unit sold whole. */
  readonly physical = computed<PhysicalFacts | null>(() => {
    const menu = this.item();
    const active = (menu.variants ?? []).filter((v: MenuItemVariant) => v.active);
    return active[0]?.physical ?? null;
  });

  /** Line amount for the pill when qty ≥ 1, through the cart so a portion or a weighed line is priced as the platform will. */
  readonly lineTotalFormatted = computed(() => {
    this.translate.current();
    const line = this.cartLine();
    if (!line) return this.formatMoney(0);
    const amount = this.formatMoney(this.cart.lineAmount(line));
    return line.physical?.catchweight ? `≈ ${amount}` : amount;
  });

  /** `0,5 порц.`, `3 шт` — the quantity as the customer's language writes it. */
  readonly qtyLabel = computed(() => {
    this.translate.current();
    const unit = this.translate.get(
      this.physical()?.splittable ? 'physical.portionsUnit' : 'common.itemsUnit',
    );
    return `${formatQuantity(this.qty(), this.lang.langId())} ${unit}`;
  });

  /** Display title */
  title = computed(() => this.item().name);

  /** Short description (trimmed) */
  description = computed(() => {
    const d = this.item().description?.trim();
    return d || null;
  });

  /**
   * The price on the add button. For a variant sold by weight the price is per quantum, so it is
   * said that way — "15 000 so'm per 100 g" — and never as if it were what one item costs.
   */
  priceLabel = computed(() => {
    this.translate.current();
    const physical = this.physical();
    if (physical?.catchweight && physical.catchweightQuantumGrams) {
      return this.translate.getWithParams('physical.pricePerQuantum', {
        price: this.formatMoney(this.item().price),
        quantum: formatWeight(physical.catchweightQuantumGrams, this.lang.langId()),
      });
    }
    return this.formatMoney(this.item().price);
  });

  /** Image URL */
  image = computed(() => this.item().image ?? '/assets/logo/placeholder-item.png');

  /** Whether this item is in favourites */
  isFavourite = computed(() => {
    const i = this.item();
    this.favourites.addedIds();
    this.favourites.removedIds();
    this.favourites.loaded();
    return this.favourites.isFavourite(i.id, i.is_favourite ?? false);
  });

  toggleFavourite(event: Event): void {
    event.stopPropagation();
    event.preventDefault();
    if (!this.favouritesEnabled || this.favouriting()) return;
    // Favourites is ownership-authorised (/me/favourites) and there is no
    // guest state for a heart -- unlike browsing, which is the point of this
    // card. Sent to sign in rather than let the optimistic flip below get
    // reverted a moment later by a 401 nobody explained.
    if (!this.session.isAuthenticated()) {
      this.router.navigate(['/auth/login']).catch(() => {});
      return;
    }
    const id = this.item().id;
    this.favouriting.set(true);
    // Optimistic: the heart flips at once and is put back if the platform
    // refuses, so the screen never keeps a state the server rejected.
    const marked = this.isFavourite();
    const change = marked ? this.favourites.remove(id) : this.favourites.add(id);
    change
      .catch(() => {
        // Reported by the error interceptor; the flip has already been undone.
      })
      .finally(() => this.favouriting.set(false));
  }

  increase(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    const vid = this.variantId();
    if (!vid || this.cart.updating()) return;
    // The platform has no anonymous-cart capability: POST /carts requires a
    // session (see app.routes.ts's own comment on /cart). Caught here, at
    // the actual point of intent, rather than letting the first line an
    // anonymous visitor adds come back 401.
    if (!this.session.isAuthenticated()) {
      this.router.navigate(['/auth/login']).catch(() => {});
      return;
    }
    const run = (): void => {
      const line = this.cartLine();
      if (line) {
        this.cart.increaseQuantity(line);
      } else {
        // ADR 0137: one whole portion, or the first quantity the cart accepts for a portion size that does not divide one.
        void this.cart.add(vid, initialQuantity(portionStep(this.physical())));
      }
    };
    if (!this.cart.cartData()) {
      void this.cart.load().then(run);
    } else {
      run();
    }
  }

  decrease(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    const line = this.cartLine();
    if (!line || this.cart.updating()) return;
    this.cart.decreaseQuantity(line);
  }

  private formatMoney(n: number): string {
    const c = this.translate.get('common.currency') || "so'm";
    return `${n.toLocaleString('uz-UZ')} ${c}`;
  }
}
