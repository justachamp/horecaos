import { TestBed } from '@angular/core/testing';

import { ReviewsService } from './reviews.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

class FakeApiClient {
  get = vi.fn();
  list = vi.fn();
  mutate = vi.fn();
}

function setUp(): { service: ReviewsService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(ReviewsService), api };
}

describe('ReviewsService.submit', () => {
  it('sends exactly rating and comment, at the order-scoped review path', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      id: 'r1',
      orderId: 'order-1',
      rating: 5,
      comment: 'Great',
      submittedAt: new Date().toISOString(),
    });

    await service.submit('order-1', 5, 'Great');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/orders/order-1/review'),
      expect.objectContaining({ body: { rating: 5, comment: 'Great' } }),
    );
  });

  it('omits comment entirely rather than sending an empty string', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      id: 'r1',
      orderId: 'order-1',
      rating: 4,
      comment: null,
      submittedAt: new Date().toISOString(),
    });

    await service.submit('order-1', 4, '   ');

    const body = api.mutate.mock.calls[0][2]?.body as Record<string, unknown>;
    expect(body['rating']).toBe(4);
    expect(body['comment']).toBeUndefined();
  });

  it('generates an idempotency key', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      id: 'r1',
      orderId: 'order-1',
      rating: 3,
      comment: null,
      submittedAt: new Date().toISOString(),
    });

    await service.submit('order-1', 3);

    expect(api.mutate.mock.calls[0][2]?.idempotencyKey).toBeTruthy();
  });
});

describe('ReviewsService.myReviews', () => {
  it('reads the brand-scoped reviews collection', async () => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [], nextCursor: null });

    await service.myReviews();

    expect(api.list).toHaveBeenCalledWith(
      expect.stringContaining('/reviews'),
      expect.objectContaining({ limit: 20 }),
    );
  });
});
