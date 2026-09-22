import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { CartConfirmationComponent } from './cart-confirmation.component';
import { UiCartService } from '../../../services/ui-cart.service';
import { OrdersService } from '../../../services/orders.service';
import { PaymentSessionService } from '../../../services/payment-session.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { LocationProfileService, type LocationProfile } from '../../../services/location-profile.service';
import type { CheckoutResult, PricedCart } from '../../../services/cart.service';
import type { CartResponse } from '../../../types/cart.types';
import { HorecaOSApiError } from '../../../core/api/problem-details';
import { APP_CONFIG, type AppConfig } from '../../../core/config/app-config';

class FakeUiCartService {
  fulfillmentMode = vi.fn(() => 'DELIVERY' as const);
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
  taxFormatted = vi.fn<() => string | null>(() => null);
  discountFormatted = vi.fn<() => string | null>(() => null);
  totalWithDelivery = vi.fn(() => '15 000 so\'m');
  deliveryUnresolvedMessage = vi.fn<() => string | null>(() => null);
  canPlaceOrder = vi.fn(() => true);
}

class FakeDeliverySelectionService {
  addressId = vi.fn<() => string | null>(() => 'address-1');
  recipientName = vi.fn(() => '');
  recipientPhone = vi.fn(() => '');
  setRecipient = vi.fn();
  ensureAddressResolved = vi.fn().mockResolvedValue(undefined);
  isComplete = vi.fn(() => false);
}

class FakePaymentSessionService {
  open = vi.fn();
}

class FakeNotificationService {
  show = vi.fn();
}

class FakeLocationProfileService {
  profile = vi.fn<() => Promise<LocationProfile | null>>(() => Promise.resolve(null));
}

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  defaultLocationId: 'loc-1',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

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
    discountMinor: 0,
    feeMinor: 5_000,
    totalMinor: 15_000,
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    delivery: {
      feeMinor: 5_000,
      outcome: 'RESOLVED',
      reasonCode: 'RESOLVED',
      minBasketMinor: null,
      freeDeliveryFromMinor: null,
    },
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

