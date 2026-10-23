import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Component, signal } from '@angular/core';

import { CheckoutComponent } from './checkout.component';
import {
  UiCartService,
  type HiddenChargeRow,
  type PromotionNote,
  type PromotionRow,
} from '../../services/ui-cart.service';
import type { GiftOfferGroup } from '../../services/applied-promotions';
import { DeliverySelectionService } from '../../services/delivery-selection.service';
import { AddressBookService } from '../../services/address-book.service';
import {
  PaymentSessionService,
  type PaymentSessionResponse,
} from '../../services/payment-session.service';
import { type CartPointsOffer, PointsService } from '../../services/points.service';
import { TranslateService } from '../../services/translate.service';
import type { PricedCart, CheckoutResult, FulfillmentMode } from '../../services/cart.service';
import { HorecaOSApiError } from '../../core/api/problem-details';
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
  /** ADR 0140: the free gifts on offer, and taking one up. */
  readonly giftOffers = signal<readonly GiftOfferGroup[]>([]);
  readonly updating = signal(false);
  addGift = vi.fn(async (_variantId: string) => true);
  readonly errorKey = signal<string | null>(null);
  readonly priceRefusalKey = signal<string | null>(null);
  /** The last pricing answer; the points control reads the cart and quote it names. */
  readonly priced = signal<PricedCart | null>(null);
  formatPrice = (value: number): string =>
    `${value.toLocaleString('en-US').replace(/,/g, ' ')} so'm`;
  orderComment = '';

  items = () => this.cartData()?.items ?? [];
  subtotalFormatted = () => "25 000 so'm";
  deliveryFee = () => "10 000 so'm";
  totalAmount = () => "35 000 so'm";
  hasDiscount = () => false;
  discountFormatted = () => "0 so'm";
  appliedPromoCode = (): string | null => null;
  discountRows = (): PromotionRow[] => [];
  promotionNotes = (): PromotionNote[] => [];
  promoOutcomeKey = (): string | null => null;
  deliveryTimeDisplay = (): string | null => null;
  deliveryUnresolvedMessage = (): string | null => null;
  hiddenCharges = (): readonly HiddenChargeRow[] => [];
  hasProvisionalLines = (): boolean => false;

  load = vi.fn(async () => {});
  paymentMethods = vi.fn(async (): Promise<readonly string[]> => ['CASH']);
  selectPaymentMethod = vi.fn(async (_code: string): Promise<void> => {});
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

/** X.1 (ADR 0185): by default a brand with no points to offer, so the control is not there. */
class FakePointsService {
  redemptionOffer = vi.fn<(cartId: string) => Promise<CartPointsOffer>>(async () =>
    pointsOffer({ redeemable: false, refusal: 'REDEMPTION_NOT_ENABLED' }),
  );
  paymentSplit = vi.fn();
}

/** A cart priced at 25 000 so'm, for a customer with 12 000 in points, of which 4 000 may be spent. */
function pointsOffer(
  overrides: { redeemable?: boolean; maximum?: number; refusal?: string | null } = {},
): CartPointsOffer {
  const redeemable = overrides.redeemable ?? true;
  const maximum = redeemable ? (overrides.maximum ?? 4_000) : 0;
  return {
    cartId: 'cart-1',
    priced: true,
    orderTotal: { amountMinor: 25_000, currency: 'UZS' },
    offer: {
      accountId: 'pa-1',
      available: { amountMinor: 12_000, currency: 'UZS' },
      maximum: { amountMinor: maximum, currency: 'UZS' },
      redeemable,
      refusal: redeemable ? null : (overrides.refusal ?? 'INSUFFICIENT_BALANCE'),
    },
  };
}

class FakeAddressBookService {
  list = vi.fn(async () => []);
}

