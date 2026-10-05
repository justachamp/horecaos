import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { UiCartService } from '../../services/ui-cart.service';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The cart's promo-code entry (ADR 0072, ADR 0140).
 *
 * A customer types a code, the platform records it on the cart, and the cart is priced again:
 * what the code is worth is the platform's own figure, never this component's. When the platform
 * says the code did not move the price -- the offers that already apply are better, the basket
 * does not qualify, the code stopped being valid -- the component says so, because the code stays
 * on the cart whatever the outcome and a customer who typed one and sees no saving would
 * otherwise be left guessing.
 *
 * Presentation only: every state it shows is `UiCartService`'s.
 */
@Component({
  selector: 'app-promo-code',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './promo-code.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromoCodeComponent {
  protected readonly cart = inject(UiCartService);

  /** What has been typed and not yet applied. */
  protected readonly code = signal('');

  /** Applies the typed code; a refusal keeps what was typed so a typo can be fixed, not retyped. */
  protected async apply(): Promise<void> {
    if (!this.code().trim() || this.cart.promoBusy()) {
      return;
    }
    if (await this.cart.applyPromoCode(this.code())) {
      this.code.set('');
    }
  }

  protected remove(): void {
    void this.cart.removePromoCode();
  }
}
