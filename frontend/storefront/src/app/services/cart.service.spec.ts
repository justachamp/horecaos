import { TestBed } from '@angular/core/testing';

import {
  CartService,
  lineKeyFor,
  modifierOptionIdsFromLineKey,
  optionIdsOfLine,
  type PlatformCart,
  type PricedCart,
} from './cart.service';
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

/** `ApiClient` is stubbed directly: `CartService`'s own logic (retry-once on
 * a stale version, how it builds requests) is what these tests exercise, and
 * the HTTP wiring underneath `ApiClient` is already covered by
 * `api-client.spec.ts` and `api.interceptors.spec.ts`. */
class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
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

function setUp(): { service: CartService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(CartService), api };
}

function staleVersion(currentVersion = 2): HorecaOSApiError {
  return new HorecaOSApiError({
    status: 409,
    code: 'STALE_VERSION',
    detail: 'moved',
    problem: { status: 409, code: 'STALE_VERSION', currentVersion },
  });
}

describe('CartService (withVersion, via putLine)', () => {
  it('on success first try, calls mutate once and never reloads the cart', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const updated = baseCart({
      version: 2,
      lines: [
        {
          lineKey: 'v1',
          variantId: 'v1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    api.mutate.mockResolvedValue(updated);

    const result = await service.putLine({ variantId: 'v1', quantity: 1 });

    expect(result).toEqual(updated);
    expect(service.cart()).toEqual(updated);
    expect(api.mutate).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('row 2.1b: sends the given commentPresetCodes through in the request body', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({ variantId: 'v1', quantity: 1, commentPresetCodes: ['NO_ONIONS'] });

    expect(api.mutate).toHaveBeenCalledWith(
      'PUT',
      expect.any(String),
      expect.objectContaining({
        body: expect.objectContaining({ commentPresetCodes: ['NO_ONIONS'] }),
      }),
    );
  });

  it('row 2.1b: defaults commentPresetCodes to an empty list rather than omitting it — an omitted field would strip whatever the customer already picked, the same failure `modifierOptionIds` guards against', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({ variantId: 'v1', quantity: 2 });

    expect(api.mutate).toHaveBeenCalledWith(
      'PUT',
      expect.any(String),
      expect.objectContaining({ body: expect.objectContaining({ commentPresetCodes: [] }) }),
    );
  });

  it('reloads the cart and retries exactly once on STALE_VERSION, then succeeds', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const refreshed = baseCart({ version: 2 });
    const succeeded = baseCart({
      version: 3,
      lines: [
        {
          lineKey: 'v1',
          variantId: 'v1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    api.mutate.mockRejectedValueOnce(staleVersion(2)).mockResolvedValueOnce(succeeded);
    api.get.mockResolvedValueOnce(refreshed);

    const result = await service.putLine({ variantId: 'v1', quantity: 1 });

    expect(result).toEqual(succeeded);
    expect(service.cart()).toEqual(succeeded);
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.mutate).toHaveBeenCalledTimes(2);
    // The retry is against the version the reload just returned, not the
    // stale one the first attempt was holding.
    expect(api.mutate.mock.calls[1][2]?.expectedVersion).toBe(2);
  });

  it('never loops: a second STALE_VERSION on the retry is thrown, not retried again', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const refreshed = baseCart({ version: 2 });
    const secondFailure = staleVersion(3);
    api.mutate.mockRejectedValueOnce(staleVersion(2)).mockRejectedValueOnce(secondFailure);
    api.get.mockResolvedValueOnce(refreshed);

    await expect(service.putLine({ variantId: 'v1', quantity: 1 })).rejects.toBe(secondFailure);

    // Exactly one reload and exactly two mutate attempts -- not a third.
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.mutate).toHaveBeenCalledTimes(2);
  });

  it('a non-stale-version failure is thrown immediately, with no reload and no retry', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const other = new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' });
    api.mutate.mockRejectedValue(other);

    await expect(service.putLine({ variantId: 'v1', quantity: 1 })).rejects.toBe(other);

    expect(api.get).not.toHaveBeenCalled();
    expect(api.mutate).toHaveBeenCalledTimes(1);
  });

  it('a plain (non-HorecaOSApiError) failure is thrown immediately too', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const boom = new Error('network died');
    api.mutate.mockRejectedValue(boom);

    await expect(service.putLine({ variantId: 'v1', quantity: 1 })).rejects.toBe(boom);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('refuses to write when there is no cart held at all', async () => {
    const { service } = setUp();

    await expect(service.putLine({ variantId: 'v1', quantity: 1 })).rejects.toThrow(
      'There is no cart to write to.',
    );
  });
});

describe('CartService.selectPaymentMethod (ADR 0140)', () => {
  it('PUTs the code to the cart with the held version, and adopts the returned cart', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 4 }));
    const updated = baseCart({ version: 5, paymentMethodCode: 'CLICK' });
    api.mutate.mockResolvedValue(updated);

    const result = await service.selectPaymentMethod('CLICK');

    expect(result).toEqual(updated);
    expect(service.cart()).toEqual(updated);
    expect(api.mutate).toHaveBeenCalledWith(
      'PUT',
      expect.stringContaining('/carts/cart-1/payment-method'),
      expect.objectContaining({
        body: { paymentMethodCode: 'CLICK' },
        expectedVersion: 4,
        idempotencyKey: expect.any(String),
      }),
    );
  });

  it('sends null to clear the selection', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 2, paymentMethodCode: 'CLICK' }));
    api.mutate.mockResolvedValue(baseCart({ version: 3, paymentMethodCode: null }));

    await service.selectPaymentMethod(null);

    expect(api.mutate.mock.calls[0][2]?.body).toEqual({ paymentMethodCode: null });
  });

  it('retries once on STALE_VERSION, against the reloaded version', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate
      .mockRejectedValueOnce(staleVersion(2))
      .mockResolvedValueOnce(baseCart({ version: 3, paymentMethodCode: 'CLICK' }));
    api.get.mockResolvedValueOnce(baseCart({ version: 2 }));

    await service.selectPaymentMethod('CLICK');

    expect(api.mutate.mock.calls[1][2]?.expectedVersion).toBe(2);
  });
});

