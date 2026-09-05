import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component, signal } from '@angular/core';

import { CheckoutComponent } from './checkout.component';
import { UiCartService } from '../../services/ui-cart.service';
import { DeliverySelectionService } from '../../services/delivery-selection.service';
import { AddressBookService } from '../../services/address-book.service';
import { PaymentSessionService } from '../../services/payment-session.service';
import { TranslateService } from '../../services/translate.service';
import type { PricedCart, CheckoutResult, FulfillmentMode } from '../../services/cart.service';
import type { CartResponse } from '../../types/cart.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

/** A stand-in for whatever real route the router lands on, so `navigate()`
 * resolves normally instead of rejecting with NG04002 against an empty route
 * table -- a rejection that would otherwise land in this component's own
 * catch-all and mask the very outcome a test is checking for. */
@Component({ selector: 'app-test-target', template: '' })
class TestTargetComponent {}

function priced(overrides: Partial<PricedCart> = {}): PricedCart {
  return {
    cartId: 'cart-1',
    cartVersion: 1,
    quoteId: 'quote-1',
    contextHash: 'hash-1',
    currency: 'UZS',
    subtotalMinor: 25_000,
    taxMinor: 0,
    totalMinor: 25_000,
    discountMinor: 0,
    expiresAt: new Date().toISOString(),
    ...overrides,
  };
}

function nonEmptyCartData(): CartResponse {
  return {
    subtotal: { price: 25_000, discount: 0 },
    delivery: { price: 0, discount: 0 },
    packaging: { price: 0, discount: 0 },
    total: { price: 25_000, discount: 0 },
    items: [
      {
        variant_id: 'v1',
        item_id: 'v1',
        name: 'Osh',
        active: true,
        image: null,
        price: 25_000,
        quantity: 1,
        note: null,
        modifierOptionIds: [],
        modifiers: [],
      },
    ],
    vendor: { id: '', name: '', phone: '', active: true, pre_order: false, start: '', finish: '' },
    address: null,
    items_count: 1,
    delivery_time: null,
    delivery_distance: 0,
    delivery_date_display: null,
    delivery_time_display: null,
    promo_code: null,
    delivery_duration: 0,
  };
}

class FakeUiCartService {
  readonly cartData = signal<CartResponse | null>(nonEmptyCartData());
  readonly fulfillmentMode = signal<FulfillmentMode>('DELIVERY');
  readonly promoBusy = signal(false);
  readonly promoError = signal<string | null>(null);
  orderComment = '';

  items = () => this.cartData()?.items ?? [];
  subtotalFormatted = () => '25 000 so\'m';
  deliveryFee = () => '10 000 so\'m';
  totalAmount = () => '35 000 so\'m';
  hasDiscount = () => false;
  discountFormatted = () => '0 so\'m';
  appliedPromoCode = (): string | null => null;
  deliveryTimeDisplay = (): string | null => null;

  load = vi.fn(async () => {});
  paymentMethods = vi.fn(async (): Promise<readonly string[]> => ['CASH']);
  applyPromoCode = vi.fn(async () => true);
  removePromoCode = vi.fn(async () => {});
  applyDestination = vi.fn(async () => true);
  priceCart = vi.fn(async () => priced());
  checkout = vi.fn(async (): Promise<CheckoutResult> => ({
    orderId: 'order-1',
    publicOrderNumber: 'PN-1',
    status: 'CONFIRMED',
    version: 1,
    outcome: 'CREATED',
    warnings: [],
  }));
  discard = vi.fn();
}

class FakeDeliverySelectionService {
  readonly addressId = signal<string | null>('addr-1');
  addressLabel = () => 'Chilonzor, 12-uy';
  recipientName = () => 'Aziz';
  recipientPhone = () => '+998901234567';
  isComplete = () => true;
  ensureAddressResolved = vi.fn(async () => {});
  choose = vi.fn();
  setRecipient = vi.fn();
}

class FakeAddressBookService {
  list = vi.fn(async () => []);
}

class FakePaymentSessionService {
  open = vi.fn(async () => ({
    attemptId: 'a1',
    merchantTransId: 'm1',
    provider: 'CLICK',
    presentation: 'PAYMENT_LINK',
    checkoutUrl: 'https://pay.example/checkout',
    qrPayload: null,
    expiresAt: new Date().toISOString(),
    amountMinor: 25_000,
    currency: 'UZS',
    rePresented: false,
    presentationCount: 1,
  }));
}

interface Fakes {
  fixture: ReturnType<typeof TestBed.createComponent<CheckoutComponent>>;
  cart: FakeUiCartService;
  delivery: FakeDeliverySelectionService;
  addressBook: FakeAddressBookService;
  paymentSession: FakePaymentSessionService;
  router: Router;
}

