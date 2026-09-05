import { newIdempotencyKey } from '../../../core/api/idempotency';
import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { UiCartService } from '../../../services/ui-cart.service';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { OrdersService } from '../../../services/orders.service';
import { PaymentSessionService } from '../../../services/payment-session.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { TranslateService } from '../../../services/translate.service';

export interface PaymentOption {
  id: string;
  labelKey: string;
  secondaryKey?: string;
  icon: 'cash' | 'card' | 'click' | 'payme';
}

@Component({
  selector: 'app-cart-confirmation',
  templateUrl: './cart-confirmation.component.html',
  styleUrl: './cart-confirmation.component.scss',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe]
})

export class CartConfirmationComponent implements OnInit {
  showPaymentOptions = false;
  /**
   * Empty until the platform says what is available.
   *
   * Not defaulted to CASH. A default the platform did not offer is a choice
   * pre-made on the customer's behalf that fails at checkout, and this brand
   * may well not take cash on this channel.
   */
  selectedPaymentId = '';

  readonly submitting = signal(false);
  readonly orderError = signal<string | null>(null);

  /** True once the platform has answered what this cart may be paid with. */
  readonly paymentMethodsLoaded = signal(false);

  /**
   * Every method this build can render, keyed by the platform's own code.
   *
   * Which of these a customer actually sees is decided by
   * `GET /carts/{id}/payment-methods`, not by this list. The platform returns
   * only methods that would genuinely work -- the channel offers them, this
   * build implements them, a customer may choose them, and a merchant account
   * resolves for this branch today. A method whose provider binding is
   * suspended is absent rather than listed and refused, because an unusable
   * method offered to a customer is a checkout that fails at its last step.
   */
  private readonly renderable: Readonly<Record<string, PaymentOption>> = {
    CASH: { id: 'CASH', labelKey: 'cart.cash', secondaryKey: 'cart.cashSecondary', icon: 'cash' },
    CLICK: { id: 'CLICK', labelKey: 'cart.click', icon: 'click' },
    PAYME: { id: 'PAYME', labelKey: 'cart.payme', icon: 'payme' },
  };

  /**
   * What this cart may be paid with, as the platform resolves it.
   *
   * Empty until the cart has been read. A code the platform offers that this
   * build has no wording for is dropped rather than rendered as its raw code:
   * "MARKETPLACE" is a database value, not a thing to show a customer.
   */
  readonly paymentOptions = signal<PaymentOption[]>([]);

  /**
   * True once the platform has answered and offered nothing this build can
   * render. Checkout requires a payment method (ADR 0031's `CheckoutRequest`
   * makes it a required field), so this state blocks the button rather than
   * ever sending `undefined` and letting the platform's 400 be the first the
   * customer hears of it.
   */
  readonly noPaymentMethods = () => this.paymentMethodsLoaded() && this.paymentOptions().length === 0;

  get paymentMethod(): string {
    const opt = this.paymentOptions().find((o) => o.id === this.selectedPaymentId);
    if (!opt) return '';
    const label = this.translate.get(opt.labelKey);
    const secondary = opt.secondaryKey ? this.translate.get(opt.secondaryKey) : null;
    return secondary ? `${label} / ${secondary}` : label;
  }

  private readonly translate = inject(TranslateService);
  private readonly paymentSessions = inject(PaymentSessionService);
  private readonly notification = inject(NotificationService);

  /**
   * Asks the platform what this cart may be paid with, and keeps only the
   * methods this build can actually render.
   *
   * A failure leaves the list empty rather than falling back to a guess: an
   * offered method that the platform would refuse is worse than no offer, since
   * the customer only discovers it after pressing the button.
   */
  private loadPaymentMethods(): void {
    this.cart
      .paymentMethods()
      .then((codes) => {
        const options = codes
          .map((code) => this.renderable[code])
          .filter((option): option is PaymentOption => option !== undefined);
        this.paymentOptions.set(options);
        // Keep the customer's choice if it survived; otherwise take the first
        // the platform offers rather than leaving nothing selected.
        if (!options.some((option) => option.id === this.selectedPaymentId)) {
          this.selectedPaymentId = options[0]?.id ?? '';
        }
      })
      .catch(() => this.paymentOptions.set([]))
      .finally(() => this.paymentMethodsLoaded.set(true));
  }

  private readonly delivery = inject(DeliverySelectionService);

  constructor(
    public cart: UiCartService,
    private ordersService: OrdersService,
    private router: Router
  ) {}

  ngOnInit(): void {
    if (!this.cart.cartData()) {
      void this.cart.load().then(() => this.loadPaymentMethods());
    } else {
      this.loadPaymentMethods();
    }
    // Only the address *id* survives a reload, so a fresh page has a choice it
    // cannot yet name; this reads it back before the screen renders it.
    void this.delivery.ensureAddressResolved();
    this.recipientName = this.delivery.recipientName();
    this.recipientPhone = this.delivery.recipientPhone();
  }

  get deliveryAddress(): string {
    return this.cart.deliveryAddress() || this.translate.get('cart.addressNotSelected');
  }

  /** Delivery is the only mode with a recipient to ask about. */
  get delivering(): boolean {
    return this.cart.fulfillmentMode() === 'DELIVERY';
  }

