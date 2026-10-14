import { TestBed } from '@angular/core/testing';

import { PointsService } from './points.service';
import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  defaultLocationId: 'loc-1',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

class FakeApiClient {
  get = vi.fn();
}

function setUp(): { service: PointsService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(PointsService), api };
}

describe('PointsService.redemptionOffer', () => {
  it('reads the offer for the cart under this brand, and never toasts a failure', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ cartId: 'cart-9', priced: true });

    await service.redemptionOffer('cart-9');

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/tenant-1/brands/brand-1/carts/cart-9/redemption-offer',
      { quiet: true },
    );
  });

  it('hands a failure on to the caller, which decides what a missing offer means', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(
      new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' }),
    );

    await expect(service.redemptionOffer('cart-9')).rejects.toBeInstanceOf(HorecaOSApiError);
  });
});

describe('PointsService.paymentSplit', () => {
  it("reads the order's split under this brand", async () => {
    const { service, api } = setUp();
    const split = { orderId: 'o1', total: { amountMinor: 1, currency: 'UZS' }, tenders: [] };
    api.get.mockResolvedValue(split);

    await expect(service.paymentSplit('o1')).resolves.toBe(split);

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/tenant-1/brands/brand-1/orders/o1/payment-split',
      { quiet: true },
    );
  });

  it('reads an order the platform has no payment to show for as nothing to show, not as a failure', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(
      new HorecaOSApiError({
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
        detail: 'x',
        problem: { status: 404, reason: 'NO_PAYMENT_SPLIT' },
      }),
    );

    await expect(service.paymentSplit('o1')).resolves.toBeNull();
  });

  it('still reports every other failure', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(
      new HorecaOSApiError({ status: 503, code: 'INTERNAL_ERROR', detail: 'x' }),
    );

    await expect(service.paymentSplit('o1')).rejects.toBeInstanceOf(HorecaOSApiError);
  });
});
