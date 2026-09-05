import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Scaffold. The Milliy design defines this screen; it is not built yet.
 *
 * Deliberately empty rather than sketched: a half-drawn screen reads as a
 * broken one, and this storefront's whole premise is that a tenant can choose
 * it and trust what it shows.
 */
@Component({
  selector: 'app-checkout',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<p style="padding:32px 24px;color:var(--text-muted)">Checkout</p>',
})
export class CheckoutComponent {}
