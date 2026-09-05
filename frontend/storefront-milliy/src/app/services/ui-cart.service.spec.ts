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
                amountMinor: 25_000,
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
              { variantId: 'v-known', sku: null, unitCode: null, isDefault: true, orderable: true, amountMinor: 1000 },
            ],
            modifierGroupIds: [],
          },
        ],
      }),
    );
    delivery.addressId.mockReturnValue('addr-1');
    customerApi.address.mockResolvedValue(geocodedAddress());
    api.get.mockResolvedValue({
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
    expect(quote).toEqual({ available: false, feeMinor: null });
    // The dedicated "not serviceable" string, not the unresolved dash and
    // not a fee of 0 (which would read as free delivery).
    expect(service.deliveryFee()).toBe('cart.deliveryNotServiceable');
  });

  it('resolves a normal fee when the address is serviceable', async () => {
    const { service, carts, menu, delivery, customerApi, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());
    delivery.addressId.mockReturnValue('addr-1');
    customerApi.address.mockResolvedValue(geocodedAddress());
    api.get.mockResolvedValue({
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

    expect(service.deliveryFeeQuote()).toEqual({ available: true, feeMinor: 12_000 });
    expect(service.deliveryFee()).not.toBe('cart.deliveryNotServiceable');
  });

  it('leaves the preview unresolved (not an error) when no destination has been chosen yet', async () => {
    const { service, carts, menu, delivery, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());
    delivery.addressId.mockReturnValue(null);

    await service.load();

    expect(api.get).not.toHaveBeenCalled();
    expect(service.deliveryFeeQuote()).toBeNull();
    expect(service.deliveryFee()).toBe('—');
  });
});
