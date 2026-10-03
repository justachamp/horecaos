import { TestBed } from '@angular/core/testing';

import { CartService, type PlatformCart } from './cart.service';
import { DineInCartService } from './dine-in-cart.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const LOCATION = 'location-1';
const BRAND_PATH = `/storefront/tenants/${CONFIG.tenantId}/brands/${CONFIG.brandId}`;

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
}

function cart(overrides: Partial<PlatformCart> = {}): PlatformCart {
  return {
    cartId: 'cart-1',
    locationId: LOCATION,
    status: 'OPEN',
    currency: 'UZS',
    fulfillmentMode: 'DINE_IN',
    version: 1,
    quoteId: null,
    contextHash: null,
    expiresAt: null,
    lines: [],
    ...overrides,
  };
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return {
    api,
    delivery: TestBed.inject(CartService),
    table: TestBed.inject(DineInCartService),
  };
}

function notFound(): HorecaOSApiError {
  return new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'gone' });
}

describe('CartService -- a cart opened on another channel', () => {
  beforeEach(() => localStorage.clear());

  it("opens the cart on the channel it is told, not on the build's own", async () => {
    const { api, delivery } = setUp();
    api.mutate.mockResolvedValue(cart());

    await delivery.create(LOCATION, 'DINE_IN', 'QRTABLE');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      `${BRAND_PATH}/carts`,
      expect.objectContaining({
        body: { locationId: LOCATION, channel: 'QRTABLE', fulfillmentMode: 'DINE_IN' },
      }),
    );
  });

  it("still opens on the build's own channel when none is given -- the delivery and pickup baskets are unchanged", async () => {
    const { api, delivery } = setUp();
    api.mutate.mockResolvedValue(cart({ fulfillmentMode: 'DELIVERY' }));

    await delivery.create(LOCATION, 'DELIVERY');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      `${BRAND_PATH}/carts`,
      expect.objectContaining({
        body: { locationId: LOCATION, channel: 'STOREFRONT', fulfillmentMode: 'DELIVERY' },
      }),
    );
  });

  it('ensure() carries the channel through when it has to open a cart', async () => {
    const { api, delivery } = setUp();
    api.mutate.mockResolvedValue(cart());

    await delivery.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE');

    expect(api.mutate.mock.calls[0][2].body.channel).toBe('QRTABLE');
  });
});

describe('DineInCartService -- the table basket, kept apart from the delivery basket', () => {
  beforeEach(() => localStorage.clear());

  it("is its own instance: the table's cart is never the delivery basket's cart", async () => {
    const { api, delivery, table } = setUp();
    api.mutate.mockResolvedValue(cart({ cartId: 'table-cart' }));

    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    expect(table.cart()?.cartId).toBe('table-cart');
    expect(delivery.cart()).toBeNull();
  });

  it("opens a DINE_IN cart on the table's own channel", async () => {
    const { api, table } = setUp();
    api.mutate.mockResolvedValue(cart());

    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      `${BRAND_PATH}/carts`,
      expect.objectContaining({
        body: { locationId: LOCATION, channel: 'QRTABLE', fulfillmentMode: 'DINE_IN' },
      }),
    );
  });

  it('remembers the cart against the table session and reloads it on the next visit to that session', async () => {
    const { api, table } = setUp();
    api.mutate.mockResolvedValue(cart({ cartId: 'table-cart' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    TestBed.resetTestingModule();
    const again = setUp();
    again.api.get.mockResolvedValue(cart({ cartId: 'table-cart', version: 4 }));
    const reloaded = await again.table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    expect(again.api.get).toHaveBeenCalledWith(`${BRAND_PATH}/carts/table-cart`);
    expect(reloaded?.version).toBe(4);
    expect(again.api.mutate).not.toHaveBeenCalled();
  });

  it("gives another table session a cart of its own rather than the earlier evening's basket", async () => {
    const { api, table } = setUp();
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'first-evening' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'second-evening' }));

    const second = await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-2');

    expect(second?.cartId).toBe('second-evening');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('does not create a cart for a browse: with create=false and nothing stored it answers null', async () => {
    const { api, table } = setUp();

    const result = await table.ensure(LOCATION, 'DINE_IN', false, 'QRTABLE', 'session-1');

    expect(result).toBeNull();
    expect(table.cart()).toBeNull();
    expect(api.mutate).not.toHaveBeenCalled();
  });

  it('starts again when the stored cart has since been checked out or expired', async () => {
    const { api, table } = setUp();
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'old' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');
    api.get.mockRejectedValueOnce(notFound());
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'fresh' }));

    const result = await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    expect(result?.cartId).toBe('fresh');
  });

  it('a delivery basket at the same location never picks up the table cart', async () => {
    const { api, delivery, table } = setUp();
    api.mutate.mockResolvedValue(cart({ cartId: 'table-cart' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    const result = await delivery.ensure(LOCATION, 'DELIVERY', false);

    // Nothing is stored under the delivery basket's key, so nothing is read.
    expect(result).toBeNull();
    expect(api.get).not.toHaveBeenCalled();
  });

  it('the table never picks up the delivery basket either, even at the same location', async () => {
    const { api, delivery, table } = setUp();
    api.mutate.mockResolvedValueOnce(
      cart({ cartId: 'delivery-cart', fulfillmentMode: 'DELIVERY' }),
    );
    await delivery.ensure(LOCATION, 'DELIVERY', true);
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'table-cart' }));

    const result = await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    expect(result?.cartId).toBe('table-cart');
    expect(api.get).not.toHaveBeenCalled();
  });

  it("discard() forgets the table cart and leaves the delivery basket's remembered id alone", async () => {
    const { api, delivery, table } = setUp();
    api.mutate.mockResolvedValueOnce(
      cart({ cartId: 'delivery-cart', fulfillmentMode: 'DELIVERY' }),
    );
    await delivery.ensure(LOCATION, 'DELIVERY', true);
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'table-cart' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');

    table.discard(LOCATION, 'session-1');

    expect(table.cart()).toBeNull();
    expect(localStorage.getItem(`horecaos_cart_${LOCATION}`)).toBe('delivery-cart');
    const afterwards = await table.ensure(LOCATION, 'DINE_IN', false, 'QRTABLE', 'session-1');
    expect(afterwards).toBeNull();
  });

  it('keeps writing to the cart it holds: a line goes to the table cart, not to the delivery basket', async () => {
    const { api, delivery, table } = setUp();
    delivery.cart.set(cart({ cartId: 'delivery-cart', fulfillmentMode: 'DELIVERY' }));
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'table-cart' }));
    await table.ensure(LOCATION, 'DINE_IN', true, 'QRTABLE', 'session-1');
    api.mutate.mockResolvedValueOnce(cart({ cartId: 'table-cart', version: 2 }));

    await table.putLine({ variantId: 'v1', quantity: 1 });

    const [method, path] = api.mutate.mock.calls.at(-1)!;
    expect(method).toBe('PUT');
    expect(path).toBe(`${BRAND_PATH}/carts/table-cart/lines/v1`);
    expect(delivery.cart()?.cartId).toBe('delivery-cart');
  });
});
