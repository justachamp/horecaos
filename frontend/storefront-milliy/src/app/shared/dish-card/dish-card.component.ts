import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { formatMoney, money } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import type { MenuItem, MenuItemVariant } from '../../types/home.types';
import {
  itemAvailability,
  preferredSellableVariant,
  variantAvailability,
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
 *
 * <h2>`ordering`</h2>
 *
 * At a table that takes orders (ADR 0047, `ORDER_AND_PAY`) the card carries its
 * own way into the *table's* basket: an add button, then a stepper, for each
 * portion that can be bought right now. The card only reports what the guest
 * asked for (`quantityChange`); the basket, the sign-in and the platform are the
 * screen's. Controls are drawn only on an unlinked card -- a button inside a link
 * is two actions in one tap target -- and never on a dish that cannot be bought
 * (its badge says why).
 *
 * A dish whose modifier group *must* be chosen from (`required`, or a non-zero
 * minimum) is not offered here: choosing needs the product page's picker, and
 * that page adds to the delivery basket. The card says a member of staff will
 * help rather than adding a plain dish the kitchen would have to refuse.
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

  /** Draw the controls that put this dish in the table's basket. Off everywhere but an ordering table. */
  readonly ordering = input(false);
  /** What the table's basket holds of each portion of this dish, by variant id. */
  readonly quantities = input<Readonly<Record<string, number>>>({});
  /** A write to the basket is in flight: the controls wait rather than stack. */
  readonly busy = input(false);
  /** The guest asked for `quantity` of a portion (0 takes it out of the basket). */
  readonly quantityChange = output<{ variantId: string; quantity: number }>();

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

  /** True when the dish cannot be added plain: a group the guest must choose from. */
  protected readonly needsStaff = computed(() =>
    this.item().modifierGroups.some((group) => group.required || group.minimumSelections > 0),
  );

  /** Only the portions that can be bought right now: an 86'd or out-of-window one is not offered. */
  protected readonly portions = computed<readonly MenuItemVariant[]>(() =>
    this.item().variants.filter((variant) => variantAvailability(variant) === 'AVAILABLE'),
  );

  protected readonly showControls = computed(
    () => this.ordering() && !this.linked() && !this.needsStaff() && this.portions().length > 0,
  );

  protected readonly showStaffNote = computed(
    () => this.ordering() && !this.linked() && !this.unavailable() && this.needsStaff(),
  );

  protected quantityOf(variantId: string): number {
    return this.quantities()[variantId] ?? 0;
  }

  protected request(variantId: string, quantity: number): void {
    if (!this.busy()) {
      this.quantityChange.emit({ variantId, quantity: Math.max(0, quantity) });
    }
  }

  protected portionPrice(variant: MenuItemVariant): string {
    this.translate.current();
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(variant.price, this.currency() ?? 'UZS'), unit);
  }

  protected readonly priceLabel = computed(() => {
    this.translate.current();
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(this.item().price, this.currency() ?? 'UZS'), unit);
  });
}
