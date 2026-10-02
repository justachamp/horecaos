import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { IconComponent } from '../../shared/icon/icon.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { UiCartService } from '../../services/ui-cart.service';
import type { CartResponseItem } from '../../types/cart.types';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Savat: the basket, its quantity controls, and the route to checkout.
 *
 * `UiCartService` owns every number here -- the subtotal, the delivery-fee
 * preview and the total all come from its computed signals, which are
 * themselves the platform's own pricing answer (`POST .../pricing`) rather
 * than a client-side sum. This screen adds no arithmetic of its own.
 *
 * The design's line cards show a portion/weight caption under each dish
 * name; the platform's `MenuItemVariant` carries no customer-facing name for
 * that (only an authoring SKU/unit code -- see `MenuService`'s own doc
 * comment), so it is left off rather than printed as a database value. The
 * chosen modifiers *are* shown, because those the platform does resolve to a
 * label.
 */
@Component({
  selector: 'app-cart',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cart.component.html',
  styleUrl: './cart.component.scss',
})
export class CartComponent implements OnInit {
  protected readonly cart = inject(UiCartService);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');

  /**
   * The specific reason the basket could not be loaded, or null when there is
   * nothing more to say than "it could not be loaded".
   *
   * The generic sentence is left out on purpose: printing "Something went
   * wrong" under "The cart could not be loaded" says the same thing twice.
   */
  protected readonly loadErrorDetail = computed(() => {
    const key = this.cart.errorKey();
    return key && key !== 'errors.generic' ? key : null;
  });

  /**
   * Checkout is offered only while every line can still be bought. A line the
   * menu has marked unavailable (sold out, or outside its sale window) makes
   * the platform refuse to price the whole basket, so the way forward is to
   * remove it -- not to tap through to a checkout that cannot succeed.
   */
  protected readonly canCheckout = computed(() => this.cart.items().every((item) => item.active));

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.state.set('loading');
    await this.cart.load();
    this.state.set(this.cart.error() ? 'error' : 'ready');
  }

  protected increase(item: CartResponseItem): void {
    this.cart.increaseQuantity(item);
  }

  protected decrease(item: CartResponseItem): void {
    this.cart.decreaseQuantity(item);
  }

  protected goHome(): void {
    void this.router.navigate(['/home']);
  }

  protected goCheckout(): void {
    void this.router.navigate(['/checkout']);
  }

  /**
   * ADR 0136: a combo line's components, as `Burger, Cola 0.5 L ×4` -- the units a combo puts on
   * the order -- or '' on every line that is no combo.
   */
  protected comboLabel(item: CartResponseItem): string {
    return (item.comboComponents ?? [])
      .map((component) => {
        const name = component.variantName
          ? `${component.name} ${component.variantName}`
          : component.name;
        return component.quantity > 1 ? `${name} ×${component.quantity}` : name;
      })
      .join(', ');
  }

  /** Comma-joined modifier labels for one line, or '' when it has none. */
  protected addonLabel(item: CartResponseItem): string {
    return item.modifiers
      .map((modifier) => modifier.label)
      .filter(Boolean)
      .join(', ');
  }
}