class FakePaymentSessionService {
  open = vi.fn(async (_orderId: string): Promise<PaymentSessionResponse> => ({
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
  points: FakePointsService;
  router: Router;
}

async function setUp(
  configure: (cart: FakeUiCartService, delivery: FakeDeliverySelectionService) => void = () => {},
  configurePoints: (points: FakePointsService) => void = () => {},
): Promise<Fakes> {
  const cart = new FakeUiCartService();
  const delivery = new FakeDeliverySelectionService();
  const addressBook = new FakeAddressBookService();
  const paymentSession = new FakePaymentSessionService();
  const points = new FakePointsService();
  configure(cart, delivery);
  configurePoints(points);

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
      { provide: PointsService, useValue: points },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const fixture = TestBed.createComponent(CheckoutComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return {
    fixture,
    cart,
    delivery,
    addressBook,
    paymentSession,
    points,
    router: TestBed.inject(Router),
  };
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
      .spyOn(
        fixture.componentInstance as unknown as { redirectTo(url: string): void },
        'redirectTo',
      )
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

  it('posts the checkout form when the session carries one (Payme with a fiscalised cashbox), and does not follow the link', async () => {
    const { fixture, cart, paymentSession, router } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['PAYME']);
    });
    paymentSession.open.mockResolvedValue({
      attemptId: 'a2',
      merchantTransId: 'm2',
      provider: 'PAYME',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: 'https://checkout.payme.example/abc',
      qrPayload: null,
      expiresAt: new Date().toISOString(),
      amountMinor: 25_000,
      currency: 'UZS',
      rePresented: false,
      presentationCount: 1,
      form: {
        action: 'https://checkout.payme.example/',
        fields: { merchant: 'cashbox-1', amount: '1500000', detail: 'ZGV0YWls' },
      },
    });
    const redirectSpy = vi
      .spyOn(
        fixture.componentInstance as unknown as { redirectTo(url: string): void },
        'redirectTo',
      )
      .mockImplementation(() => {});
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(submit).toHaveBeenCalledTimes(1);
    const form = document.body.querySelector('form') as HTMLFormElement;
    expect((form.querySelector('input[name="detail"]') as HTMLInputElement).value).toBe('ZGV0YWls');
    expect(redirectSpy).not.toHaveBeenCalled();
    expect(navigateSpy).not.toHaveBeenCalledWith(['/orders']);
    expect(cart.discard).toHaveBeenCalled();
    form.remove();
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

describe('CheckoutComponent -- the payment method is part of the price (ADR 0140)', () => {
  it('writes the chosen method to the cart when the customer picks one, so the shown total is priced with it', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['CASH', 'CLICK']);
    });
    expect(cart.selectPaymentMethod).not.toHaveBeenCalled();

    const rows = fixture.nativeElement.querySelectorAll(
      '.pay-row',
    ) as NodeListOf<HTMLButtonElement>;
    rows[1].click();
    await fixture.whenStable();

    expect(cart.selectPaymentMethod).toHaveBeenCalledWith('CLICK');
  });

  it('writes the one method on offer without a tap, because a promotion can read it', async () => {
    const { cart } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['CLICK']);
    });

    expect(cart.selectPaymentMethod).toHaveBeenCalledWith('CLICK');
  });

  it('puts the method on the cart before it prices, so checkout is not refused for a method the quote was not priced with', async () => {
    const { fixture, cart } = await setUp((cart) => {
      cart.paymentMethods = vi.fn(async () => ['CLICK']);
    });
    cart.selectPaymentMethod.mockClear();

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(cart.selectPaymentMethod).toHaveBeenCalledWith('CLICK');
    const select = cart.selectPaymentMethod.mock.invocationCallOrder[0];
    expect(select).toBeGreaterThan(cart.applyDestination.mock.invocationCallOrder[0]);
    expect(select).toBeLessThan(cart.priceCart.mock.invocationCallOrder[0]);
    expect(cart.checkout).toHaveBeenCalledWith(
      expect.objectContaining({ paymentMethodCode: 'CLICK' }),
    );
  });

  it('names the reason when the platform refuses the method, and never reaches checkout', async () => {
    const { fixture, cart } = await setUp();
    cart.selectPaymentMethod = vi.fn(async () => {
      throw refusal('PAYMENT_METHOD_UNAVAILABLE');
    });

    (fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('errors.reason.paymentMethodUnavailable');
    expect(fixture.nativeElement.textContent).not.toContain('errors.generic');
    expect(cart.priceCart).not.toHaveBeenCalled();
    expect(cart.checkout).not.toHaveBeenCalled();
  });
});

