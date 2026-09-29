import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { formatMoney, money } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import type { MenuItem } from '../../types/home.types';
import {
  itemAvailability,
  preferredSellableVariant,
  type ItemAvailability,
} from '../../utils/item-availability';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * One dish on the menu grid.
 *
 * <h2>The states a customer must be able to tell apart</h2>
 *
 * A dish that cannot be bought right now is **shown**, never hidden, and says
 * which of two things it is: *sold out* (86'd, or stock is gone -- rows
 * 4.4c/4.4d) or *not on sale right now* (its own sale schedule excludes this
 * moment -- row 4.2g, "try again at breakfast"). Both read from
 * {@link itemAvailability}, the single definition the product page, the basket
 * and the table screen share. A dish that can still be bought but is nearly
 * gone shows its remaining count.
 *
 * Neither state removes the link: the customer may still read the dish, and the
 * product page refuses the add for the same reason. The platform's own
 * `ITEM_OUT_OF_SALE_WINDOW` / sold-out refusals remain the last line, not the
 * first.
 *
 * <h2>`linked`</h2>
 *
 * The table-QR screen shows the same cards without a link: the product page
 * adds to the delivery/pickup basket, which is not what a guest seated at a
 * table is ordering into.
 */
@Component({
  selector: 'app-dish-card',
  standalone: true,
  imports: [NgTemplateOutlet, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dish-card.component.html',
  styleUrl: './dish-card.component.scss',
})
export class DishCardComponent {
  private readonly translate = inject(TranslateService);

  readonly item = input.required<MenuItem>();
  /** ISO currency of the menu the item came from; null before any menu is read. */
  readonly currency = input<string | null>(null);
  readonly linked = input(true);

  protected readonly availability = computed<ItemAvailability>(() => itemAvailability(this.item()));

  protected readonly unavailable = computed(() => this.availability() !== 'AVAILABLE');

  /**
   * The remaining count of the portion a customer would actually get, when it
   * is low. Never shown on a dish that is not buyable: "0 left" beside a
   * sold-out badge would say the same thing twice.
   */
  protected readonly lowStock = computed<number | null>(() => {
    if (this.unavailable()) {
      return null;
    }
    return preferredSellableVariant(this.item())?.remainingQuantity ?? null;
  });

  protected readonly priceLabel = computed(() => {
    this.translate.current();
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(this.item().price, this.currency() ?? 'UZS'), unit);
  });
}
