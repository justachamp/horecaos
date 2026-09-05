import { ChangeDetectionStrategy, Component, type OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AddressBookService, addressLine } from '../../services/address-book.service';
import { DeliverySelectionService } from '../../services/delivery-selection.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { PaymentSessionService } from '../../services/payment-session.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { UiCartService } from '../../services/ui-cart.service';
import { newIdempotencyKey } from '../../core/api/idempotency';
import type { CustomerAddress } from '../../core/api/customer-api';

type LoadState = 'loading' | 'ready' | 'empty' | 'error';

/** A payment method the cart may be charged with, as this screen presents it. */
interface PaymentOption {
  readonly code: string;
  readonly labelKey: string;
  readonly subKey: string;
}

/** The two payment codes this deployment actually knows a label for. Anything
 * else is still selectable -- the design cannot show a method it has never
 * seen -- with its own code standing in for a label. */
const KNOWN_METHODS: Readonly<Record<string, PaymentOption>> = {
  CASH: { code: 'CASH', labelKey: 'cart.cash', subKey: 'cart.cashSecondary' },
  CLICK: { code: 'CLICK', labelKey: 'cart.click', subKey: 'cart.click' },
  PAYME: { code: 'PAYME', labelKey: 'cart.payme', subKey: 'cart.payme' },
};

/**
 * To'lov: address, recipient, payment method, promo code, courier note, and
 * the confirm action that turns a priced cart into an order.
 *
 * <h2>What the design shows that this screen does not build</h2>
 *
 * **The "Yetkazish vaqti" time-slot chips (Hozir / 12:30 / 13:00...).** There
 * is no scheduled-delivery field anywhere on `CheckoutRequest` or the cart --
 * a customer cannot ask for a promised time, so a row of tappable times would
 * control nothing. `UiCartService.deliveryTimeDisplay` is wired into the
 * header for exactly the honest case: it reads `null` until the platform
 * has an actual promise to show (there is none before an order exists --
 * `OrderSummaryResponse.promisedAt` is only set afterward), so nothing renders
 * rather than a fabricated "30 daqiqa".
 *
 * **A map-based "add a new address" flow.** `AddressBookService` only lists
 * and edits what is already saved; creating one needs the geocoding/marker
 * picker this wave does not build. A customer with no saved address sees that
 * stated plainly (`checkout.noSavedAddresses`) rather than a button that goes
 * nowhere.
 *
 * <h2>The money on this screen</h2>
 *
 * Every figure -- subtotal, delivery fee, discount, total -- is read from
 * `UiCartService`'s own computed signals, which are themselves the platform's
 * `POST .../pricing` answer. Applying or removing a promo code never adjusts
 * a number here: it asks the platform to reprice (ADR 0072) and only ever
 * displays what comes back.
 */
@Component({
  selector: 'app-checkout',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './checkout.component.html',
  styleUrl: './checkout.component.scss',
})
export class CheckoutComponent implements OnInit {
  protected readonly cart = inject(UiCartService);
  protected readonly delivery = inject(DeliverySelectionService);
  private readonly addressBook = inject(AddressBookService);
  private readonly paymentSession = inject(PaymentSessionService);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  protected readonly addresses = signal<readonly CustomerAddress[]>([]);
  protected readonly paymentMethods = signal<readonly string[]>([]);
  protected readonly selectedPayment = signal<string | null>(null);
  protected readonly promoInput = signal('');
  protected readonly submitting = signal(false);
  protected readonly submitErrorKey = signal<string | null>(null);

  /** Seeded from `DeliverySelectionService` and pushed back on every edit. */
  protected readonly recipientName = signal('');
  protected readonly recipientPhone = signal('');

  protected readonly isDelivery = computed(() => this.cart.fulfillmentMode() === 'DELIVERY');

  protected readonly canConfirm = computed(() => {
    if (this.submitting() || this.paymentMethods().length === 0 || !this.selectedPayment()) {
      return false;
    }
    if (this.isDelivery()) {
      return (
        !!this.delivery.addressId() &&
        !!this.recipientName().trim() &&
        !!this.recipientPhone().trim()
      );
    }
    return true;
  });

