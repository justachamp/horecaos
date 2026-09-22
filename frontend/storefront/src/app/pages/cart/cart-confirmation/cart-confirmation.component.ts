import { newIdempotencyKey } from '../../../core/api/idempotency';
import { Component, OnInit, effect, signal, inject } from '@angular/core';
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
import { HorecaOSApiError, messageKeyFor } from '../../../core/api/problem-details';

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
    // CASH's secondary line describes *how* it is paid, and that genuinely
    // differs by fulfillment mode: a delivery order is paid to the courier
    // on receipt, a pickup order is paid at the branch on collection -- there
    // is no courier to hand cash to. Every other rendered method (CLICK,
    // PAYME) is an online charge with no such distinction.
    const secondaryKey =
      this.pickingUp && opt.id === 'CASH' ? 'cart.cashSecondaryPickup' : opt.secondaryKey;
    const secondary = secondaryKey ? this.translate.get(secondaryKey) : null;
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
  ) {
    // Prefills the recipient from the signed-in account, reactively rather
    // than as a one-time copy in `ngOnInit`. Both sources it reads --
    // `DeliverySelectionService.recipientName` (the profile's display name,
    // loaded asynchronously by the home screen) and `recipientPhone` (the
    // number typed at sign-in, held only in memory) -- can still be empty at
    // the moment this screen mounts and arrive afterwards. A one-time read
    // raced that and silently kept the blank it saw first; this keeps
    // syncing until the customer actually types something of their own,
    // which is what `recipientTouched` remembers.
    effect(() => {
      const name = this.delivery.recipientName();
      const phone = this.delivery.recipientPhone();
      if (!this.recipientTouched) {
        this.recipientName = name;
        this.recipientPhone = phone;
      }
    });

    // Without this, a fresh DELIVERY cart deadlocks: `applyDestination()` is
    // otherwise called only from `submitOrder()`, the order button stays
    // disabled until the fee resolves (`canPlaceOrder`), and a disabled
    // <button> never fires (click) -- so the one call that would resolve the
    // fee could never run. This resolves it as soon as the chosen address and
    // a recipient are both known (an address picked earlier on Home, a
    // recipient prefilled from the account or typed into the fields above),
    // rather than waiting on a click the disabled state itself prevents.
    effect(() => {
      if (this.cart.fulfillmentMode() !== 'DELIVERY') return;
      if (!this.cart.cartData()) return;
      if (this.cart.canPlaceOrder()) return; // already resolved
      if (!this.delivery.isComplete()) return; // nothing to apply yet
      if (this.resolvingDestination) return;
      this.resolvingDestination = true;
      void this.cart.applyDestination().finally(() => {
        this.resolvingDestination = false;
      });
    });
  }

  /** Set once the customer edits either recipient field, so a late-arriving
   * profile load never overwrites what they already typed. */
  private recipientTouched = false;

  /** Guards the auto-apply effect above against overlapping calls while one
   * `applyDestination()` is still in flight. */
  private resolvingDestination = false;

  ngOnInit(): void {
    if (!this.cart.cartData()) {
      void this.cart.load().then(() => this.loadPaymentMethods());
    } else {
      this.loadPaymentMethods();
    }
    // Only the address *id* survives a reload, so a fresh page has a choice it
    // cannot yet name; this reads it back before the screen renders it.
    void this.delivery.ensureAddressResolved();
  }

  get deliveryAddress(): string {
    return this.cart.deliveryAddress() || this.translate.get('cart.addressNotSelected');
  }

  /** Delivery is the only mode with a recipient and an address to ask about. */
  get delivering(): boolean {
    return this.cart.fulfillmentMode() === 'DELIVERY';
  }

  get pickingUp(): boolean {
    return this.cart.fulfillmentMode() === 'PICKUP';
  }

  /** Why the delivery fee is not final yet, or `null` when it is (or this is
   * not a delivery cart). See `UiCartService.deliveryUnresolvedMessage`. */
  get deliveryUnresolvedMessage(): string | null {
    return this.cart.deliveryUnresolvedMessage();
  }

  /** False only for a `DELIVERY` cart whose fee has not resolved -- the order
   * button stays disabled until it does, per `CheckoutEligibilityGuard`. */
  get canPlaceOrder(): boolean {
    return this.cart.canPlaceOrder();
  }

  /**
   * Who receives this delivery.
   *
   * Bound to plain fields, kept in step with {@link DeliverySelectionService}
   * by the constructor's `effect` rather than read once in `ngOnInit`, and
   * editable from here so the customer may change either one. The phone in
   * particular is never persisted (it is ADR 0029 personal data and `GET /me`
   * will not return it) -- only the number typed at sign-in, held in memory
   * for this session, so a page that was actually reloaded still has nothing
   * to prefill from and the customer types it once. `PUT /carts/{id}/destination`
   * requires both fields -- which is how a chosen address used to still end in
   * "address required" at checkout.
   */
  recipientName = '';
  recipientPhone = '';

  onRecipientChange(): void {
    // From here on the customer's own typing wins; the prefill effect above
    // stops overwriting these two fields.
    this.recipientTouched = true;
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

  /** `null` hides the row: no tax to show yet, or the platform reported none. */
  get taxAmount(): string | null {
    return this.cart.taxFormatted();
  }

  /** `null` hides the row: nothing was discounted. */
  get discountAmount(): string | null {
    return this.cart.discountFormatted();
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
        // No order exists to retry against, and the very next submitOrder()
        // re-runs applyDestination() and priceCart() regardless -- a retry
        // under this same key would present a different body and the
        // platform's idempotency store would refuse it as
        // IDEMPOTENCY_KEY_REUSED forever, on a basket the customer may still
        // legitimately want to order. A fresh key is what lets that retry
        // through.
        this.pendingCheckoutKey = null;
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
    } catch (failure) {
      // A platform refusal (DELIVERY_FEE_UNRESOLVED, NOT_SERVICEABLE, a stale
      // quote, ...) is named specifically here, the same vocabulary
      // `messageKeyFor` already gives the toast -- so the inline text under
      // the button agrees with it instead of falling back to one generic
      // sentence for every reason checkout could have said no.
      const key = failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'cart.orderError';
      this.orderError.set(this.translate.get(key));
      // A definite refusal (a stale quote, a moved cart version, an expired
      // cart, ...) means no order was created here either, and the retry's
      // own applyDestination()/priceCart() calls will send a different body
      // regardless -- so this key must rotate for the same reason REJECTED's
      // does, above. The one exception is a failure that never reached the
      // platform at all: `NETWORK_UNREACHABLE` is ApiClient's own
      // normalisation of a dropped connection or a client-side timeout with
      // no HTTP response (see toHorecaOSApiError). There the original
      // request may in fact have been received, and presenting the *same*
      // key on an *unchanged* retry is what lets a genuine replay answer
      // REPLAYED with the order that already exists, instead of a second
      // one -- rotating here would only turn one silent success into a
      // second, unwanted order.
      if (!(failure instanceof HorecaOSApiError) || failure.code !== 'NETWORK_UNREACHABLE') {
        this.pendingCheckoutKey = null;
      }
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
