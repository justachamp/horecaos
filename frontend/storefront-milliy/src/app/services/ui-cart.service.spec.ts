import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { UiCartService, type DeliveryFeeQuote } from './ui-cart.service';
import { CartService, type PlatformCart, type PricedCart } from './cart.service';
import { MenuService, type PublishedMenu } from './menu.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';
import { LangService } from './lang.service';
import { ApiClient } from '../core/api/api-client';
import { CustomerApi, type CustomerAddress } from '../core/api/customer-api';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';
import type { CartResponseItem } from '../types/cart.types';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

class FakeCartService {
  readonly cart = signal<PlatformCart | null>(null);
  ensure = vi.fn();
  create = vi.fn();
  putLine = vi.fn();
  removeLine = vi.fn();
  clear = vi.fn();
  price = vi.fn();
  setDestination = vi.fn();
  paymentMethods = vi.fn();
  checkout = vi.fn();
  discard = vi.fn();
  applyPromoCode = vi.fn();
  removePromoCode = vi.fn();
  selectPaymentMethod = vi.fn();
}

class FakeMenuService {
  menu = vi.fn();
}

class FakeDeliverySelectionService {
  addressId = vi.fn<() => string | null>(() => null);
  isComplete = vi.fn<() => boolean>(() => false);
  recipientName = vi.fn<() => string>(() => '');
  recipientPhone = vi.fn<() => string>(() => '');
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}(${JSON.stringify(params)})` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeLangService {
  langId = () => 'uz';
}

class FakeCustomerApi {
  address = vi.fn();
}

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

function emptyMenu(overrides: Partial<PublishedMenu> = {}): PublishedMenu {
  return {
    publicationId: 'pub-1',
    locale: 'uz',
    currency: 'UZS',
    categories: [],
    products: [],
    modifierGroups: [],
    ...overrides,
  };
}

function baseCart(overrides: Partial<PlatformCart> = {}): PlatformCart {
  return {
    cartId: 'cart-1',
    locationId: 'loc-1',
    status: 'OPEN',
    currency: 'UZS',
    fulfillmentMode: 'DELIVERY',
    version: 1,
    quoteId: null,
    contextHash: null,
    expiresAt: null,
    lines: [],
    ...overrides,
  };
}

function pricedFor(cart: PlatformCart): PricedCart {
  return {
    cartId: cart.cartId,
    cartVersion: cart.version,
    quoteId: 'quote-1',
    contextHash: 'hash-1',
    currency: cart.currency,
    subtotalMinor: 1000,
    taxMinor: 0,
    totalMinor: 1000,
    discountMinor: 0,
    expiresAt: new Date().toISOString(),
  };
}

interface Fakes {
  service: UiCartService;
  carts: FakeCartService;
  menu: FakeMenuService;
  delivery: FakeDeliverySelectionService;
  customerApi: FakeCustomerApi;
  api: FakeApiClient;
}

function setUp(): Fakes {
  const carts = new FakeCartService();
  const menu = new FakeMenuService();
  const delivery = new FakeDeliverySelectionService();
  const customerApi = new FakeCustomerApi();
  const api = new FakeApiClient();

  menu.menu.mockResolvedValue(emptyMenu());
  carts.price.mockResolvedValue(undefined);

  TestBed.configureTestingModule({
    providers: [
      { provide: CartService, useValue: carts },
      { provide: MenuService, useValue: menu },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
      { provide: CustomerApi, useValue: customerApi },
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });

  return { service: TestBed.inject(UiCartService), carts, menu, delivery, customerApi, api };
}

describe('UiCartService.applyDestination', () => {
  it('short-circuits true for PICKUP without reading the delivery selection or writing a destination', async () => {
    const { service, carts, delivery } = setUp();
    service.fulfillmentMode.set('PICKUP');

    const result = await service.applyDestination();

    expect(result).toBe(true);
    expect(delivery.addressId).not.toHaveBeenCalled();
    expect(carts.setDestination).not.toHaveBeenCalled();
  });

  it('for DELIVERY, returns false and does not write when no address has been chosen', async () => {
    const { service, carts, delivery } = setUp();
    service.fulfillmentMode.set('DELIVERY');
    delivery.addressId.mockReturnValue(null);

    const result = await service.applyDestination();

    expect(result).toBe(false);
    expect(carts.setDestination).not.toHaveBeenCalled();
  });

  it('for DELIVERY with a complete selection, writes the destination and returns true', async () => {
    const { service, carts, delivery } = setUp();
    service.fulfillmentMode.set('DELIVERY');
    delivery.addressId.mockReturnValue('addr-1');
    delivery.isComplete.mockReturnValue(true);
    delivery.recipientName.mockReturnValue('Aziz');
    delivery.recipientPhone.mockReturnValue('+998901234567');
    carts.setDestination.mockResolvedValue(baseCart());

    const result = await service.applyDestination();

    expect(result).toBe(true);
    expect(carts.setDestination).toHaveBeenCalledWith(
      expect.objectContaining({
        addressId: 'addr-1',
        recipientName: 'Aziz',
        recipientPhone: '+998901234567',
      }),
    );
  });
});

describe('UiCartService project() (via load())', () => {
  it('drops a line whose variant has left the menu, and excludes it from the item count', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        { lineKey: 'v-known', variantId: 'v-known', quantity: 2, hasCustomerNote: false },
        { lineKey: 'v-gone', variantId: 'v-gone', quantity: 5, hasCustomerNote: false },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p-known',
            code: null,
            name: 'Osh',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              {
                variantId: 'v-known',
                sku: null,
                unitCode: null,
                isDefault: true,
                orderable: true,
                onSaleNow: true,
                amountMinor: 25_000,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
          },
        ],
      }),
    );

    await service.load();

    const items = service.cartData()?.items ?? [];
    expect(items).toHaveLength(1);
    expect(items[0].item_id).toBe('v-known');
    expect(items[0].name).toBe('Osh');
    // Only the surviving line's quantity counts -- the dropped line
    // contributes nothing, it is not just hidden from the list.
    expect(service.totalItemsCount()).toBe(2);
  });

  it('shows an empty basket, not an error, when every line has left the menu', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [{ lineKey: 'v-gone', variantId: 'v-gone', quantity: 1, hasCustomerNote: false }],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.cartData()?.items).toEqual([]);
  });
});

describe('UiCartService.setQuantity', () => {
  const item: CartResponseItem = {
    variant_id: 'v1',
    price: 5000,
    item_id: 'v1+m1.m2',
    name: 'Osh',
    active: true,
    image: null,
    quantity: 2,
    note: null,
    modifierOptionIds: ['m1', 'm2'],
    modifiers: [],
  };

  it('resends the exact modifier selection on a quantity change, not an empty list', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockResolvedValue(baseCart());

    await service.setQuantity(item, 3);

    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 3,
      modifierOptionIds: ['m1', 'm2'],
    });
    expect(carts.removeLine).not.toHaveBeenCalled();
  });

  it('removes the line instead of writing a zero or negative quantity', async () => {
    const { service, carts } = setUp();
    carts.removeLine.mockResolvedValue(baseCart());

    await service.setQuantity(item, 0);

    expect(carts.removeLine).toHaveBeenCalledWith('v1+m1.m2');
    expect(carts.putLine).not.toHaveBeenCalled();
  });
});

describe('UiCartService delivery-fee preview (refreshDeliveryFee, via load())', () => {
  function deliveryCart(): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
    });
  }

  function geocodedAddress(): CustomerAddress {
    return {
      addressId: 'addr-1',
      label: 'Home',
      fields: {},
      deliveryInstructions: null,
      latitude: 41.3,
      longitude: 69.2,
      coordinateSource: 'CUSTOMER_PIN',
      version: 1,
    };
  }

  it('reports the platform\'s own "not serviceable" answer, not a generic error, and not free delivery', async () => {
    const { service, carts, menu, delivery, customerApi, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p-known',
            code: null,
            name: 'Osh',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              {
                variantId: 'v-known',
                sku: null,
                unitCode: null,
                isDefault: true,
                orderable: true,
                onSaleNow: true,
                amountMinor: 1000,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
          },
        ],
      }),
    );
    delivery.addressId.mockReturnValue('addr-1');
    customerApi.address.mockResolvedValue(geocodedAddress());
    // Copied from DeliveryFeeResolver, not invented: an address no zone covers
    // is outcome OUT_OF_ZONE with the granular reason NO_ZONE_COVERS_ADDRESS.
    api.mutate.mockResolvedValue({
      outcome: 'OUT_OF_ZONE',
      reasonCode: 'NO_ZONE_COVERS_ADDRESS',
      available: false,
      feeMinor: null,
      currency: null,
      minBasketMinor: null,
      freeDeliveryFromMinor: null,
      distanceMeters: null,
      distanceSource: null,
    });

    await service.load();

    expect(customerApi.address).toHaveBeenCalledWith('addr-1');
    const quote = service.deliveryFeeQuote() as DeliveryFeeQuote;
    // The outcome travels with the quote instead of being dropped on the
    // floor -- it is what the customer needs to act on. The granular
    // reasonCode is not what the sentence is keyed on.
    expect(quote).toEqual({
      available: false,
      feeMinor: null,
      outcome: 'OUT_OF_ZONE',
    });
    // Neither a fee of 0 (which would read as free delivery) nor a generic
    // "not serviceable" in the price slot: the slot stays the honest dash, and
    // the specific sentence is explained beside it.
    expect(service.deliveryFee()).toBe('—');
    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.outOfZone');
  });

  it('resolves a normal fee when the address is serviceable', async () => {
    const { service, carts, menu, delivery, customerApi, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());
    delivery.addressId.mockReturnValue('addr-1');
    customerApi.address.mockResolvedValue(geocodedAddress());
    api.mutate.mockResolvedValue({
      outcome: 'RESOLVED',
      reasonCode: null,
      available: true,
      feeMinor: 12_000,
      currency: 'UZS',
      minBasketMinor: null,
      freeDeliveryFromMinor: null,
      distanceMeters: 1200,
      distanceSource: 'HAVERSINE',
    });

    await service.load();

    expect(service.deliveryFeeQuote()).toEqual({
      available: true,
      feeMinor: 12_000,
      outcome: 'RESOLVED',
    });
    expect(service.deliveryFee()).not.toBe('—');
    expect(service.deliveryUnresolvedMessage()).toBeNull();
  });

  it('leaves the preview unresolved (not an error) when no destination has been chosen yet', async () => {
    const { service, carts, menu, delivery, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());
    delivery.addressId.mockReturnValue(null);

    await service.load();

    expect(api.mutate).not.toHaveBeenCalled();
    expect(service.deliveryFeeQuote()).toBeNull();
    expect(service.deliveryFee()).toBe('—');
  });

  it('sends the point and basket in the body, POST, anonymously -- never in the query string', async () => {
    const { service, carts, menu, delivery, customerApi, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());
    delivery.addressId.mockReturnValue('addr-1');
    customerApi.address.mockResolvedValue(geocodedAddress());
    api.mutate.mockResolvedValue({
      outcome: 'RESOLVED',
      reasonCode: null,
      available: true,
      feeMinor: 12_000,
      currency: 'UZS',
      minBasketMinor: null,
      freeDeliveryFromMinor: null,
      distanceMeters: null,
      distanceSource: null,
    });

    await service.load();

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/locations/loc-1/delivery-fee'),
      expect.objectContaining({
        body: expect.objectContaining({ lat: 41.3, lon: 69.2, currency: 'UZS' }),
        anonymous: true,
      }),
    );
    // Never as a query string, however this client renders the call:
    expect(api.mutate.mock.calls[0][1]).not.toContain('lat=');
  });
});

describe('UiCartService.applyPromoCode / removePromoCode (ADR 0072)', () => {
  function cartWith(promoCode: string | null): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
      appliedPromoCode: promoCode,
    });
  }

  it("trims the code, applies it, and re-prices so the discount is the platform's own answer", async () => {
    const { service, carts } = setUp();
    const applied = cartWith('OSH2026');
    carts.applyPromoCode.mockResolvedValue(applied);
    carts.price.mockResolvedValue({ ...pricedFor(applied), discountMinor: 4_800 });

    const ok = await service.applyPromoCode('  OSH2026  ');

    expect(ok).toBe(true);
    expect(carts.applyPromoCode).toHaveBeenCalledWith('OSH2026');
    expect(service.appliedPromoCode()).toBe('OSH2026');
    expect(service.hasDiscount()).toBe(true);
    expect(service.promoError()).toBeNull();
  });

  it('never calls the platform for a blank code', async () => {
    const { service, carts } = setUp();

    const ok = await service.applyPromoCode('   ');

    expect(ok).toBe(false);
    expect(carts.applyPromoCode).not.toHaveBeenCalled();
  });

  it('surfaces a refusal reason as a translated message, never the raw ADR 0031 code', async () => {
    const { service, carts } = setUp();
    carts.applyPromoCode.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'expired',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'CODE_EXPIRED' },
      }),
    );

    const ok = await service.applyPromoCode('OLDCODE');

    expect(ok).toBe(false);
    // The fake TranslateService echoes the key, so this is the *key* the
    // message resolves to -- never 'RESOURCE_CONFLICT' or 'CODE_EXPIRED'.
    expect(service.promoError()).toBe('checkout.promoExpired');
  });

  it('falls back to the generic message for a reason it does not specifically know', async () => {
    const { service, carts } = setUp();
    carts.applyPromoCode.mockRejectedValue(
      new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'boom' }),
    );

    await service.applyPromoCode('X');

    expect(service.promoError()).toBe('errors.generic');
  });

  it('removePromoCode re-prices without the code', async () => {
    const { service, carts } = setUp();
    const cleared = cartWith(null);
    carts.removePromoCode.mockResolvedValue(cleared);
    carts.price.mockResolvedValue({ ...pricedFor(cleared), discountMinor: 0 });

    await service.removePromoCode();

    expect(carts.removePromoCode).toHaveBeenCalled();
    expect(service.appliedPromoCode()).toBeNull();
    expect(service.hasDiscount()).toBe(false);
  });
});

describe('UiCartService.selectPaymentMethod (ADR 0140)', () => {
  function cartPayingBy(code: string | null): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
      paymentMethodCode: code,
    });
  }

  it("writes the method to the cart and re-prices, so the total is the platform's answer for that method", async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy(null));
    const selected = cartPayingBy('CLICK');
    carts.selectPaymentMethod.mockResolvedValue(selected);
    carts.price.mockResolvedValue({ ...pricedFor(selected), discountMinor: 2_500 });

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).toHaveBeenCalledWith('CLICK');
    expect(carts.price).toHaveBeenCalled();
    expect(service.hasDiscount()).toBe(true);
  });

  it('writes nothing when the cart already carries the method, so it is free to repeat before every checkout', async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy('CLICK'));

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).not.toHaveBeenCalled();
    expect(carts.price).not.toHaveBeenCalled();
  });

  it('writes nothing when there is no cart yet', async () => {
    const { service, carts } = setUp();

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).not.toHaveBeenCalled();
  });

  it("throws the platform's refusal to the caller, which owns the screen's error", async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy(null));
    carts.selectPaymentMethod.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'refused',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'PAYMENT_METHOD_UNAVAILABLE' },
      }),
    );

    await expect(service.selectPaymentMethod('CLICK')).rejects.toBeInstanceOf(HorecaOSApiError);
  });
});

describe('UiCartService promotions behind the price (ADR 0140)', () => {
  function cartWith(promoCode: string | null): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
      appliedPromoCode: promoCode,
    });
  }

  async function priceWith(
    overrides: Partial<PricedCart>,
    promoCode: string | null = null,
  ): Promise<UiCartService> {
    const { service, carts, menu } = setUp();
    const cart = cartWith(promoCode);
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue({ ...pricedFor(cart), ...overrides });
    menu.menu.mockResolvedValue(emptyMenu());
    await service.load();
    return service;
  }

  it("prints each discount as its own line with the platform's amount, and the customer's code only on a code line", async () => {
    const service = await priceWith(
      {
        discountMinor: 6_000,
        appliedPromotions: [
          { source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 },
          { source: 'PROMO_CODE', effect: 'DISCOUNT', amountMinor: 2_000 },
        ],
        promoCodeOutcome: 'APPLIED',
      },
      'OSH2026',
    );

    expect(service.discountRows()).toEqual([
      { labelKey: 'cart.offerDiscount', code: null, amount: expect.stringContaining('4') },
      { labelKey: 'cart.promoCode', code: 'OSH2026', amount: expect.stringContaining('2') },
    ]);
  });

  it('labels an automatic discount as an offer, never as a promo code the customer did not type', async () => {
    const service = await priceWith({
      discountMinor: 4_000,
      appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 }],
    });

    expect(service.discountRows().map((row) => row.labelKey)).toEqual(['cart.offerDiscount']);
    expect(service.discountRows()[0].code).toBeNull();
  });

  it('keeps a discount the platform did not break down as one line rather than a gap in the total, labelled a code when the cart has one', async () => {
    const service = await priceWith({ discountMinor: 5_000 }, 'OSH2026');

    expect(service.discountRows()).toEqual([
      { labelKey: 'cart.promoCode', code: 'OSH2026', amount: expect.stringContaining('5') },
    ]);
  });

  it('labels an unexplained discount an offer when the cart has no code', async () => {
    const service = await priceWith({ discountMinor: 5_000 });

    expect(service.discountRows().map((row) => row.labelKey)).toEqual(['cart.offerDiscount']);
  });

  it('has no discount line when nothing was discounted', async () => {
    const service = await priceWith({ discountMinor: 0, appliedPromotions: [] });

    expect(service.discountRows()).toEqual([]);
  });

  it('explains a delivery offer and a surcharge as captions, not as lines that would be added twice', async () => {
    const service = await priceWith({
      appliedPromotions: [
        { source: 'AUTOMATIC', effect: 'DELIVERY_DISCOUNT', amountMinor: 5_000 },
        { source: 'AUTOMATIC', effect: 'SURCHARGE', amountMinor: 1_500 },
      ],
    });

    expect(service.discountRows()).toEqual([]);
    expect(service.promotionNotes()).toEqual([
      { labelKey: 'cart.deliveryOfferNote', amount: expect.stringContaining('5') },
      { labelKey: 'cart.surchargeNote', amount: expect.stringContaining('1') },
    ]);
  });

  it("says why a code on the cart did not move the price, from the platform's verdict", async () => {
    const service = await priceWith(
      {
        discountMinor: 18_000,
        appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 18_000 }],
        promoCodeOutcome: 'OFFERS_ARE_BETTER',
      },
      'SMALL5',
    );

    expect(service.promoOutcomeKey()).toBe('checkout.promoOffersBetter');
  });

  it('says nothing about a code that applied', async () => {
    const service = await priceWith({ promoCodeOutcome: 'APPLIED' }, 'BIG30');

    expect(service.promoOutcomeKey()).toBeNull();
  });

  it('says nothing about a code when the cart carries none', async () => {
    const service = await priceWith({ promoCodeOutcome: null });

    expect(service.promoOutcomeKey()).toBeNull();
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

function offline(): HorecaOSApiError {
  return new HorecaOSApiError({
    status: 0,
    code: 'NETWORK_UNREACHABLE',
    detail: 'The request did not reach the platform.',
  });
}

/** A cart line for `variantId`, as the platform reports it. */
function lineOf(variantId: string, quantity = 1, extra: Record<string, unknown> = {}) {
  return { lineKey: variantId, variantId, quantity, hasCustomerNote: false, ...extra };
}

/** A published product with one variant. */
function productOf(
  variantId: string,
  name: string,
  availability: { orderable?: boolean; onSaleNow?: boolean } = {},
) {
  return {
    productId: `p-${variantId}`,
    code: null,
    name,
    description: null,
    mediaAssetIds: [],
    imageUrls: [`/${variantId}.png`],
    variants: [
      {
        variantId,
        sku: null,
        unitCode: null,
        isDefault: true,
        orderable: availability.orderable ?? true,
        onSaleNow: availability.onSaleNow ?? true,
        amountMinor: 10_000,
        remainingQuantity: null,
      },
    ],
    modifierGroupIds: [],
  };
}

describe('UiCartService gift offers (ADR 0140: an offer, never a line)', () => {
  const GIFT = {
    ruleId: 'rule-1',
    variantId: 'v-cola',
    quantity: 1,
    inCart: false,
    toAdd: 1,
  };

  async function offered(
    giftOffers: PricedCart['giftOffers'],
    lines = [lineOf('v-known')],
    products = [productOf('v-known', 'Osh'), productOf('v-cola', 'Cola')],
  ) {
    const fakes = setUp();
    const cart = baseCart({ lines });
    fakes.carts.ensure.mockResolvedValue(cart);
    fakes.carts.price.mockResolvedValue({ ...pricedFor(cart), giftOffers });
    fakes.menu.menu.mockResolvedValue(emptyMenu({ products }));
    await fakes.service.load();
    return { ...fakes, cart };
  }

  it('offers the free gift by the name the menu gives it, and puts nothing in the basket', async () => {
    const { service, carts } = await offered([GIFT]);

    expect(service.giftOffers()).toEqual([
      {
        ruleId: 'rule-1',
        toAdd: 1,
        choices: [{ variantId: 'v-cola', name: 'Cola', image: '/v-cola.png', inCart: false }],
      },
    ]);
    expect(service.items().map((item) => item.variant_id)).toEqual(['v-known']);
    expect(carts.putLine).not.toHaveBeenCalled();
  });

  it('offers nothing when the priced cart carries no offer', async () => {
    const { service } = await offered(undefined);

    expect(service.giftOffers()).toEqual([]);
  });

  it('does not offer a gift the menu no longer carries', async () => {
    const { service } = await offered([{ ...GIFT, variantId: 'v-gone' }]);

    expect(service.giftOffers()).toEqual([]);
  });

  it('does not offer a gift that cannot be ordered right now, which adding would only get refused', async () => {
    const soldOut = await offered(
      [GIFT],
      [lineOf('v-known')],
      [productOf('v-known', 'Osh'), productOf('v-cola', 'Cola', { orderable: false })],
    );
    expect(soldOut.service.giftOffers()).toEqual([]);
    TestBed.resetTestingModule();

    const outOfWindow = await offered(
      [GIFT],
      [lineOf('v-known')],
      [productOf('v-known', 'Osh'), productOf('v-cola', 'Cola', { onSaleNow: false })],
    );
    expect(outOfWindow.service.giftOffers()).toEqual([]);
  });

  it('adds the gift through the normal cart call, then prices the cart again', async () => {
    const { service, carts } = await offered([GIFT]);
    const withGift = baseCart({ lines: [lineOf('v-known'), lineOf('v-cola')], version: 2 });
    carts.putLine.mockResolvedValue(withGift);
    carts.price.mockClear();
    carts.price.mockResolvedValue({
      ...pricedFor(withGift),
      discountMinor: 10_000,
      appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 10_000 }],
      giftOffers: [{ ...GIFT, inCart: true, toAdd: 0 }],
    });

    const added = await service.addGift('v-cola');

    expect(added).toBe(true);
    expect(carts.putLine).toHaveBeenCalledTimes(1);
    expect(carts.putLine.mock.calls[0][0]).toMatchObject({ variantId: 'v-cola', quantity: 1 });
    expect(carts.price).toHaveBeenCalled();
    // The platform priced it free; the offer is gone and the discount is its own figure.
    expect(service.giftOffers()).toEqual([]);
    expect(service.discountMinor()).toBe(10_000);
  });

  it('adds only what is still missing when the cart already holds part of the allowance', async () => {
    const { service, carts } = await offered(
      [{ ...GIFT, quantity: 3, inCart: true, toAdd: 2 }],
      [lineOf('v-known'), lineOf('v-cola', 1)],
    );
    carts.putLine.mockResolvedValue(baseCart({ lines: [lineOf('v-cola', 3)] }));

    await service.addGift('v-cola');

    // One already there plus the two missing: the line is replaced by its new quantity.
    expect(carts.putLine.mock.calls[0][0]).toMatchObject({ variantId: 'v-cola', quantity: 3 });
  });

  it('adds nothing for a variant that is not on offer', async () => {
    const { service, carts } = await offered([GIFT]);

    const added = await service.addGift('v-known');

    expect(added).toBe(false);
    expect(carts.putLine).not.toHaveBeenCalled();
  });

  it('says so, with the specific sentence, when the platform refuses the gift', async () => {
    const { service, carts } = await offered([GIFT]);
    carts.putLine.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'sold out',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'SOLD_OUT' },
      }),
    );

    const added = await service.addGift('v-cola');

    expect(added).toBe(false);
    expect(service.errorKey()).toBe('errors.reason.itemUnavailable');
  });
});

describe('UiCartService: every cart failure reaches the customer as the specific sentence', () => {
  it('add: a sale-window refusal returns false and names the sale window, not "something went wrong"', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockRejectedValue(refusal('ITEM_OUT_OF_SALE_WINDOW'));

    const ok = await service.add('v1', 1);

    expect(ok).toBe(false);
    expect(service.errorKey()).toBe('errors.reason.itemOutOfSaleWindow');
    // The fake TranslateService echoes the key, so this is the key the
    // sentence resolves through -- never the raw reason code.
    expect(service.error()).toBe('errors.reason.itemOutOfSaleWindow');
  });

  it('add: a sold-out refusal says the item just sold out', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockRejectedValue(refusal('SOLD_OUT'));

    expect(await service.add('v1', 1)).toBe(false);
    expect(service.errorKey()).toBe('errors.reason.itemUnavailable');
  });

  it('add: success returns true and clears the last failure', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockRejectedValueOnce(refusal('SOLD_OUT'));
    await service.add('v1', 1);
    expect(service.errorKey()).not.toBeNull();

    const cart = baseCart({
      lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
    });
    carts.putLine.mockResolvedValueOnce(cart);
    carts.price.mockResolvedValue(pricedFor(cart));

    expect(await service.add('v1', 1)).toBe(true);
    expect(service.errorKey()).toBeNull();
    expect(service.error()).toBeNull();
  });

  it('add: a failure that is not a platform answer stays the generic sentence', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockRejectedValue(new Error('boom'));

    expect(await service.add('v1', 1)).toBe(false);
    expect(service.errorKey()).toBe('errors.generic');
  });

  it('add: a dropped connection says offline, so the customer knows to check their network', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockRejectedValue(offline());

    await service.add('v1', 1);

    expect(service.errorKey()).toBe('errors.offline');
  });

  it('setQuantity: a refusal on a quantity change is named too', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockRejectedValue(refusal('CART_EXPIRED'));
    const item: CartResponseItem = {
      variant_id: 'v1',
      price: 5000,
      item_id: 'v1',
      name: 'Osh',
      active: true,
      image: null,
      quantity: 1,
      note: null,
      modifierOptionIds: [],
      modifiers: [],
    };

    await service.setQuantity(item, 2);

    expect(service.errorKey()).toBe('errors.reason.cartExpired');
  });

  it('setQuantity: a stale cart version says the basket changed, not a generic failure', async () => {
    const { service, carts } = setUp();
    carts.removeLine.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'STALE_VERSION', detail: 'stale' }),
    );
    const item = { variant_id: 'v1', item_id: 'v1' } as CartResponseItem;

    await service.setQuantity(item, 0);

    expect(service.errorKey()).toBe('errors.staleVersion');
  });

  it('load: a dropped connection says offline', async () => {
    const { service, carts } = setUp();
    carts.ensure.mockRejectedValue(offline());

    await service.load();

    expect(service.errorKey()).toBe('errors.offline');
  });

  it('clearCart: a refusal is named', async () => {
    const { service, carts } = setUp();
    carts.clear.mockRejectedValue(refusal('CART_NOT_EDITABLE'));

    await service.clearCart();

    expect(service.errorKey()).toBe('errors.reason.cartNotEditable');
  });

  it('switchFulfillmentMode: a branch that cannot take the new mode says so', async () => {
    const { service, carts } = setUp();
    carts.cart.set(
      baseCart({
        lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
      }),
    );
    carts.create.mockRejectedValue(refusal('NOT_SERVICEABLE'));

    await service.switchFulfillmentMode('PICKUP');

    expect(service.errorKey()).toBe('errors.reason.notServiceable');
  });

  it("priceCart: returns null and keeps the refusal's own sentence for the checkout screen to show", async () => {
    const { service, carts } = setUp();
    carts.cart.set(baseCart());
    carts.price.mockRejectedValue(refusal('DELIVERY_DESTINATION_REQUIRED'));

    const priced = await service.priceCart();

    expect(priced).toBeNull();
    expect(service.errorKey()).toBe('errors.reason.destinationRequired');
  });

  it('removePromoCode: a refusal is named, not flattened to the generic sentence', async () => {
    const { service, carts } = setUp();
    carts.removePromoCode.mockRejectedValue(refusal('CART_NOT_EDITABLE'));

    await service.removePromoCode();

    expect(service.promoError()).toBe('errors.reason.cartNotEditable');
  });
});

describe('UiCartService.deliveryUnresolvedMessage: why the fee preview is not a price', () => {
  const quote = (overrides: Partial<DeliveryFeeQuote> = {}): DeliveryFeeQuote => ({
    available: false,
    feeMinor: null,
    outcome: 'OUT_OF_ZONE',
    ...overrides,
  });

  it('is null for a resolved fee', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ available: true, feeMinor: 12_000, outcome: 'RESOLVED' }));

    expect(service.deliveryUnresolvedMessage()).toBeNull();
  });

  it('is null when there is no preview to explain (no destination chosen, or the read failed)', () => {
    const { service } = setUp();

    expect(service.deliveryUnresolvedMessage()).toBeNull();
  });

  it('is null for a collection cart, whatever the preview last said', () => {
    const { service } = setUp();
    service.fulfillmentMode.set('PICKUP');
    service.deliveryFeeQuote.set(quote());

    expect(service.deliveryUnresolvedMessage()).toBeNull();
  });

  // Every refusal DeliveryFeeResolver can produce, by DeliveryFeeOutcome name
  // (the field the storefront branches on), with the sentence it must read as.
  it.each([
    ['OUT_OF_ZONE', 'errors.reason.outOfZone'],
    ['OUTSIDE_CATCHMENT', 'errors.reason.outOfZone'],
    ['BEYOND_MAX_DISTANCE', 'errors.reason.outOfZone'],
    ['NO_TARIFF', 'errors.reason.deliveryFeeUnresolved'],
    ['LOCATION_NOT_LOCATED', 'errors.reason.deliveryFeeUnresolved'],
  ])('maps the resolver outcome %s to its own sentence', (outcome, key) => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ outcome }));

    expect(service.deliveryUnresolvedMessage()).toBe(key);
  });

  it('never leaks an outcome this build has no sentence for', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ outcome: 'SOMETHING_NEW' }));

    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.deliveryFeeUnresolved');
  });
});

describe('UiCartService project(): a line the menu no longer lets the customer buy is marked, with the reason (row 4.2g)', () => {
  function productWith(orderable: boolean, onSaleNow: boolean) {
    return {
      productId: 'p1',
      code: null,
      name: 'Osh',
      description: null,
      mediaAssetIds: [],
      imageUrls: [],
      variants: [
        {
          variantId: 'v1',
          sku: null,
          unitCode: null,
          isDefault: true,
          orderable,
          onSaleNow,
          amountMinor: 25_000,
          remainingQuantity: null,
        },
      ],
      modifierGroupIds: [],
    };
  }

  async function projected(orderable: boolean, onSaleNow: boolean) {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu({ products: [productWith(orderable, onSaleNow)] }));
    await service.load();
    return service.cartData()?.items[0];
  }

  it('an ordinary line is active with no reason', async () => {
    const item = await projected(true, true);

    expect(item?.active).toBe(true);
    expect(item?.unavailableReason).toBeUndefined();
  });

  it('a sold-out line is inactive and says sold out', async () => {
    const item = await projected(false, true);

    expect(item?.active).toBe(false);
    expect(item?.unavailableReason).toBe('SOLD_OUT');
  });

  it('a line outside its sale window is inactive and says so -- not "sold out"', async () => {
    const item = await projected(true, false);

    expect(item?.active).toBe(false);
    expect(item?.unavailableReason).toBe('OUT_OF_SALE_WINDOW');
  });
});

describe('UiCartService project(): a refused pricing never renders as a zero total', () => {
  function offWindowMenu() {
    return emptyMenu({
      products: [
        {
          productId: 'p1',
          code: null,
          name: 'Breakfast osh',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [
            {
              variantId: 'v1',
              sku: null,
              unitCode: null,
              isDefault: true,
              orderable: true,
              onSaleNow: false,
              amountMinor: 45_000,
              remainingQuantity: null,
            },
          ],
          modifierGroupIds: [],
        },
      ],
    });
  }

  function heldCart(): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
    });
  }

  it('reads the subtotal and total as unknown, not free, when POST /pricing refuses during load()', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(heldCart());
    carts.price.mockRejectedValue(refusal('ITEM_OUT_OF_SALE_WINDOW'));
    menu.menu.mockResolvedValue(offWindowMenu());

    await service.load();

    expect(service.priced()).toBeNull();
    expect(service.totalAmount()).toBe('—');
    expect(service.subtotalFormatted()).toBe('—');
    // The refusal's own sentence is kept -- and it is not a load failure: the
    // basket stays on screen, so errorKey (which turns the page into "could
    // not be loaded") stays clear.
    expect(service.priceRefusalKey()).toBe('errors.reason.itemOutOfSaleWindow');
    expect(service.errorKey()).toBeNull();
    expect(service.items()).toHaveLength(1);
  });

  it('names a dropped connection as such, not as a sale-window problem', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(heldCart());
    carts.price.mockRejectedValue(offline());
    menu.menu.mockResolvedValue(offWindowMenu());

    await service.load();

    expect(service.priceRefusalKey()).toBe('errors.offline');
    expect(service.totalAmount()).toBe('—');
  });

  it('forgets the refusal once the cart prices again', async () => {
    const { service, carts, menu } = setUp();
    const cart = heldCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockRejectedValueOnce(refusal('ITEM_OUT_OF_SALE_WINDOW'));
    menu.menu.mockResolvedValue(offWindowMenu());
    await service.load();
    expect(service.priceRefusalKey()).not.toBeNull();

    carts.price.mockResolvedValue(pricedFor(cart));
    await service.load();

    expect(service.priceRefusalKey()).toBeNull();
    expect(service.totalAmount()).toBe(service.formatPrice(1000));
  });

  it("still reads the platform's own numbers once a price is held", async () => {
    const { service, carts, menu } = setUp();
    const cart = heldCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.totalAmount()).toBe(service.formatPrice(1000));
    expect(service.subtotalFormatted()).toBe(service.formatPrice(1000));
    expect(service.priceRefusalKey()).toBeNull();
  });
});

describe('UiCartService: combos and what the server added (ADR 0136)', () => {
  const picks = [
    { componentId: 'c-burger', quantity: 1 },
    { componentId: 'c-cola', quantity: 2 },
  ];

  function comboMenu(): PublishedMenu {
    const component = (
      componentId: string,
      name: string,
      amountMinor: number,
      defaultQuantity = 1,
    ) => ({
      componentId,
      variantId: `${componentId}-v`,
      productId: null,
      name,
      variantName: null,
      defaultQuantity,
      sortOrder: 0,
      orderable: true,
      amountMinor,
    });
    return emptyMenu({
      products: [
        {
          productId: 'p-lunch',
          code: null,
          name: 'Lunch box',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [
            {
              variantId: 'v-lunch',
              sku: null,
              unitCode: null,
              isDefault: true,
              orderable: true,
              amountMinor: null,
              onSaleNow: true,
              remainingQuantity: null,
            },
          ],
          modifierGroupIds: [],
          comboGroupIds: ['g-main'],
        },
      ],
      comboGroups: [
        {
          comboGroupId: 'g-main',
          containerVariantId: 'v-lunch',
          code: 'MAIN',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 3,
          allowSameComponentMultipleTimes: true,
          sortOrder: 0,
          components: [
            component('c-burger', 'Burger', 25_000),
            component('c-cola', 'Cola', 3_000, 2),
          ],
        },
      ],
    });
  }

  it('shows a combo line as the container with its components, priced as the sum of what its picks cost', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-lunchcabc',
          variantId: 'v-lunch',
          quantity: 2,
          hasCustomerNote: false,
          comboPicks: picks,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(comboMenu());

    await service.load();

    const [item] = service.cartData()?.items ?? [];
    expect(item.name).toBe('Lunch box');
    // 25 000 + 2 picks of a cola at 3 000 x 2 units each = 25 000 + 12 000, per combo.
    expect(item.price).toBe(37_000);
    expect(item.comboPicks).toEqual(picks);
    expect(item.comboComponents?.map((c) => [c.name, c.quantity])).toEqual([
      ['Burger', 1],
      ['Cola', 4],
    ]);
  });

  it('puts a combo into the cart with its picks, and a plain line without any', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockResolvedValue(baseCart());
    menu.menu.mockResolvedValue(emptyMenu());

    await service.add('v-lunch', 1, undefined, [], picks);

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', comboPicks: picks }),
    );
  });

  it('resends a combo’s picks on a quantity change, or its quantity would strip them', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockResolvedValue(baseCart());
    const item: CartResponseItem = {
      variant_id: 'v-lunch',
      price: 37_000,
      item_id: 'v-lunchcabc',
      name: 'Lunch box',
      active: true,
      image: null,
      quantity: 1,
      note: null,
      modifierOptionIds: [],
      modifiers: [],
      comboPicks: picks,
    };

    await service.setQuantity(item, 2);

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', quantity: 2, comboPicks: picks }),
    );
  });

  it('carries a combo’s picks across a change of fulfilment mode', async () => {
    const { service, carts, menu } = setUp();
    carts.cart.set(
      baseCart({
        lines: [
          {
            lineKey: 'v-lunchcabc',
            variantId: 'v-lunch',
            quantity: 1,
            hasCustomerNote: false,
            comboPicks: picks,
          },
        ],
      }),
    );
    carts.create.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    carts.putLine.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.switchFulfillmentMode('PICKUP');

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', comboPicks: picks }),
    );
  });

  it('itemises what the server added by itself, named from the menu, one row per option with the lines summed', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue({
      ...pricedFor(cart),
      hiddenCharges: [
        { lineKey: 'v-1', optionId: 'o-box', amountMinor: 2_000 },
        { lineKey: 'v-2', optionId: 'o-box', amountMinor: 2_000 },
      ],
    });
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p-1',
            code: null,
            name: 'Salad',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              {
                variantId: 'v-1',
                sku: null,
                unitCode: null,
                isDefault: true,
                orderable: true,
                amountMinor: 20_000,
                onSaleNow: true,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
          },
        ],
        modifierGroups: [
          {
            modifierGroupId: 'g-box',
            code: 'BOX',
            name: 'Box',
            required: true,
            minimumSelections: 1,
            maximumSelections: 1,
            allowSameOptionMultipleTimes: false,
            options: [
              {
                optionId: 'o-box',
                code: 'BOX',
                maximumQuantity: 1,
                amountMinor: 2_000,
                name: 'Delivery box',
              },
            ],
          },
        ],
      }),
    );

    await service.load();

    expect(service.hiddenCharges()).toEqual([
      {
        optionId: 'o-box',
        label: 'Delivery box',
        amountMinor: 4_000,
        amount: service.formatPrice(4_000),
      },
    ]);
  });

  it('shows nothing of the kind for a cart the server added nothing to', async () => {
    const { service, carts } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue({ ...pricedFor(cart), hiddenCharges: [] });

    await service.load();

    expect(service.hiddenCharges()).toEqual([]);
  });

  it('names a charge whose option the menu does not carry with a neutral label rather than an id', async () => {
    const { service, carts } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue({
      ...pricedFor(cart),
      hiddenCharges: [{ lineKey: 'v-1', optionId: 'o-unknown', amountMinor: 0 }],
    });

    await service.load();

    expect(service.hiddenCharges()[0].label).toBe('cart.hiddenCharge.fallbackLabel');
    expect(service.hiddenCharges()[0].amountMinor).toBe(0);
  });
});

describe('UiCartService with portions and weighed items (ADR 0137)', () => {
  function variant(
    variantId: string,
    amountMinor: number,
    physical: PublishedMenu['products'][number]['variants'][number]['physical'],
  ): PublishedMenu['products'][number]['variants'][number] {
    return {
      variantId,
      sku: null,
      unitCode: null,
      isDefault: true,
      orderable: true,
      amountMinor,
      onSaleNow: true,
      remainingQuantity: null,
      physical,
    };
  }

  function product(
    productId: string,
    name: string,
    variants: PublishedMenu['products'][number]['variants'],
  ): PublishedMenu['products'][number] {
    return {
      productId,
      code: null,
      name,
      description: null,
      mediaAssetIds: [],
      imageUrls: [],
      variants,
      modifierGroupIds: [],
    } as PublishedMenu['products'][number];
  }

  const PLOV = variant('v-plov', 38_000, {
    catchweight: false,
    splittable: true,
    portionSize: 0.5,
  });
  const CAKE = variant('v-cake', 15_000, {
    catchweight: true,
    catchweightQuantumGrams: 100,
    catchweightNominalGrams: 1_200,
    splittable: false,
  });
  const COLA = variant('v-cola', 9_000, null);

  async function loaded(quantities: Record<string, number>) {
    const fakes = setUp();
    const cart = baseCart({
      lines: Object.entries(quantities).map(([variantId, quantity]) => ({
        lineKey: variantId,
        variantId,
        quantity,
        hasCustomerNote: false,
      })),
    });
    fakes.carts.cart.set(cart);
    fakes.carts.ensure.mockResolvedValue(cart);
    fakes.carts.price.mockResolvedValue(pricedFor(cart));
    fakes.carts.putLine.mockResolvedValue(cart);
    fakes.carts.removeLine.mockResolvedValue(cart);
    fakes.menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          product('p-plov', 'Plov', [PLOV]),
          product('p-cake', 'Medovik', [CAKE]),
          product('p-cola', 'Cola', [COLA]),
        ],
      }),
    );
    await fakes.service.load();
    return fakes;
  }

  function itemOf(service: UiCartService, variantId: string): CartResponseItem {
    return service.items().find((item) => item.variant_id === variantId) as CartResponseItem;
  }

  it('carries each variant’s physical facts onto its cart line', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cola': 2 });

    expect(itemOf(service, 'v-plov').physical?.portionSize).toBe(0.5);
    expect(itemOf(service, 'v-cola').physical ?? null).toBeNull();
  });

  it('moves a splittable line by its portion size, and a plain one by one', async () => {
    const { service, carts } = await loaded({ 'v-plov': 1, 'v-cola': 2 });

    service.increaseQuantity(itemOf(service, 'v-plov'));
    service.decreaseQuantity(itemOf(service, 'v-cola'));
    await Promise.resolve();

    const quantities = carts.putLine.mock.calls.map((call) => [
      call[0].variantId,
      call[0].quantity,
    ]);
    expect(quantities).toContainEqual(['v-plov', 1.5]);
    expect(quantities).toContainEqual(['v-cola', 1]);
  });

  it('a half portion is stepped down to nothing, which removes the line rather than writing zero', async () => {
    const { service, carts } = await loaded({ 'v-plov': 0.5 });

    service.decreaseQuantity(itemOf(service, 'v-plov'));
    await Promise.resolve();

    expect(carts.removeLine).toHaveBeenCalledWith('v-plov');
    expect(carts.putLine).not.toHaveBeenCalled();
  });

  it('does not let floating-point noise reach the platform: 0.2 + 0.1 is written 0.3', async () => {
    const { service, carts } = await loaded({ 'v-plov': 0.2 });
    const item = {
      ...itemOf(service, 'v-plov'),
      physical: { catchweight: false, splittable: true, portionSize: 0.1 },
    };

    service.increaseQuantity(item);
    await Promise.resolve();

    expect(carts.putLine.mock.calls[0][0].quantity).toBe(0.3);
  });

  it('prices a portion at its share of the price, and a weighed line at its estimated weight', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cake': 2, 'v-cola': 3 });

    expect(service.lineAmount(itemOf(service, 'v-plov'))).toBe(19_000);
    expect(service.lineAmount(itemOf(service, 'v-cake'))).toBe(360_000);
    expect(service.lineAmount(itemOf(service, 'v-cola'))).toBe(27_000);
  });

  it('counts a half portion as one plate in the basket badge, not as half of one', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cola': 2 });

    expect(service.totalItemsCount()).toBe(3);
  });

  it('knows when the basket holds something sold by weight, so its total is an estimate', async () => {
    expect((await loaded({ 'v-cola': 2, 'v-cake': 1 })).service.hasProvisionalLines()).toBe(true);
  });

  it('does not call a basket of fixed units an estimate', async () => {
    expect((await loaded({ 'v-cola': 2 })).service.hasProvisionalLines()).toBe(false);
  });
});
