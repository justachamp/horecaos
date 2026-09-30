import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { MenuCategory, MenuProduct, StorefrontMenu } from './new-order-api';

/**
 * The category grid of the New order screen's menu pane (§5.5): every category
 * with its products as buttons, a product with nothing orderable left drawn with
 * a stop chip rather than hidden.
 *
 * Presentation only; a click is reported and the screen decides what adding a
 * product means (a modifier dialog, a plain line). It is its own component so the
 * grid's rules do not count against the screen's component-style budget.
 */
@Component({
  selector: 'q-new-order-menu-grid',
  imports: [TPipe],
  templateUrl: './new-order-menu-grid.html',
  styleUrl: './new-order-menu-grid.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrderMenuGrid {
  readonly menu = input.required<StorefrontMenu>();

  readonly productSelected = output<MenuProduct>();

  /** The category's own row — orders.md §5.5's fallback for a caller browsing aloud. */
  protected productsIn(category: MenuCategory): readonly MenuProduct[] {
    const byId = new Map(this.menu().products.map((product) => [product.productId, product]));
    return category.productIds
      .map((id) => byId.get(id))
      .filter((product): product is MenuProduct => product !== undefined);
  }

  /** A struck-through стоп chip, orders.md §5.5: visible and not addable, never hidden. */
  protected isAnyVariantOrderable(product: MenuProduct): boolean {
    return product.variants.some((variant) => variant.orderable);
  }
}
