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
import { canBeSatisfied, isMandatory } from '../../utils/modifier-selection';
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
 * minimum) cannot be added plain: the kitchen would have to refuse it. Its portions
 * carry a **Choose** button instead, which asks the screen (`choose`) to open the
 * option picker for that portion; the picker returns the selection and the screen
 * writes the line. The card holds no selection state. Only a mandatory group with
 * fewer options than it demands (a menu published with an empty group) leaves the
 * guest nothing to choose, and there the card says a member of staff will help.
 *
 * A portion the basket *already holds* keeps its stepper even when it can no
 * longer be bought (sold out, out of its window, or its dish now needs a choice
 * or staff): the stepper is then a way out only -- it cannot be raised -- and says
 * why. The platform refuses to price a basket with an unavailable line, so a line
 * the guest could not remove would stop the whole table ordering. The decrease on
 * such a portion takes the whole line out ({@link lowered}), because a write of it
 * would be refused.
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
  /** The guest wants to choose the options for a portion of this dish before adding it. */
  readonly choose = output<{ item: MenuItem; variantId: string }>();

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

  /** The groups the guest must choose from before this dish can be ordered. */
  private readonly mandatoryGroups = computed(() => this.item().modifierGroups.filter(isMandatory));

  /** True when the dish cannot be added plain: a group the guest must choose from. */
  protected readonly needsChoice = computed(() => this.mandatoryGroups().length > 0);

  /** The guest can make the choice here: every mandatory group offers enough options to satisfy it. */
  protected readonly choosable = computed(
    () => this.needsChoice() && this.mandatoryGroups().every(canBeSatisfied),
  );

  /** The dish must be chosen from and cannot be: only a member of staff can put it in. */
  protected readonly needsStaff = computed(() => this.needsChoice() && !this.choosable());

  /**
   * The portions the card draws controls for: the ones that can be bought right
   * now, and -- always -- the ones the basket already holds.
   *
   * A held portion stays on the card when it sold out, left its sale window or
   * its dish became one that needs staff, because the platform prices the whole
   * basket and refuses it while any line is unavailable: a stepper that
   * disappeared with the dish's availability would leave the guest holding a line
   * they cannot take out, and so unable to order at all. It can only be lowered
   * ({@link canAdd}).
   */
  protected readonly portions = computed<readonly MenuItemVariant[]>(() =>
    this.item().variants.filter(
      (variant) => this.canAdd(variant) || this.quantityOf(variant.id) > 0,
    ),
  );

  protected readonly showControls = computed(
    () => this.ordering() && !this.linked() && this.portions().length > 0,
  );

  /** The portions the guest can pick options for right now. */
  protected readonly choosePortions = computed<readonly MenuItemVariant[]>(() =>
    this.item().variants.filter((variant) => this.canChoose(variant)),
  );

  protected readonly showChoose = computed(
    () => this.ordering() && !this.linked() && this.choosePortions().length > 0,
  );

  protected readonly showStaffNote = computed(
    () => this.ordering() && !this.linked() && !this.unavailable() && this.needsStaff(),
  );

  protected quantityOf(variantId: string): number {
    return this.quantities()[variantId] ?? 0;
  }

  /** A portion may be put in the basket (or more of it) plain only while nothing has to be chosen for it. */
  protected canAdd(variant: MenuItemVariant): boolean {
    return !this.needsChoice() && variantAvailability(variant) === 'AVAILABLE';
  }

  /** A portion of a dish that must be chosen from can be picked for while it can be bought. */
  protected canChoose(variant: MenuItemVariant): boolean {
    return this.choosable() && variantAvailability(variant) === 'AVAILABLE';
  }

  /**
   * The quantity the decrease button asks for. One fewer -- except on a held portion
   * that can no longer be bought (its dish now needs a choice or staff, it sold out,
   * its sale window closed): the platform checks the selection rules, the stock and
   * the sale window on every write of a line, whatever quantity it writes, so one
   * fewer would be refused every time and the guest could never reach zero. The way
   * out there is the whole line.
   */
  protected lowered(variant: MenuItemVariant): number {
    return this.canAdd(variant) ? this.quantityOf(variant.id) - 1 : 0;
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
