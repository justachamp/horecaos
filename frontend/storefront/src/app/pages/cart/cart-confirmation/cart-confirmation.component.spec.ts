import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { CartConfirmationComponent } from './cart-confirmation.component';
import { UiCartService } from '../../../services/ui-cart.service';
import { OrdersService } from '../../../services/orders.service';
import { PaymentSessionService } from '../../../services/payment-session.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import type { CheckoutResult, PricedCart } from '../../../services/cart.service';
import type { CartResponse } from '../../../types/cart.types';

class FakeUiCartService {
  cartData = vi.fn<() => CartResponse | null>(() => cartDataFixture());
  load = vi.fn();
  paymentMethods = vi.fn();
  applyDestination = vi.fn();
  priceCart = vi.fn();
  checkout = vi.fn();
  discard = vi.fn();
  deliveryAddress = vi.fn(() => '');
  subtotalFormatted = vi.fn(() => '10 000 so\'m');
  deliveryFee = vi.fn(() => '5 000 so\'m');
  totalWithDelivery = vi.fn(() => '15 000 so\'m');
}

class FakePaymentSessionService {
  open = vi.fn();
}

class FakeNotificationService {
  show = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function cartDataFixture(): CartResponse {
  return {
    subtotal: { price: 10_000, discount: 0 },
    delivery: { price: 5_000, discount: 0 },
    packaging: { price: 0, discount: 0 },
    total: { price: 15_000, discount: 0 },
    items: [],
    vendor: { id: '', name: '', phone: '', active: true, pre_order: false, start: '', finish: '' },
    address: null,
    items_count: 0,
    delivery_time: null,
    delivery_distance: 0,
    delivery_date_display: null,
    delivery_time_display: null,
    promo_code: null,
    delivery_duration: 0,
  };
}

function pricedFixture(): PricedCart {
  return {
    cartId: 'cart-1',
    cartVersion: 3,
    quoteId: 'quote-1',
    contextHash: 'hash-1',
    currency: 'UZS',
    subtotalMinor: 10_000,
    taxMinor: 0,
    totalMinor: 15_000,
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  };
}

function checkoutResult(overrides: Partial<CheckoutResult> = {}): CheckoutResult {
  return {
    orderId: 'order-1',
    publicOrderNumber: 'PN-1',
    status: 'CONFIRMED',
    version: 1,
    outcome: 'CREATED',
    warnings: [],
    ...overrides,
  };
}

async function setUp(paymentCodes: readonly string[] = ['CASH']) {
  const cart = new FakeUiCartService();
  cart.paymentMethods.mockResolvedValue(paymentCodes);
  const paymentSessions = new FakePaymentSessionService();
  const notification = new FakeNotificationService();

  TestBed.configureTestingModule({
    imports: [CartConfirmationComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: cart },
      { provide: OrdersService, useValue: {} },
      { provide: PaymentSessionService, useValue: paymentSessions },
      { provide: NotificationService, useValue: notification },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(CartConfirmationComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  // `ngOnInit` fires `loadPaymentMethods()` without awaiting it, and that
  // method chains `.then().catch().finally()` -- three microtask hops after
  // the mocked promise settles. `whenStable()` does not reliably drain a
  // chain that deep, so a macrotask flush (which always runs after every
  // pending microtask) is what actually guarantees `paymentMethodsLoaded`
  // has been set before a test reads it.
  await new Promise((resolve) => setTimeout(resolve, 0));

  return { fixture, comp: fixture.componentInstance, cart, paymentSessions, notification, navigateSpy };
}

describe('CartConfirmationComponent: no payment methods blocks submit', () => {
  it('blocks submitOrder and reports a selection error when the platform offers nothing renderable', async () => {
    const { comp, cart } = await setUp([]);

    expect(comp.noPaymentMethods()).toBe(true);
    expect(comp.selectedPaymentId).toBe('');

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.noPaymentMethodSelected');
    expect(cart.applyDestination).not.toHaveBeenCalled();
    expect(cart.priceCart).not.toHaveBeenCalled();
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('is not blocked once at least one renderable method is offered', async () => {
    const { comp } = await setUp(['CASH']);

    expect(comp.noPaymentMethods()).toBe(false);
    expect(comp.selectedPaymentId).toBe('CASH');
  });

  it('drops a method code the platform offers that this build cannot render', async () => {
    const { comp } = await setUp(['CASH', 'MARKETPLACE']);

    expect(comp.paymentOptions().map((o) => o.id)).toEqual(['CASH']);
  });
});

describe('CartConfirmationComponent.submitOrder sequencing (destination -> price -> checkout)', () => {
  it('calls applyDestination, then priceCart, then checkout, in that order', async () => {
    const { comp, cart, navigateSpy } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult());

    await comp.submitOrder();

    const destOrder = cart.applyDestination.mock.invocationCallOrder[0];
    const priceOrder = cart.priceCart.mock.invocationCallOrder[0];
    const checkoutOrder = cart.checkout.mock.invocationCallOrder[0];
    expect(destOrder).toBeLessThan(priceOrder);
    expect(priceOrder).toBeLessThan(checkoutOrder);

    // CASH needs no online session -- straight to the active orders list.
    expect(cart.discard).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/orders', 'active']);
  });

  it('stops before pricing when the destination could not be applied (a delivery cart with no address yet)', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(false);

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.addressRequired');
    expect(cart.priceCart).not.toHaveBeenCalled();
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('stops before checkout when pricing fails', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(null);

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.orderError');
    expect(cart.checkout).not.toHaveBeenCalled();
  });
});

describe('CartConfirmationComponent: REJECTED vs a thrown error', () => {
  it('a REJECTED outcome is reported distinctly, and does not discard the basket or navigate', async () => {
    const { comp, cart, navigateSpy } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult({ outcome: 'REJECTED' }));

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.orderRejected');
    expect(cart.discard).not.toHaveBeenCalled();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('a thrown error (network failure, etc.) is reported as a different key than REJECTED', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockRejectedValue(new Error('network exploded'));

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.orderError');
    expect(comp.orderError()).not.toBe('cart.orderRejected');
  });

  it('CREATED and REPLAYED are both treated as success, not just a literal ACCEPTED', async () => {
    const { comp, cart, navigateSpy } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult({ outcome: 'REPLAYED' }));

    await comp.submitOrder();

    expect(comp.orderError()).toBeNull();
    expect(cart.discard).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/orders', 'active']);
  });
});

describe('CartConfirmationComponent: idempotency key stability across repeated clicks', () => {
  it('reuses the same idempotency key across two failed attempts at the same basket', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockRejectedValue(new Error('timed out'));

    await comp.submitOrder();
    await comp.submitOrder();

    expect(cart.checkout).toHaveBeenCalledTimes(2);
    const key1 = cart.checkout.mock.calls[0][0].idempotencyKey;
    const key2 = cart.checkout.mock.calls[1][0].idempotencyKey;
    expect(key1).toBeTruthy();
    expect(key1).toBe(key2);
  });

  it('mints a fresh key for the next order after a successful checkout resets it', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValueOnce(checkoutResult());

    await comp.submitOrder();
    const firstKey = cart.checkout.mock.calls[0][0].idempotencyKey;

    cart.checkout.mockResolvedValueOnce(checkoutResult({ orderId: 'order-2' }));
    await comp.submitOrder();
    const secondKey = cart.checkout.mock.calls[1][0].idempotencyKey;

    expect(secondKey).not.toBe(firstKey);
  });
});

describe('CartConfirmationComponent: CLICK opens a payment session and redirects', () => {
  const realLocation = window.location;

  beforeEach(() => {
    // `window.location.href = ...` performs a real navigation attempt in
    // jsdom; replacing the object lets the assignment be observed instead.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...realLocation, href: realLocation.href },
      writable: true,
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: realLocation, writable: true });
  });

  it('opens the payment session and sends the browser to checkoutUrl, without an Angular navigation', async () => {
    const { comp, cart, paymentSessions, navigateSpy } = await setUp(['CLICK']);
    expect(comp.selectedPaymentId).toBe('CLICK');
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult({ orderId: 'order-click' }));
    paymentSessions.open.mockResolvedValue({
      attemptId: 'a1',
      merchantTransId: 'm1',
      provider: 'CLICK',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: 'https://click.example/pay/order-click',
      qrPayload: null,
      expiresAt: new Date().toISOString(),
      amountMinor: 15_000,
      currency: 'UZS',
      rePresented: false,
      presentationCount: 1,
    });

    await comp.submitOrder();

    expect(cart.discard).toHaveBeenCalled();
    expect(paymentSessions.open).toHaveBeenCalledWith('order-click');
    expect(window.location.href).toBe('https://click.example/pay/order-click');
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('falls back to the order-status screen when the payment session cannot be opened', async () => {
    const { comp, cart, paymentSessions, notification, navigateSpy } = await setUp(['CLICK']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult({ orderId: 'order-click-2' }));
    paymentSessions.open.mockRejectedValue(new Error('provider unreachable'));

    await comp.submitOrder();

    expect(notification.show).toHaveBeenCalledWith('cart.paymentSessionError');
    expect(navigateSpy).toHaveBeenCalledWith(['/cart', 'order-status', 'order-click-2']);
  });

  it('CASH never opens a payment session', async () => {
    const { comp, cart, paymentSessions, navigateSpy } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult());

    await comp.submitOrder();

    expect(paymentSessions.open).not.toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/orders', 'active']);
  });
});
