import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable, firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { LocationScope } from '../../core/api/operations-paths';
import { InventoryApi } from './inventory-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

function firstValue<T>(source: Observable<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    source.subscribe({ next: resolve, error: reject });
  });
}

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/**
 * `InventoryController` is mapped at
 * `/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/inventory`
 * — never on the ADR 0031 `/api/v1/operations/**` prefix. Batch 11 built
 * `inventoryStockItems`/`inventoryVariantAvailability`/`inventoryBulkAvailability`
 * on the `/operations` prefix by mistake and every caller 404'd, because the
 * only specs that existed either mocked `InventoryApi` wholesale (so the real
 * URL was never built) or compared a request URL against this same builder's
 * own output (a comparison that cannot fail regardless of which prefix is
 * wrong). These tests exercise `InventoryApi` end to end through a real
 * `HttpClient` and a literal expected URL, so a regression here fails loudly.
 */
describe('InventoryApi', () => {
  let api: InventoryApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), InventoryApi],
    });
    api = TestBed.inject(InventoryApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('registers a stock item at the real, non-/operations InventoryController path', async () => {
    const promise = firstValue(api.registerStockItem(SCOPE, 'v1', 'QUANTITY'));
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/stock-items'),
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ variantId: 'v1', trackingMode: 'QUANTITY' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ stockItemId: 's1', trackingMode: 'QUANTITY' });
    await promise;
  });

  it('toggles availability with a PUT to the variant’s own /availability path', async () => {
    const promise = firstValue(api.setAvailability(SCOPE, 'v1', false, 'OUT_OF_STOCK'));
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/availability'),
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ available: false, reasonCode: 'OUT_OF_STOCK' });
    request.flush(null);
    await promise;
  });

  it('reads plain availability at the collection’s own /availability path, variantIds repeated', async () => {
    const promise = firstValue(api.availability(SCOPE, ['v1', 'v2']));
    const request = http.expectOne(
      (r) => r.url === url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/availability'),
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.params.getAll('variantIds')).toEqual(['v1', 'v2']);
    expect(request.request.params.has('channel')).toBe(false);
    request.flush({ available: true, unavailableItems: [] });
    const result = await promise;
    expect(result).toEqual({ available: true, unavailableItems: [] });
  });

  it('reads channel-aware availability on the same path, with the channel query param added', async () => {
    const promise = firstValue(api.availabilityForChannel(SCOPE, ['v1'], 'AGGREGATOR'));
    const request = http.expectOne(
      (r) => r.url === url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/availability'),
    );
    expect(request.request.params.getAll('variantIds')).toEqual(['v1']);
    expect(request.request.params.get('channel')).toBe('AGGREGATOR');
    request.flush({
      available: false,
      unavailableItems: [{ variantId: 'v1', reason: 'CHANNEL_STOPPED' }],
    });
    const result = await promise;
    expect(result.available).toBe(false);
  });

  it('lists every stock position at the location’s own /positions path', async () => {
    const promise = firstValue(api.listPositions(SCOPE));
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/positions'),
    );
    expect(request.request.method).toBe('GET');
    request.flush([]);
    await expect(promise).resolves.toEqual([]);
  });

  it('sets the on-hand count with a PUT to the variant’s own /on-hand path', async () => {
    const promise = firstValue(api.setOnHand(SCOPE, 'v1', 12, 'RECOUNT'));
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/on-hand'),
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ quantity: 12, reasonCode: 'RECOUNT' });
    request.flush(null);
    await promise;
  });

  it('sets the daily reset default with a PUT to the variant’s own /quantity-defaults path', async () => {
    const promise = firstValue(api.setQuantityDefault(SCOPE, 'v1', 20, 'DAILY_RESET_CHANGE'));
    const request = http.expectOne(
      url('/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/quantity-defaults'),
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ defaultQuantity: 20, reasonCode: 'DAILY_RESET_CHANGE' });
    request.flush(null);
    await promise;
  });

  it('sets a channel stop threshold with a PUT to the variant’s own /channel-stop-thresholds/{type} path', async () => {
    const promise = firstValue(
      api.setChannelStopThreshold(SCOPE, 'v1', 'AGGREGATOR', 3, 'PARTNER_REQUEST'),
    );
    const request = http.expectOne(
      url(
        '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/channel-stop-thresholds/AGGREGATOR',
      ),
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ stopAtOrBelow: 3, reasonCode: 'PARTNER_REQUEST' });
    request.flush(null);
    await promise;
  });

  it('reads a variant’s unlisted branches on the brand-scoped, non-/operations inventory-listing path', async () => {
    const promise = firstValue(api.unlistedLocations(SCOPE, 'v1'));
    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/variants/v1/inventory-listing'));
    expect(request.request.method).toBe('GET');
    request.flush({ locationIds: ['l2', 'l3'] });
    await expect(promise).resolves.toEqual(['l2', 'l3']);
  });

  it('backfills a variant’s listing with a POST to the same brand-scoped path GET reads', async () => {
    const promise = firstValue(api.backfillVariantListing(SCOPE, 'v1'));
    const request = http.expectOne(url('/api/v1/tenants/t1/brands/b1/variants/v1/inventory-listing'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ candidateCount: 2, listedCount: 2 });
    await expect(promise).resolves.toEqual({ candidateCount: 2, listedCount: 2 });
  });

  it('clears a channel stop threshold with a DELETE to the same path, reasonCode as a query param', async () => {
    const promise = firstValue(
      api.clearChannelStopThreshold(SCOPE, 'v1', 'AGGREGATOR', 'PARTNER_REQUEST'),
    );
    const request = http.expectOne(
      (r) =>
        r.url ===
        url(
          '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/channel-stop-thresholds/AGGREGATOR',
        ),
    );
    expect(request.request.method).toBe('DELETE');
    expect(request.request.params.get('reasonCode')).toBe('PARTNER_REQUEST');
    request.flush(null);
    await promise;
  });
});