  /**
   * Who receives this delivery.
   *
   * Bound to plain fields rather than read straight from
   * {@link DeliverySelectionService} because the phone is deliberately never
   * persisted (it is ADR 0029 personal data and `GET /me` will not return it),
   * so on any page that was reloaded there is nothing to prefill from and the
   * customer types it once. Before this wave the screen offered nowhere to type
   * it, and `PUT /carts/{id}/destination` requires it -- which is how a chosen
   * address still ended in "address required" at checkout.
   */
  recipientName = '';
  recipientPhone = '';

  onRecipientChange(): void {
    this.delivery.setRecipient(this.recipientName, this.recipientPhone);
  }

  togglePaymentOptions(): void {
    this.showPaymentOptions = !this.showPaymentOptions;
  }

  closePaymentOptions(): void {
    this.showPaymentOptions = false;
  }

  selectPayment(id: string): void {
    this.selectedPaymentId = id;
  }

  get orderSubtotal(): string {
    return this.cart.subtotalFormatted();
  }

  get deliveryFee(): string {
    return this.cart.deliveryFee();
  }

  get totalWithDelivery(): string {
    return this.cart.totalWithDelivery();
  }

  /**
   * Places the order: price, then check out against that exact quote.
   *
   * Two calls where the legacy backend had one, and the split is the whole
   * safety property. `POST /pricing` returns a quote bound to this cart at this
   * version, with a context hash covering every input the total depends on.
   * Checkout accepts only that quote for that cart, so a client cannot present
   * a price computed for a different, cheaper basket, and a cart edited in
   * another tab between the two calls is refused rather than charged.
   *
   * The idempotency key is formed here, once, before the request, and reused if
   * the customer presses again after a timeout. Generating a fresh one on retry
   * is exactly how one press becomes two orders.
   *
   * A rejection is not an error to swallow: the platform answers REJECTED with
   * a reason -- a branch that closed, an item that sold out, a quote that
   * expired -- and the customer is told to look at their basket rather than to
   * try again into the same refusal.
   *
   * A CLICK or PAYME order does not land on the active-orders list directly:
   * checkout only creates the order, and a second call
   * (`PaymentSessionService.open`) opens the one payable attempt against it and
   * hands back where to send the browser. The order already exists by the time
   * that second call is made, so a failure there is reported and the customer
   * is sent to look at the order rather than told the order itself failed.
   */
  async submitOrder(): Promise<void> {
    if (this.submitting() || !this.cart.cartData()) return;
    if (!this.selectedPaymentId) {
      this.orderError.set(this.translate.get('cart.noPaymentMethodSelected'));
      return;
    }
    this.orderError.set(null);
    this.submitting.set(true);
    try {
      // Where it is going, before it is priced: setting a destination clears the
      // quote and bumps the version, so pricing first would throw away the very
      // quote checkout is about to spend. ADR 0037 prices delivery from the
      // destination, which is why the two are ordered this way and not the
      // other.
      this.delivery.setRecipient(this.recipientName, this.recipientPhone);
      if (!(await this.cart.applyDestination())) {
        // Name the missing half. "Address required" over a chosen address sends
        // the customer back to re-pick something that was never the problem.
        this.orderError.set(
          this.translate.get(
            this.delivery.addressId() ? 'cart.recipientRequired' : 'cart.addressRequired',
          ),
        );
        return;
      }
      const priced = await this.cart.priceCart();
      if (!priced) {
        this.orderError.set(this.translate.get('cart.orderError'));
        return;
      }
      const paymentMethodCode = this.selectedPaymentId;
      const result = await this.cart.checkout({
        priced,
        paymentMethodCode,
        idempotencyKey: this.checkoutKey(),
      });
      if (result.outcome === 'REJECTED') {
        this.orderError.set(this.translate.get('cart.orderRejected'));
        return;
      }
      // The basket became an order. Forgetting the cart id is what stops the
      // next visit reopening a cart that has been checked out, and it happens
      // whatever comes next -- an online-payment handoff failure below leaves
      // the order in place, not the basket.
      this.cart.discard();
      this.pendingCheckoutKey = null;

      if (PaymentSessionService.requiresOnlineSession(paymentMethodCode)) {
        try {
          const session = await this.paymentSessions.open(result.orderId);
          if (session.checkoutUrl) {
            // A different host's checkout page, not an Angular route -- a full
            // navigation, and nothing after this line runs.
            window.location.href = session.checkoutUrl;
            return;
          }
        } catch {
          this.notification.show(this.translate.get('cart.paymentSessionError'));
        }
        this.router.navigate(['/cart', 'order-status', result.orderId]).catch(() => {});
        return;
      }
      this.router.navigate(['/orders', 'active']).catch(() => {});
    } catch {
      this.orderError.set(this.translate.get('cart.orderError'));
    } finally {
      this.submitting.set(false);
    }
  }

  /**
   * One key per attempt at this basket, held across retries.
   *
   * Reset when the basket becomes an order, so the next order is a new intent.
   */
  private checkoutKey(): string {
    this.pendingCheckoutKey ??= newIdempotencyKey();
    return this.pendingCheckoutKey;
  }

  private pendingCheckoutKey: string | null = null;
}
