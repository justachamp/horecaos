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
      expect.objectContaining({ addressId: 'addr-1', recipientName: 'Aziz', recipientPhone: '+998901234567' }),
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
    api.mutate.mockResolvedValue({
      outcome: 'NOT_SERVICEABLE',
      reasonCode: 'OUT_OF_ZONE',
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
    // The reason the resolver gave travels with the quote instead of being
    // dropped on the floor -- it is what the customer needs to act on.
    expect(quote).toEqual({
      available: false,
      feeMinor: null,
      reasonCode: 'OUT_OF_ZONE',
      minBasketMinor: null,
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
      outcome: 'OK',
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
      reasonCode: null,
      minBasketMinor: null,
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
      outcome: 'OK',
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

  it('trims the code, applies it, and re-prices so the discount is the platform\'s own answer', async () => {
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

  it('priceCart: returns null and keeps the refusal\'s own sentence for the checkout screen to show', async () => {
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
    reasonCode: 'OUT_OF_ZONE',
    minBasketMinor: null,
    ...overrides,
  });

  it('is null for a resolved fee', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ available: true, feeMinor: 12_000, reasonCode: null }));

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

  it('names the minimum basket amount when that is why', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(
      quote({ reasonCode: 'BELOW_MINIMUM_BASKET', minBasketMinor: 50_000 }),
    );

    expect(service.deliveryUnresolvedMessage()).toBe(
      `errors.reason.minimumBasketAmount(${JSON.stringify({ amount: service.formatPrice(50_000) })})`,
    );
  });

  it('falls back to the generic minimum-basket sentence when the zone did not send the amount', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ reasonCode: 'BELOW_MINIMUM_BASKET', minBasketMinor: null }));

    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.minimumBasketNotMet');
  });

  it('maps every resolver reason through the same vocabulary the checkout refusal uses', () => {
    const { service } = setUp();

    service.deliveryFeeQuote.set(quote({ reasonCode: 'NO_TARIFF' }));
    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.deliveryFeeUnresolved');
    service.deliveryFeeQuote.set(quote({ reasonCode: 'BEYOND_MAX_DISTANCE' }));
    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.outOfZone');
  });

  it('never leaks a reason code this build has no sentence for', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ reasonCode: 'SOMETHING_NEW' }));

    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.deliveryFeeUnresolved');
  });

  it('a refusal with no reason at all is still one honest sentence', () => {
    const { service } = setUp();
    service.deliveryFeeQuote.set(quote({ reasonCode: null }));

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