describe('CartService.bindTable (ADR 0047)', () => {
  it("puts to the cart's table sub-resource with the version and the guest token header, and no body naming a table", async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 4, fulfillmentMode: 'DINE_IN' }));
    api.mutate.mockResolvedValue(baseCart({ version: 5, fulfillmentMode: 'DINE_IN' }));

    await service.bindTable('guest-token-9');

    expect(api.mutate).toHaveBeenCalledTimes(1);
    const [method, path, options] = api.mutate.mock.calls[0];
    expect(method).toBe('PUT');
    expect(path).toBe(
      `/storefront/tenants/${CONFIG.tenantId}/brands/${CONFIG.brandId}/carts/cart-1/table`,
    );
    expect(options.expectedVersion).toBe(4);
    expect(options.headers).toEqual({ 'X-Dine-In-Token': 'guest-token-9' });
    expect(options.body).toBeUndefined();
    expect(options.idempotencyKey).toEqual(expect.any(String));
    expect(service.cart()?.version).toBe(5);
  });

  it('retries once on a stale version, exactly like every other write to the cart', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1, fulfillmentMode: 'DINE_IN' }));
    api.mutate
      .mockRejectedValueOnce(staleVersion(3))
      .mockResolvedValueOnce(baseCart({ version: 4, fulfillmentMode: 'DINE_IN' }));
    api.get.mockResolvedValue(baseCart({ version: 3, fulfillmentMode: 'DINE_IN' }));

    await service.bindTable('guest-token-9');

    expect(api.mutate).toHaveBeenCalledTimes(2);
    expect(api.mutate.mock.calls[1][2].expectedVersion).toBe(3);
    expect(api.mutate.mock.calls[1][2].headers).toEqual({ 'X-Dine-In-Token': 'guest-token-9' });
  });
});

describe('CartService.checkout', () => {
  const priced: PricedCart = {
    cartId: 'cart-1',
    cartVersion: 3,
    quoteId: 'quote-1',
    contextHash: 'hash-1',
    currency: 'UZS',
    subtotalMinor: 1000,
    taxMinor: 0,
    discountMinor: 0,
    feeMinor: 0,
    totalMinor: 1000,
    expiresAt: new Date().toISOString(),
    delivery: null,
  };

  it('sends the given paymentMethodCode through to the checkout body', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({ orderId: 'o1', outcome: 'CREATED' });

    await service.checkout({ priced, paymentMethodCode: 'CLICK', idempotencyKey: 'k-1' });

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/checkouts'),
      expect.objectContaining({
        body: expect.objectContaining({ paymentMethodCode: 'CLICK' }),
        idempotencyKey: 'k-1',
      }),
    );
  });

  it("sends a table guest's token as X-Dine-In-Token, so the platform can re-prove the table the cart is bound to", async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({ orderId: 'o1', outcome: 'CREATED' });

    await service.checkout({
      priced,
      paymentMethodCode: 'CASH',
      idempotencyKey: 'k-3',
      guestToken: 'guest-token-9',
    });

    expect(api.mutate.mock.calls[0][2].headers).toEqual({ 'X-Dine-In-Token': 'guest-token-9' });
    expect(api.mutate.mock.calls[0][2].body).not.toHaveProperty('guestToken');
  });

  it('sends no dine-in header when there is no table token', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({ orderId: 'o1', outcome: 'CREATED' });

    await service.checkout({ priced, paymentMethodCode: 'CASH', idempotencyKey: 'k-4' });

    expect(api.mutate.mock.calls[0][2].headers).toBeUndefined();
  });

  it('is a required, pass-through field: nothing here defaults it when missing', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({ orderId: 'o1', outcome: 'CREATED' });

    // The TS type makes this a compile error at every real call site; casting
    // past it here is how the runtime behaviour without it is observed --
    // there is no fallback to CASH or anything else hiding underneath.
    await service.checkout({
      priced,
      paymentMethodCode: undefined as unknown as string,
      idempotencyKey: 'k-2',
    });

    const call = api.mutate.mock.calls[0];
    const body = call[2]?.body as Record<string, unknown>;
    expect(body['paymentMethodCode']).toBeUndefined();
    expect('paymentMethodCode' in body).toBe(true);
  });
});