async function setUp(
  configure: (cart: FakeUiCartService, delivery: FakeDeliverySelectionService) => void = () => {},
): Promise<Fakes> {
  const cart = new FakeUiCartService();
  const delivery = new FakeDeliverySelectionService();
  const addressBook = new FakeAddressBookService();
  const paymentSession = new FakePaymentSessionService();
  configure(cart, delivery);

  TestBed.configureTestingModule({
    imports: [CheckoutComponent],
    providers: [
      provideRouter([
        { path: 'cart', component: TestTargetComponent },
        { path: 'orders', component: TestTargetComponent },
      ]),
      { provide: UiCartService, useValue: cart },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: AddressBookService, useValue: addressBook },
      { provide: PaymentSessionService, useValue: paymentSession },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const fixture = TestBed.createComponent(CheckoutComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, cart, delivery, addressBook, paymentSession, router: TestBed.inject(Router) };
}

describe('CheckoutComponent -- denied/empty states', () => {
  it('shows the empty-cart state and never renders a confirm button over nothing to check out', async () => {
    const { fixture } = await setUp((cart) => cart.cartData.set(null));

    expect(fixture.nativeElement.textContent).toContain('checkout.emptyCartTitle');
    expect(fixture.nativeElement.querySelector('.cta')).toBeNull();
  });
});

describe('CheckoutComponent.confirm -- guards that must hold before the platform is ever asked', () => {
  it('does not call checkout while the delivery destination is incomplete', async () => {
    const { fixture, cart } = await setUp((_cart, delivery) => delivery.addressId.set(null));

    const button = fixture.nativeElement.querySelector('.cta') as HTMLButtonElement;
    expect(button.disabled).toBe(true);

    button.click();
    await fixture.whenStable();

    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('does not call checkout when there is no payment method to select', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => []);
    });

    const button = fixture.nativeElement.querySelector('.cta') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    button.click();
    await fixture.whenStable();

    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('sends exactly the priced quote, the selected method and one idempotency key -- and discards the cart on success', async () => {
    const { fixture, cart, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');
    const quote = priced({ quoteId: 'quote-xyz' });
    cart.priceCart.mockResolvedValue(quote);

    const button = fixture.nativeElement.querySelector('.cta') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    button.click();
    await fixture.whenStable();

    expect(cart.applyDestination).toHaveBeenCalled();
    expect(cart.checkout).toHaveBeenCalledWith({
      priced: quote,
      paymentMethodCode: 'CASH',
      idempotencyKey: expect.any(String),
    });
    expect(cart.discard).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/orders']);
  });

  it('a REJECTED outcome surfaces the translated refusal key, never the raw outcome string, and never discards the cart', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.checkout = vi.fn(async () => ({
        orderId: 'order-1',
        publicOrderNumber: 'PN-1',
        status: 'REJECTED',
        version: 1,
        outcome: 'REJECTED' as const,
        warnings: [],
      }));
    });

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('cart.orderRejected');
    expect(fixture.nativeElement.textContent).not.toContain('REJECTED');
    expect(cart.discard).not.toHaveBeenCalled();
  });

  it('an online method (CLICK) opens a payment session and sends the browser to its checkout URL', async () => {
    const { fixture, cart, paymentSession, router } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['CLICK']);
    });
    // `redirectTo` is the one line that would actually leave this page; a real
    // assignment to `window.location.href` sends jsdom off to load another
    // document, which corrupts the environment for every test that runs after
    // it in this file. Spying on the seam instead of the global keeps this
    // test honest about what fired without paying that cost.
    const redirectSpy = vi
      .spyOn(fixture.componentInstance as unknown as { redirectTo(url: string): void }, 'redirectTo')
      .mockImplementation(() => {});
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(paymentSession.open).toHaveBeenCalledWith('order-1');
    expect(redirectSpy).toHaveBeenCalledWith('https://pay.example/checkout');
    // The provider checkout took over the browser -- this screen never sends
    // the customer straight to the order list the way a CASH order does.
    expect(navigateSpy).not.toHaveBeenCalledWith(['/orders']);
    expect(cart.discard).toHaveBeenCalled();
  });

  it('still lands the customer on /orders, with a translated notice, when the payment session cannot be opened', async () => {
    const { fixture, paymentSession, router } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['CLICK']);
    });
    paymentSession.open.mockRejectedValue(new Error('offline'));
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('cart.paymentSessionError');
    expect(navigateSpy).toHaveBeenCalledWith(['/orders']);
  });
});

describe('CheckoutComponent -- promo code (ADR 0072)', () => {
  it('never calls applyPromoCode for a blank field', async () => {
    const { fixture, cart } = await setUp();

    const applyButton = fixture.nativeElement.querySelector('.btn--compact') as HTMLButtonElement;
    expect(applyButton.disabled).toBe(true);

    applyButton.click();
    expect(cart.applyPromoCode).not.toHaveBeenCalled();
  });

  it('sends exactly what the customer typed, and clears the field once the platform accepts it', async () => {
    const { fixture, cart } = await setUp();

    const input = fixture.nativeElement.querySelector('.promo-form .field') as HTMLInputElement;
    input.value = 'OSH2026';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const applyButton = fixture.nativeElement.querySelector('.btn--compact') as HTMLButtonElement;
    expect(applyButton.disabled).toBe(false);
    applyButton.click();
    await fixture.whenStable();

    expect(cart.applyPromoCode).toHaveBeenCalledWith('OSH2026');
  });

  it('surfaces a refusal as the message UiCartService already translated, never a raw code', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.applyPromoCode = vi.fn(async () => {
        cart.promoError.set('checkout.promoExpired');
        return false;
      });
    });

    const input = fixture.nativeElement.querySelector('.promo-form .field') as HTMLInputElement;
    input.value = 'OLDCODE';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn--compact') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('checkout.promoExpired');
  });

  it('removing an applied code calls removePromoCode', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.appliedPromoCode = () => 'OSH2026';
    });

    (fixture.nativeElement.querySelector('.pill-btn') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(cart.removePromoCode).toHaveBeenCalled();
  });
});