async function setUp(
  paymentCodes: readonly string[] = ['CASH'],
  configureCart?: (cart: FakeUiCartService) => void,
  configureDelivery?: (delivery: FakeDeliverySelectionService) => void,
  configureLocations?: (locations: FakeLocationProfileService) => void,
) {
  const cart = new FakeUiCartService();
  const delivery = new FakeDeliverySelectionService();
  cart.paymentMethods.mockResolvedValue(paymentCodes);
  configureCart?.(cart);
  configureDelivery?.(delivery);
  const paymentSessions = new FakePaymentSessionService();
  const notification = new FakeNotificationService();
  const locations = new FakeLocationProfileService();
  configureLocations?.(locations);

  TestBed.configureTestingModule({
    imports: [CartConfirmationComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: cart },
      { provide: OrdersService, useValue: {} },
      { provide: PaymentSessionService, useValue: paymentSessions },
      { provide: NotificationService, useValue: notification },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: LocationProfileService, useValue: locations },
      { provide: APP_CONFIG, useValue: CONFIG },
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
  fixture.detectChanges();

  return {
    fixture,
    comp: fixture.componentInstance,
    cart,
    delivery,
    paymentSessions,
    notification,
    locations,
    navigateSpy,
  };
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

describe('CartConfirmationComponent: the pickup screen names the actual branch', () => {
  function pickupProfile(overrides: Partial<LocationProfile> = {}): LocationProfile {
    return {
      displayName: 'Chilonzor filiali',
      addressLine: "Bunyodkor ko'chasi 12",
      district: 'Chilonzor',
      city: 'Toshkent',
      ...overrides,
    };
  }

  it('shows the branch\'s own name and address once the profile read resolves, for the configured location', async () => {
    const { comp, fixture, locations } = await setUp(
      ['CASH'],
      (cart) => {
        cart.fulfillmentMode.mockReturnValue('PICKUP' as never);
      },
      undefined,
      (locations) => {
        locations.profile.mockResolvedValue(pickupProfile());
      },
    );

    expect(locations.profile).toHaveBeenCalledWith('loc-1');
    expect(comp.pickupLocationName).toBe('Chilonzor filiali');
    expect(comp.pickupLocationAddress).toContain("Bunyodkor ko'chasi 12");
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilonzor filiali');
    expect(text).toContain("Bunyodkor ko'chasi 12");
  });

  it('falls back to the generic label and hint while the profile has not loaded (or is unavailable)', async () => {
    const { comp } = await setUp(['CASH'], (cart) => {
      cart.fulfillmentMode.mockReturnValue('PICKUP' as never);
    });
    // locations.profile resolves to null by default (FakeLocationProfileService).

    expect(comp.pickupLocationName).toBe('cart.pickupLocation');
    expect(comp.pickupLocationAddress).toBe('cart.pickupLocationHint');
  });

  it('never asks for a branch profile on a DELIVERY cart', async () => {
    const { locations } = await setUp(['CASH']);

    expect(locations.profile).not.toHaveBeenCalled();
  });
});

describe('CartConfirmationComponent: CASH wording matches how it is actually paid, by mode', () => {
  it('a DELIVERY cart describes CASH as paid to the courier on receipt', async () => {
    const { comp } = await setUp(['CASH']);

    expect(comp.paymentMethod).toBe('cart.cash / cart.cashSecondary');
  });

  it('a PICKUP cart describes CASH as paid at the branch on collection, not "pay the courier"', async () => {
    // There is no courier on a pickup order -- the pre-fix wording
    // ("Olish paytida kuryerga to'lash" / "pay the courier on receipt") was
    // simply wrong for this mode.
    const { comp } = await setUp(['CASH'], (cart) => {
      cart.fulfillmentMode.mockReturnValue('PICKUP' as never);
    });

    expect(comp.paymentMethod).toBe('cart.cash / cart.cashSecondaryPickup');
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
    const { comp, cart, delivery } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(false);
    delivery.addressId.mockReturnValue(null);

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.addressRequired');
    expect(cart.priceCart).not.toHaveBeenCalled();
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('blames the recipient, not the address, when an address is chosen but nobody is named', async () => {
    // The destination endpoint needs an address *and* a recipient, and the
    // phone never survives a reload. Reporting "address required" over an
    // address the customer already chose sent them back to re-pick something
    // that was never missing -- and there was no field to fix what was.
    const { comp, cart, delivery } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(false);
    delivery.addressId.mockReturnValue('address-1');

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.recipientRequired');
    expect(cart.priceCart).not.toHaveBeenCalled();
  });

  it('hands the typed recipient to the delivery selection before applying the destination', async () => {
    const { comp, cart, delivery } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValue(checkoutResult());
    comp.recipientName = 'Dilnoza';
    comp.recipientPhone = '+998901234567';

    await comp.submitOrder();

    expect(delivery.setRecipient).toHaveBeenCalledWith('Dilnoza', '+998901234567');
    const recipientOrder = delivery.setRecipient.mock.invocationCallOrder[0];
    const destinationOrder = cart.applyDestination.mock.invocationCallOrder[0];
    expect(recipientOrder).toBeLessThan(destinationOrder);
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

describe('CartConfirmationComponent: order button disabled while the delivery fee is unresolved', () => {
  it('is disabled when the delivery fee has not resolved, and the reason is shown', async () => {
    const { comp, fixture } = await setUp(['CASH'], (cart) => {
      cart.canPlaceOrder.mockReturnValue(false);
      cart.deliveryUnresolvedMessage.mockReturnValue('errors.reason.outOfZone');
    });

    expect(comp.canPlaceOrder).toBe(false);
    expect(comp.deliveryUnresolvedMessage).toBe('errors.reason.outOfZone');
    const button = fixture.nativeElement.querySelector(
      '[data-testid="place-order-button"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('errors.reason.outOfZone');
  });

  it('is enabled once the delivery fee resolves', async () => {
    const { comp, fixture } = await setUp(['CASH'], (cart) => {
      cart.canPlaceOrder.mockReturnValue(true);
      cart.deliveryUnresolvedMessage.mockReturnValue(null);
    });

    expect(comp.canPlaceOrder).toBe(true);
    const button = fixture.nativeElement.querySelector(
      '[data-testid="place-order-button"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
  });

  it('is unaffected by delivery resolution for a PICKUP cart', async () => {
    const { comp } = await setUp(['CASH'], (cart) => {
      cart.fulfillmentMode.mockReturnValue('PICKUP' as never);
      cart.canPlaceOrder.mockReturnValue(true);
    });

    expect(comp.delivering).toBe(false);
    expect(comp.canPlaceOrder).toBe(true);
  });
});

describe('CartConfirmationComponent: a fresh DELIVERY cart resolves its fee without a click', () => {
  // A brand-new DELIVERY cart's first price has no destination yet, so
  // `canPlaceOrder` starts false and the order button starts disabled. A
  // disabled <button> never fires (click), and `applyDestination()` was only
  // ever called from inside `submitOrder()` -- so nothing could ever unblock
  // the button once it was already blocked. See ui-cart.service.ts's
  // `applyDestination` doc and `DeliveryChargeResponse`'s "no destination
  // chosen yet" case.
  it('applies the destination on its own once the address and recipient are already known, with no click at all', async () => {
    const { cart } = await setUp(
      ['CASH'],
      (cart) => {
        cart.canPlaceOrder.mockReturnValue(false);
        cart.applyDestination.mockResolvedValue(true);
      },
      (delivery) => {
        delivery.isComplete = vi.fn(() => true);
      },
    );

    expect(cart.applyDestination).toHaveBeenCalled();
  });

  it('never calls applyDestination on its own once the fee is already resolved', async () => {
    const { cart } = await setUp(
      ['CASH'],
      (cart) => {
        cart.canPlaceOrder.mockReturnValue(true);
      },
      (delivery) => {
        delivery.isComplete = vi.fn(() => true);
      },
    );

    expect(cart.applyDestination).not.toHaveBeenCalled();
  });

  it('never calls applyDestination on its own before an address and recipient are both known', async () => {
    const { cart } = await setUp(['CASH'], (cart) => {
      cart.canPlaceOrder.mockReturnValue(false);
    });

    expect(cart.applyDestination).not.toHaveBeenCalled();
  });
});

describe('CartConfirmationComponent.submitOrder: a thrown refusal is reported with its specific reason', () => {
  it('shows the DELIVERY_FEE_UNRESOLVED wording, not the generic orderError', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'x',
        problem: { status: 409, reason: 'DELIVERY_FEE_UNRESOLVED' },
      }),
    );

    await comp.submitOrder();

    expect(comp.orderError()).toBe('errors.reason.deliveryFeeUnresolved');
    expect(comp.orderError()).not.toBe('cart.orderError');
  });

  it('falls back to the generic message for a non-HorecaOSApiError failure', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockRejectedValue(new Error('boom'));

    await comp.submitOrder();

    expect(comp.orderError()).toBe('cart.orderError');
  });
});

describe('CartConfirmationComponent: idempotency key stability across repeated clicks', () => {
  it('reuses the same idempotency key after a failure that never reached the platform (no response at all)', async () => {
    // ApiClient/toHorecaOSApiError normalises a dropped connection or a
    // client-side timeout with no HTTP response to NETWORK_UNREACHABLE at
    // status 0 -- the one case where the original request may in fact have
    // been received, so an *unchanged* retry under the *same* key is what
    // lets a genuine replay answer REPLAYED with the order that already
    // exists, instead of minting a second one.
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockRejectedValue(
      new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'x' }),
    );

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

  it('mints a fresh key after a REJECTED outcome, so a retry is not refused as a key reuse', async () => {
    // No order was created, and applyDestination/priceCart run again on the
    // very next submitOrder() -- a retry under the same key would present a
    // different body and the platform's idempotency store would refuse it
    // as IDEMPOTENCY_KEY_REUSED forever, on a basket the customer may still
    // legitimately want to order.
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockResolvedValueOnce(checkoutResult({ outcome: 'REJECTED' }));

    await comp.submitOrder();
    const key1 = cart.checkout.mock.calls[0][0].idempotencyKey;

    cart.checkout.mockResolvedValueOnce(checkoutResult());
    await comp.submitOrder();
    const key2 = cart.checkout.mock.calls[1][0].idempotencyKey;

    expect(key2).not.toBe(key1);
  });

  it('mints a fresh key after a definite platform refusal thrown from checkout (e.g. PRICE_CHANGED), not just after REJECTED', async () => {
    const { comp, cart } = await setUp(['CASH']);
    cart.applyDestination.mockResolvedValue(true);
    cart.priceCart.mockResolvedValue(pricedFixture());
    cart.checkout.mockRejectedValueOnce(
      new HorecaOSApiError({
        status: 409,
        code: 'PRICE_CHANGED',
        detail: 'x',
        problem: { status: 409, reason: 'PRICE_CHANGED' },
      }),
    );

    await comp.submitOrder();
    const key1 = cart.checkout.mock.calls[0][0].idempotencyKey;

    cart.checkout.mockResolvedValueOnce(checkoutResult());
    await comp.submitOrder();
    const key2 = cart.checkout.mock.calls[1][0].idempotencyKey;

    expect(key2).not.toBe(key1);
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
