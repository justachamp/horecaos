import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { LocationScope } from '../../../core/api/operations-paths';
import { AggregatorOrderRequest, NewOrderApi, PlaceOrderRequest } from './new-order-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders`;

const REQUEST: PlaceOrderRequest = {
  customerAccountId: 'acct-1',
  channelCode: 'PHONE',
  fulfillmentMode: 'PICKUP',
  lines: [{ variantId: 'v-1', quantity: 1, modifierOptionIds: [] }],
  paymentMethodCode: 'CASH',
};

const AGGREGATOR_REQUEST: AggregatorOrderRequest = {
  channelCode: 'YANDEX',
  externalOrderId: 'ext-1',
  lines: [],
  currency: 'UZS',
  subtotalMinor: 10_000,
  discountMinor: 0,
  feeMinor: 0,
  totalMinor: 10_000,
};

/**
 * HK: `placeOrder`/`aggregatorEntry` create a brand-new order and carry no
 * `expectedVersion` — unlike every method in `order-actions-api.ts`, nothing
 * else stops a retry with a fresh Idempotency-Key from creating a second,
 * independent order. This was the critic's named reproduction
 * (new-order-api.ts:226): every mutating wrapper minted its key inline via
 * `command(request)` on each call, so an operator's manual retry after a lost
 * response was indistinguishable from placing a second order.
 */
describe('NewOrderApi Idempotency-Key stability', () => {
  let api: NewOrderApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), NewOrderApi],
    });
    api = TestBed.inject(NewOrderApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('reuses the same Idempotency-Key across a retried placeOrder() for the unchanged request', () => {
    void api.placeOrder(SCOPE, REQUEST);
    const first = http.expectOne(`${BASE}`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    expect(firstKey).toBeTruthy();

    // A retry before the first response arrived — the lost-response case.
    void api.placeOrder(SCOPE, REQUEST);
    const second = http.expectOne(`${BASE}`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);

    first.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    second.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
  });

  it('mints a fresh Idempotency-Key for placeOrder() once the operator edits the form before resubmitting', () => {
    void api.placeOrder(SCOPE, REQUEST);
    const first = http.expectOne(`${BASE}`);
    const firstKey = first.request.headers.get('Idempotency-Key');

    void api.placeOrder(SCOPE, { ...REQUEST, promoCode: 'WELCOME10' });
    const second = http.expectOne(`${BASE}`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);

    first.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    second.flush({
      orderId: 'o2',
      publicOrderNumber: '#0002',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
  });

  it('mints a fresh Idempotency-Key for the next placeOrder() once the previous one confirmed', () => {
    void api.placeOrder(SCOPE, REQUEST);
    const first = http.expectOne(`${BASE}`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });

    // A second, later order for the identical basket/customer (e.g. a repeat
    // phone call) must not replay the first order's response.
    void api.placeOrder(SCOPE, REQUEST);
    const second = http.expectOne(`${BASE}`);
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({
      orderId: 'o2',
      publicOrderNumber: '#0002',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
  });

  it('reuses the same Idempotency-Key across a retried aggregatorEntry() for the unchanged request', () => {
    void api.aggregatorEntry(SCOPE, AGGREGATOR_REQUEST);
    const first = http.expectOne(`${BASE}/aggregator-entries`);
    const firstKey = first.request.headers.get('Idempotency-Key');
    expect(firstKey).toBeTruthy();

    void api.aggregatorEntry(SCOPE, AGGREGATOR_REQUEST);
    const second = http.expectOne(`${BASE}/aggregator-entries`);
    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);

    first.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
    second.flush({
      orderId: 'o1',
      publicOrderNumber: '#0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'PLACED',
      warnings: [],
    });
  });
});