describe('lineKeyFor', () => {
  it('is just the variantId with no modifiers', () => {
    expect(lineKeyFor('v1', [])).toBe('v1');
  });

  it('joins a single modifier with a "+"', () => {
    expect(lineKeyFor('v1', ['m1'])).toBe('v1+m1');
  });

  it('sorts modifier ids so selection order never matters', () => {
    expect(lineKeyFor('v1', ['m2', 'm1', 'm3'])).toBe('v1+m1.m2.m3');
    expect(lineKeyFor('v1', ['m3', 'm1', 'm2'])).toBe('v1+m1.m2.m3');
    expect(lineKeyFor('v1', ['m1', 'm2', 'm3'])).toBe('v1+m1.m2.m3');
  });

  it('does not mutate the caller-supplied array while sorting', () => {
    const ids = ['b', 'a'];
    lineKeyFor('v1', ids);
    expect(ids).toEqual(['b', 'a']);
  });
});

describe('modifierOptionIdsFromLineKey (inverse of lineKeyFor)', () => {
  it('reads no modifiers back from a bare variant key', () => {
    expect(modifierOptionIdsFromLineKey('v1', 'v1')).toEqual([]);
  });

  it('reads modifiers back from a composed key', () => {
    expect(modifierOptionIdsFromLineKey('v1+m1.m2', 'v1')).toEqual(['m1', 'm2']);
  });

  it('degrades to no modifiers for a key this client did not mint, rather than throwing', () => {
    expect(modifierOptionIdsFromLineKey('someone-elses-key', 'v1')).toEqual([]);
  });

  it.each([
    [
      'aaaaaaaa-0000-0000-0000-000000000001',
      ['bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002'],
    ],
    ['aaaaaaaa-0000-0000-0000-000000000002', ['cccccccc-0000-0000-0000-000000000001']],
    ['aaaaaaaa-0000-0000-0000-000000000003', []],
    [
      'aaaaaaaa-0000-0000-0000-000000000004',
      [
        'dddddddd-0000-0000-0000-000000000003',
        'dddddddd-0000-0000-0000-000000000001',
        'dddddddd-0000-0000-0000-000000000002',
      ],
    ],
  ] as const)(
    'round-trips variant %s with selection %j through encode -> decode',
    (variantId, ids) => {
      const key = lineKeyFor(variantId, ids);
      const decoded = modifierOptionIdsFromLineKey(key, variantId);

      // The key sorts, so the round trip is compared against a sorted copy --
      // decode does not (and cannot) recover the original selection order.
      expect(decoded).toEqual([...ids].sort());
    },
  );
});

describe('combo lines (ADR 0136)', () => {
  const VARIANT = '3f2b8c1e-0000-4000-8000-000000000001';
  const picks = [
    { componentId: 'c-1', quantity: 1 },
    { componentId: 'c-2', quantity: 2 },
  ];

  it('keys a combo line by its container and a short hash, within the sixty characters the platform allows', () => {
    const key = lineKeyFor(VARIANT, [], picks);

    expect(key.startsWith(`${VARIANT}c`)).toBe(true);
    expect(key.length).toBeLessThanOrEqual(60);
    expect(key).not.toContain('~');
  });

  it('gives the same combo the same key in any order, and another choice another key', () => {
    const reordered = [...picks].reverse();

    expect(lineKeyFor(VARIANT, [], reordered)).toBe(lineKeyFor(VARIANT, [], picks));
    expect(lineKeyFor(VARIANT, [], [{ componentId: 'c-1', quantity: 1 }])).not.toBe(
      lineKeyFor(VARIANT, [], picks),
    );
  });

  it('leaves the key of a line with no picks exactly as it was', () => {
    expect(lineKeyFor(VARIANT, [])).toBe(VARIANT);
    expect(lineKeyFor(VARIANT, ['m1'])).toBe(`${VARIANT}+m1`);
    expect(lineKeyFor(VARIANT, [], [])).toBe(VARIANT);
  });

  it('puts a combo with its picks: the container as the variant, the picks in the body, the hashed key in the path', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({ variantId: VARIANT, quantity: 2, comboPicks: picks });

    const [method, path, options] = api.mutate.mock.calls[0];
    expect(method).toBe('PUT');
    expect(path).toContain(encodeURIComponent(lineKeyFor(VARIANT, [], picks)));
    expect(options.body).toMatchObject({ variantId: VARIANT, quantity: 2, comboPicks: picks });
  });

  it('sends no comboPicks at all for an ordinary line', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({ variantId: 'v1', quantity: 1 });

    expect(api.mutate.mock.calls[0][2].body).not.toHaveProperty('comboPicks');
  });
});