  async ngOnInit(): Promise<void> {
    this.state.set('loading');
    await this.cart.load();
    if (!this.cart.cartData() || this.cart.items().length === 0) {
      this.state.set('empty');
      return;
    }

    await this.delivery.ensureAddressResolved();
    this.recipientName.set(this.delivery.recipientName());
    this.recipientPhone.set(this.delivery.recipientPhone());

    try {
      const [methods] = await Promise.all([
        this.cart.paymentMethods().catch(() => [] as readonly string[]),
        this.isDelivery() ? this.loadAddresses() : Promise.resolve(),
      ]);
      this.paymentMethods.set(methods);
      if (methods.length === 1) {
        this.selectedPayment.set(methods[0]);
      }
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  private async loadAddresses(): Promise<void> {
    try {
      this.addresses.set(await this.addressBook.list());
    } catch {
      this.addresses.set([]);
    }
  }

  protected addressLine(address: CustomerAddress): string {
    return address.label?.trim() || addressLine(address);
  }

  protected chooseAddress(address: CustomerAddress): void {
    this.delivery.choose(address.addressId, address);
  }

  protected onNameInput(value: string): void {
    this.recipientName.set(value);
    this.delivery.setRecipient(value, this.recipientPhone());
  }

  protected onPhoneInput(value: string): void {
    this.recipientPhone.set(value);
    this.delivery.setRecipient(this.recipientName(), value);
  }

  protected paymentOption(code: string): PaymentOption {
    return KNOWN_METHODS[code] ?? { code, labelKey: '', subKey: '' };
  }

  protected selectPayment(code: string): void {
    this.selectedPayment.set(code);
  }

  protected onCommentInput(value: string): void {
    this.cart.orderComment = value;
  }

  protected async applyPromo(): Promise<void> {
    if (await this.cart.applyPromoCode(this.promoInput())) {
      this.promoInput.set('');
    }
  }

  protected async removePromo(): Promise<void> {
    await this.cart.removePromoCode();
  }

  protected back(): void {
    void this.router.navigate(['/cart']);
  }

  /**
   * Turns the priced cart into an order.
   *
   * Every guard below refuses to call `checkout` at all rather than let the
   * platform refuse it: an incomplete delivery destination or a missing
   * payment method are read here so the customer sees why, instead of a
   * generic rejection for a request this screen should never have sent.
   */
  protected async confirm(): Promise<void> {
    if (!this.canConfirm()) {
      return;
    }
    this.submitting.set(true);
    this.submitErrorKey.set(null);
    try {
      const destinationOk = await this.cart.applyDestination();
      if (!destinationOk) {
        this.submitErrorKey.set('cart.addressRequired');
        return;
      }
      const priced = await this.cart.priceCart();
      if (!priced) {
        this.submitErrorKey.set('errors.generic');
        return;
      }
      const paymentMethodCode = this.selectedPayment();
      if (!paymentMethodCode) {
        this.submitErrorKey.set('cart.noPaymentMethodSelected');
        return;
      }

      const result = await this.cart.checkout({
        priced,
        paymentMethodCode,
        idempotencyKey: newIdempotencyKey(),
      });
      if (result.outcome === 'REJECTED') {
        this.submitErrorKey.set('cart.orderRejected');
        return;
      }

      this.cart.discard();

      if (PaymentSessionService.requiresOnlineSession(paymentMethodCode)) {
        const redirected = await this.openPaymentSession(result.orderId);
        if (redirected) {
          // The browser is about to navigate away to the provider's own page.
          return;
        }
        this.submitErrorKey.set('cart.paymentSessionError');
      }
      await this.router.navigate(['/orders']);
    } catch {
      this.submitErrorKey.set('errors.generic');
    } finally {
      this.submitting.set(false);
    }
  }

  /** @returns true once the browser has been sent to the provider's checkout. */
  private async openPaymentSession(orderId: string): Promise<boolean> {
    try {
      const session = await this.paymentSession.open(orderId);
      if (session.checkoutUrl) {
        this.redirectTo(session.checkoutUrl);
        return true;
      }
      return false;
    } catch {
      return false;
    }
  }

  /**
   * The one line that actually leaves this application, isolated so a test
   * can replace it -- a real assignment here sends jsdom off to load another
   * document, which does not fail this call but does leave later tests in the
   * same file running against a browser environment the navigation broke.
   */
  private redirectTo(url: string): void {
    window.location.href = url;
  }
}
