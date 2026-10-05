import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import { formatQuantity } from '../../utils/physical';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * «Add your free X» (ADR 0140): the free gifts a promotion offers that the basket does not hold.
 *
 * An offer, never a line. Pricing adds nothing to the basket and discounts nothing for a gift the
 * customer has not taken; one tap here puts the gift in through the ordinary cart write and the
 * platform prices it free on the next price. When a rule gives a choice of gifts, the customer
 * picks one -- any of them fills the same allowance.
 *
 * Presentation only: what is on offer, its name and how many are still to add all come from
 * `UiCartService.giftOffers`. A refusal of the add lands in the screen's own cart error line.
 */
@Component({
  selector: 'app-gift-offers',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './gift-offers.component.html',
  styleUrl: './gift-offers.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GiftOffersComponent {
  protected readonly cart = inject(UiCartService);
  private readonly lang = inject(LangService);

  /** How many are to be added, as the customer's language writes a quantity (a half portion is `0,5`). */
  protected quantityText(toAdd: number): string {
    return formatQuantity(toAdd, this.lang.langId());
  }

  protected add(variantId: string): void {
    void this.cart.addGift(variantId);
  }
}