describe('lines with second-level choices (ADR 0136)', () => {
  const VARIANT = '3f2b8c1e-0000-4000-8000-000000000001';
  const CHILI = '3f2b8c1e-0000-4000-8000-0000000000a1';
  const GARLIC = '3f2b8c1e-0000-4000-8000-0000000000a2';
  const nested = [{ parentOptionId: CHILI, optionId: '3f2b8c1e-0000-4000-8000-0000000000b1' }];

  it('keys such a line by its variant and a short hash, within the sixty characters the platform allows', () => {
    const key = lineKeyFor(VARIANT, [CHILI, GARLIC], [], nested);

    expect(key.startsWith(`${VARIANT}n`)).toBe(true);
    expect(key.length).toBeLessThanOrEqual(60);
    expect(key).not.toContain('~');
  });

  it('gives the same selection the same key in any order, and another answer another key', () => {
    const other = [{ parentOptionId: CHILI, optionId: '3f2b8c1e-0000-4000-8000-0000000000b2' }];

    expect(lineKeyFor(VARIANT, [GARLIC, CHILI], [], nested)).toBe(
      lineKeyFor(VARIANT, [CHILI, GARLIC], [], nested),
    );
    expect(lineKeyFor(VARIANT, [CHILI], [], other)).not.toBe(
      lineKeyFor(VARIANT, [CHILI], [], nested),
    );
    expect(lineKeyFor(VARIANT, [CHILI, GARLIC], [], nested)).not.toBe(
      lineKeyFor(VARIANT, [CHILI], [], nested),
    );
  });

  it('leaves the key of a line with no second-level answer exactly as it was', () => {
    expect(lineKeyFor(VARIANT, [CHILI])).toBe(`${VARIANT}+${CHILI}`);
    expect(lineKeyFor(VARIANT, [CHILI], [], [])).toBe(`${VARIANT}+${CHILI}`);
  });

  it('puts the answers in the body and the hashed key in the path', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({
      variantId: VARIANT,
      quantity: 1,
      modifierOptionIds: [CHILI],
      nestedModifiers: nested,
    });

    const [method, path, options] = api.mutate.mock.calls[0];
    expect(method).toBe('PUT');
    expect(path).toContain(encodeURIComponent(lineKeyFor(VARIANT, [CHILI], [], nested)));
    expect(options.body).toMatchObject({
      variantId: VARIANT,
      modifierOptionIds: [CHILI],
      nestedModifiers: nested,
    });
  });

  it('sends no nestedModifiers at all for an ordinary line', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    api.mutate.mockResolvedValue(baseCart({ version: 2 }));

    await service.putLine({ variantId: 'v1', quantity: 1, modifierOptionIds: ['m1'] });

    expect(api.mutate.mock.calls[0][2].body).not.toHaveProperty('nestedModifiers');
  });
});

describe('optionIdsOfLine', () => {
  const line = (extra: Record<string, unknown>) => ({
    lineKey: 'v1+m1.m2',
    variantId: 'v1',
    quantity: 1,
    hasCustomerNote: false,
    commentPresetCodes: [],
    ...extra,
  });

  it("reads the options off the cart's own echo of the line when it has one", () => {
    expect(
      optionIdsOfLine(line({ lineKey: 'v1n0123456789abcd', modifierOptionIds: ['m9'] })),
    ).toEqual(['m9']);
  });

  it('says a line holds nothing when the echo is an empty list, whatever its key looks like', () => {
    expect(optionIdsOfLine(line({ modifierOptionIds: [] }))).toEqual([]);
  });

  it('falls back to the key it minted for a cart from a platform that does not echo the options', () => {
    expect(optionIdsOfLine(line({}))).toEqual(['m1', 'm2']);
  });
});