describe('CheckoutComponent -- free gifts (ADR 0140)', () => {
  it('offers the free gift the platform offered, so one that depends on the payment method is not missed', async () => {
    const { fixture, cart } = await setUp((c) =>
      c.giftOffers.set([
        {
          ruleId: 'rule-1',
          toAdd: 1,
          choices: [{ variantId: 'v-cola', name: 'Cola', image: null, inCart: false }],
        },
      ]),
    );

    const add = fixture.nativeElement.querySelector(
      '[data-testid="gift-offer-add"]',
    ) as HTMLButtonElement;
    expect(add.textContent).toContain('cart.giftOffer.add');
    add.click();

    expect(cart.addGift).toHaveBeenCalledWith('v-cola');
  });

  it('shows no gift block when nothing is on offer', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('[data-testid="gift-offers"]')).toBeNull();
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
    const { fixture } = await setUp((cart) => {
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

  it('says why a code on the cart did not move the price, and does not claim it applied or took off a discount', async () => {
    const { fixture } = await setUp((cart) => {
      cart.appliedPromoCode = () => 'SMALL5';
      cart.hasDiscount = () => true;
      cart.discountFormatted = () => "18 000 so'm";
      cart.promoOutcomeKey = () => 'checkout.promoOffersBetter';
    });

    const text = fixture.nativeElement.textContent as string;
    expect(
      (
        fixture.nativeElement.querySelector('[data-testid="promo-outcome"]') as HTMLElement
      ).textContent?.trim(),
    ).toBe('checkout.promoOffersBetter');
    expect(text).toContain('checkout.promoCodeLabel');
    expect(text).not.toContain('checkout.promoApplied');
    expect(fixture.nativeElement.querySelector('.promo-applied__sub')?.textContent).not.toContain(
      '18 000',
    );
  });

  it('shows the applied code with its discount when the platform says it applied', async () => {
    const { fixture } = await setUp((cart) => {
      cart.appliedPromoCode = () => 'BIG30';
      cart.hasDiscount = () => true;
      cart.discountFormatted = () => "27 000 so'm";
    });

    expect(fixture.nativeElement.querySelector('[data-testid="promo-outcome"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('checkout.promoApplied');
    expect(fixture.nativeElement.querySelector('.promo-applied__sub')?.textContent).toContain(
      '27 000',
    );
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

/** A platform refusal the way `ApiClient` normalises it: code plus a business `reason`. */
function refusal(reason: string, code = 'RESOURCE_CONFLICT', status = 409): HorecaOSApiError {
  return new HorecaOSApiError({
    status,
    code,
    detail: 'refused',
    problem: { status, code, reason },
  });
}

describe('CheckoutComponent.confirm -- a failure names its reason instead of one generic sentence', () => {
  async function submit(configure: (cart: FakeUiCartService) => void) {
    const setUpResult = await setUp(configure);
    (setUpResult.fixture.nativeElement.querySelector('.cta') as HTMLButtonElement).click();
    await setUpResult.fixture.whenStable();
    setUpResult.fixture.detectChanges();
    return setUpResult;
  }

  it('an unresolved delivery fee at checkout says the fee could not be worked out', async () => {
    const { fixture, cart } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw refusal('DELIVERY_FEE_UNRESOLVED');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.deliveryFeeUnresolved');
    expect(fixture.nativeElement.textContent).not.toContain('errors.generic');
    expect(cart.discard).not.toHaveBeenCalled();
  });

  it('a pickup order below the tenant minimum says the order is below the minimum', async () => {
    const { fixture, cart } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw refusal('BELOW_MINIMUM_ORDER');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.belowMinimumOrder');
    expect(fixture.nativeElement.textContent).not.toContain('errors.generic');
    expect(cart.discard).not.toHaveBeenCalled();
  });

  it('a saved address that was never located says the address is the problem', async () => {
    const { fixture, cart } = await submit((cart) => {
      cart.applyDestination = vi.fn(async () => {
        throw refusal('DESTINATION_NOT_LOCATED');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.destinationNotLocated');
    expect(fixture.nativeElement.textContent).not.toContain('errors.generic');
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('a checkout rejected because an item just sold out says so', async () => {
    const { fixture } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw refusal('SOLD_OUT');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.itemUnavailable');
  });

  it('a checkout rejected for a dish outside its sale window says so (row 4.2g)', async () => {
    const { fixture } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw refusal('ITEM_OUT_OF_SALE_WINDOW');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.itemOutOfSaleWindow');
  });

  it('a price that moved says the price changed', async () => {
    const { fixture } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw new HorecaOSApiError({ status: 409, code: 'PRICE_CHANGED', detail: 'moved' });
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.priceChanged');
  });

  it('a dropped connection says offline', async () => {
    const { fixture } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'no route' });
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.offline');
  });

  it('writing the destination can be refused too (an address that no longer exists)', async () => {
    const { fixture, cart } = await submit((cart) => {
      cart.applyDestination = vi.fn(async () => {
        throw refusal('ADDRESS_NOT_FOUND', 'RESOURCE_NOT_FOUND', 404);
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.addressNotFound');
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('a pricing refusal shows the sentence UiCartService kept for it, not a bare generic one', async () => {
    const { fixture, cart } = await submit((cart) => {
      cart.priceCart = vi.fn(async () => {
        cart.errorKey.set('errors.reason.notServiceable');
        return null as never;
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.reason.notServiceable');
    expect(cart.checkout).not.toHaveBeenCalled();
  });

  it('a pricing failure with nothing more specific to say is still the generic sentence', async () => {
    const { fixture } = await submit((cart) => {
      cart.priceCart = vi.fn(async () => null as never);
    });

    expect(fixture.nativeElement.textContent).toContain('errors.generic');
  });

  it('a failure that is not a platform answer stays the generic sentence', async () => {
    const { fixture } = await submit((cart) => {
      cart.checkout = vi.fn(async () => {
        throw new Error('boom');
      });
    });

    expect(fixture.nativeElement.textContent).toContain('errors.generic');
  });
});

describe('CheckoutComponent -- a basket that could not be loaded is not an empty basket', () => {
  it('shows the load failure, with its specific reason, instead of "your cart is empty"', async () => {
    const { fixture } = await setUp((cart) => {
      cart.cartData.set(null);
      cart.errorKey.set('errors.offline');
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('checkout.loadError');
    expect(host.querySelector('[data-testid="checkout-error-detail"]')?.textContent).toContain(
      'errors.offline',
    );
    expect(host.textContent).not.toContain('checkout.emptyCartTitle');
    expect(host.querySelector('.cta')).toBeNull();
  });

  it('adds no redundant second line when the only thing known is the generic sentence', async () => {
    const { fixture } = await setUp((cart) => {
      cart.cartData.set(null);
      cart.errorKey.set('errors.generic');
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('checkout.loadError');
    expect(host.querySelector('[data-testid="checkout-error-detail"]')).toBeNull();
  });

  it('still says the basket is empty when it loaded fine and simply has nothing in it', async () => {
    const { fixture } = await setUp((cart) => cart.cartData.set(null));

    expect(fixture.nativeElement.textContent).toContain('checkout.emptyCartTitle');
  });
});

describe('CheckoutComponent -- the delivery-fee preview explains itself', () => {
  it('shows why the fee is not a price, beside the delivery line', async () => {
    const { fixture } = await setUp((cart) => {
      cart.deliveryUnresolvedMessage = () => 'errors.reason.outOfZone';
    });

    const note = fixture.nativeElement.querySelector('[data-testid="delivery-unresolved"]');
    expect(note?.textContent).toContain('errors.reason.outOfZone');
  });

  it('shows nothing when there is nothing to explain', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('[data-testid="delivery-unresolved"]')).toBeNull();
  });
});

describe('CheckoutComponent -- a basket the platform would not price on arrival', () => {
  it('says why the totals are dashes, before the customer taps confirm', async () => {
    const { fixture } = await setUp((cart) => {
      cart.priceRefusalKey.set('errors.reason.itemOutOfSaleWindow');
    });

    const note = fixture.nativeElement.querySelector('[data-testid="checkout-pricing-error"]');
    expect(note?.textContent).toContain('errors.reason.itemOutOfSaleWindow');
  });

  it('shows nothing for a basket that priced', async () => {
    const { fixture } = await setUp();

    expect(
      fixture.nativeElement.querySelector('[data-testid="checkout-pricing-error"]'),
    ).toBeNull();
  });
});

describe('CheckoutComponent -- what the server added by itself (ADR 0136)', () => {
  it('itemises a charge the customer never chose beside the totals, which already include it', async () => {
    const { fixture } = await setUp((cart) => {
      cart.hiddenCharges = () => [
        { optionId: 'o-box', label: 'Delivery box', amountMinor: 2_000, amount: '2 000 so‘m' },
      ];
    });

    const rows = [...fixture.nativeElement.querySelectorAll('[data-testid="hidden-charge"]')];
    expect(rows).toHaveLength(1);
    expect((rows[0] as HTMLElement).textContent).toContain('Delivery box');
  });

  it('draws no such block for an order the server added nothing to', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('[data-testid="hidden-charges"]')).toBeNull();
  });
});

describe('CheckoutComponent -- items sold by weight (ADR 0137)', () => {
  it('tells the customer before they pay that the total is an estimate while the basket holds a weighed item', async () => {
    const { fixture } = await setUp((cart) => {
      cart.hasProvisionalLines = () => true;
    });

    expect(
      fixture.nativeElement.querySelector('[data-testid="summary-provisional-notice"]'),
    ).not.toBeNull();
  });

  it('says nothing of the kind for a basket of fixed units', async () => {
    const { fixture } = await setUp();

    expect(
      fixture.nativeElement.querySelector('[data-testid="summary-provisional-notice"]'),
    ).toBeNull();
  });
});

describe('CheckoutComponent -- paying part of the order with points (ADR 0046, ADR 0185)', () => {
  /** A checkout whose cart is priced, so the points control has a quote to ask about. */
  async function withPoints(offer: CartPointsOffer = pointsOffer()) {
    const setup = await setUp(
      (cart) => cart.priced.set(priced()),
      (points) => points.redemptionOffer.mockResolvedValue(offer),
    );
    // The control is drawn once the screen is ready, and only then asks what may be spent.
    await new Promise((resolve) => setTimeout(resolve, 0));
    setup.fixture.detectChanges();
    return setup;
  }

  type View = { fixture: { nativeElement: unknown; detectChanges(): void } };
  const byId = ({ fixture }: View, id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);
  /** The text of one element with every kind of space read as a plain one: amounts are grouped with a no-break space. */
  const textOf = (view: View, id: string) =>
    (byId(view, id)?.textContent ?? '').replace(/\s+/g, ' ');
  const cta = ({ fixture }: View) =>
    (fixture.nativeElement as HTMLElement).querySelector('.cta') as HTMLButtonElement;

  async function settle(view: View): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
    view.fixture.detectChanges();
  }

  async function switchOn(view: View): Promise<void> {
    byId(view, 'points-toggle')!.click();
    await settle(view);
  }

  async function type(view: View, value: string): Promise<void> {
    const input = byId(view, 'points-amount') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle(view);
  }

  it('asks what may be spent against the quote the cart is priced under, and offers nothing it was not given', async () => {
    const view = await withPoints(pointsOffer({ redeemable: false }));

    expect(view.points.redemptionOffer).toHaveBeenCalledWith('cart-1');
    expect(byId(view, 'points-redemption')).toBeNull();
  });

  it('shows the control, and sends no points, until the customer switches them on', async () => {
    const view = await withPoints();

    expect(byId(view, 'points-redemption')).not.toBeNull();
    cta(view).click();
    await view.fixture.whenStable();

    expect(view.cart.checkout).toHaveBeenCalledTimes(1);
    expect((view.cart.checkout.mock.calls[0] as unknown[])[0]).not.toHaveProperty(
      'redeemFromBalanceMinor',
    );
  });

  it('starts from the most the platform allows, shows what is left to pay, and sends that amount with the checkout', async () => {
    const view = await withPoints();

    await switchOn(view);

    expect((byId(view, 'points-amount') as HTMLInputElement).value).toBe('4000');
    expect(textOf(view, 'points-cash')).toContain('21 000');
    cta(view).click();
    await view.fixture.whenStable();

    expect(view.cart.checkout).toHaveBeenCalledWith({
      priced: expect.anything(),
      paymentMethodCode: 'CASH',
      idempotencyKey: expect.any(String),
      redeemFromBalanceMinor: 4_000,
    });
  });

  it('shows the points and the cash left in the summary and on the button', async () => {
    const view = await withPoints();

    await switchOn(view);

    expect(textOf(view, 'summary-points')).toContain('4 000');
    expect(textOf(view, 'summary-cash')).toContain('21 000');
    expect(cta(view).textContent).toContain('21 000');
  });

  it('keeps the order from being placed while the amount typed is more than the platform allows', async () => {
    const view = await withPoints();
    await switchOn(view);

    await type(view, '9 000');

    expect(byId(view, 'points-exceeds')).not.toBeNull();
    expect(cta(view).disabled).toBe(true);
    cta(view).click();
    await view.fixture.whenStable();
    expect(view.cart.checkout).not.toHaveBeenCalled();
  });

  it('asks again after pricing, and does not place the order when the most allowed has fallen below the amount chosen', async () => {
    const view = await withPoints();
    await switchOn(view);
    // The re-pricing at confirm moved the cap (a promotion landed).
    view.points.redemptionOffer.mockResolvedValue(pointsOffer({ maximum: 1_000 }));

    cta(view).click();
    await view.fixture.whenStable();
    view.fixture.detectChanges();

    expect(view.cart.checkout).not.toHaveBeenCalled();
    expect(view.fixture.nativeElement.textContent).toContain('cart.pointsChanged');
    const asked = view.points.redemptionOffer.mock.invocationCallOrder.at(-1)!;
    expect(view.cart.priceCart.mock.invocationCallOrder[0]).toBeLessThan(asked);
  });

  it('still places the order when the second look cannot be had: the platform checks the amount at checkout', async () => {
    const view = await withPoints();
    await switchOn(view);
    view.points.redemptionOffer.mockRejectedValue(new Error('offline'));

    cta(view).click();
    await view.fixture.whenStable();

    expect(view.cart.checkout).toHaveBeenCalledWith(
      expect.objectContaining({ redeemFromBalanceMinor: 4_000 }),
    );
  });
});
