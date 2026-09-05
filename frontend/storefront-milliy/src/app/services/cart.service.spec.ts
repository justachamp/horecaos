import { TestBed } from '@angular/core/testing';

import {
  CartService,
  lineKeyFor,
  modifierOptionIdsFromLineKey,
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
      lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
    });
    api.mutate.mockResolvedValue(updated);

    const result = await service.putLine({ variantId: 'v1', quantity: 1 });

    expect(result).toEqual(updated);
    expect(service.cart()).toEqual(updated);
    expect(api.mutate).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('reloads the cart and retries exactly once on STALE_VERSION, then succeeds', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const refreshed = baseCart({ version: 2 });
    const succeeded = baseCart({
      version: 3,
      lines: [{ lineKey: 'v1', variantId: 'v1', quantity: 1, hasCustomerNote: false }],
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

describe('CartService.applyPromoCode / removePromoCode', () => {
  it('applyPromoCode sends the code and the held version, and adopts the returned cart', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 4 }));
    const updated = baseCart({ version: 5, appliedPromoCode: 'OSH2026' });
    api.mutate.mockResolvedValue(updated);

    const result = await service.applyPromoCode('osh2026');

    expect(result).toEqual(updated);
    expect(service.cart()).toEqual(updated);
    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/promo-code'),
      expect.objectContaining({ body: { code: 'osh2026' }, expectedVersion: 4 }),
    );
  });

  it('applyPromoCode retries once on STALE_VERSION, against the reloaded version', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 1 }));
    const refreshed = baseCart({ version: 2 });
    const succeeded = baseCart({ version: 3, appliedPromoCode: 'OSH2026' });
    api.mutate.mockRejectedValueOnce(staleVersion(2)).mockResolvedValueOnce(succeeded);
    api.get.mockResolvedValueOnce(refreshed);

    const result = await service.applyPromoCode('OSH2026');

    expect(result).toEqual(succeeded);
    expect(api.mutate.mock.calls[1][2]?.expectedVersion).toBe(2);
  });

  it('applyPromoCode refuses to write when there is no cart held at all', async () => {
    const { service } = setUp();

    await expect(service.applyPromoCode('OSH2026')).rejects.toThrow(
      'There is no cart to write to.',
    );
  });

  it('removePromoCode sends a DELETE with the held version and adopts the returned cart', async () => {
    const { service, api } = setUp();
    service.cart.set(baseCart({ version: 4, appliedPromoCode: 'OSH2026' }));
    const updated = baseCart({ version: 5, appliedPromoCode: null });
    api.mutate.mockResolvedValue(updated);

    const result = await service.removePromoCode();

    expect(result).toEqual(updated);
    expect(api.mutate).toHaveBeenCalledWith(
      'DELETE',
      expect.stringContaining('/promo-code'),
      expect.objectContaining({ expectedVersion: 4 }),
    );
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
    totalMinor: 1000,
    discountMinor: 0,
    expiresAt: new Date().toISOString(),
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
  ] as const)('round-trips variant %s with selection %j through encode -> decode', (variantId, ids) => {
    const key = lineKeyFor(variantId, ids);
    const decoded = modifierOptionIdsFromLineKey(key, variantId);

    // The key sorts, so the round trip is compared against a sorted copy --
    // decode does not (and cannot) recover the original selection order.
    expect(decoded).toEqual([...ids].sort());
  });
});
