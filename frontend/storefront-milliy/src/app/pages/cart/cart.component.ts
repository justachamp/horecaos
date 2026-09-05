import { ChangeDetectionStrategy, Component, type OnInit, inject, signal } from '@angular/core';
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

  /** Comma-joined modifier labels for one line, or '' when it has none. */
  protected addonLabel(item: CartResponseItem): string {
    return item.modifiers.map((modifier) => modifier.label).filter(Boolean).join(', ');
  }
}
