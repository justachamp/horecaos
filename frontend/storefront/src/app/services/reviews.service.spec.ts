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

describe('ReviewsService.submit -- tags and the public-display box (ADR 0159)', () => {
  const review = {
    id: 'r1',
    orderId: 'order-1',
    rating: 1,
    comment: 'cold',
    submittedAt: new Date().toISOString(),
  };

  it('sends the tags tapped and the ticked box, and nothing else besides the rating and the words', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(review);

    await service.submit('order-1', 1, 'cold', { tagIds: ['tag-1', 'tag-2'], publicConsent: true });

    const body = api.mutate.mock.calls[0][2]?.body as Record<string, unknown>;
    expect(body).toEqual(
      expect.objectContaining({
        rating: 1,
        comment: 'cold',
        tagIds: ['tag-1', 'tag-2'],
        publicConsent: true,
      }),
    );
    expect(
      Object.keys(body)
        .filter((key) => body[key] !== undefined)
        .sort(),
    ).toEqual(['comment', 'publicConsent', 'rating', 'tagIds']);
  });

  it('leaves an empty tag list and an unticked box out of the request, which is what the server defaults to', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue(review);

    await service.submit('order-1', 4, 'good', { tagIds: [], publicConsent: false });

    const body = api.mutate.mock.calls[0][2]?.body as Record<string, unknown>;
    expect(body['tagIds']).toBeUndefined();
    expect(body['publicConsent']).toBeUndefined();
  });
});

describe('ReviewsService -- the feedback surface (ADR 0159)', () => {
  it('reads the feedback config without a bearer token: it is the same for everybody', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ enabled: false });

    await service.feedbackConfig();

    expect(api.get).toHaveBeenCalledWith(
      expect.stringContaining('/feedback/config'),
      expect.objectContaining({ anonymous: true }),
    );
  });

  it('reads the customer’s own pending orders with the customer’s token', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue([]);

    await service.pending();

    expect(api.get.mock.calls[0][0]).toContain('/reviews/pending');
    expect(api.get.mock.calls[0][1]).toBeUndefined();
  });

  it('withdraws the public display of one review with an idempotency key and no body', async () => {
    const { service, api } = setUp();
    api.mutate.mockResolvedValue({
      state: 'HIDDEN',
      reasonCode: 'CUSTOMER_WITHDREW',
      withdrawable: false,
    });

    const display = await service.withdrawPublicDisplay('review-9');

    expect(api.mutate).toHaveBeenCalledWith(
      'POST',
      expect.stringContaining('/reviews/review-9/public-display/withdrawal'),
      expect.objectContaining({ idempotencyKey: expect.any(String) }),
    );
    expect(api.mutate.mock.calls[0][2]?.body).toBeUndefined();
    expect(display.state).toBe('HIDDEN');
  });

  it('reads the public summary and the public comments anonymously, the comments from the cursor given', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ ready: false });

    await service.publicSummary();
    await service.publicComments('3', 5);

    expect(api.get.mock.calls[0][0]).toContain('/reviews/public/summary');
    expect(api.get.mock.calls[0][1]).toEqual(expect.objectContaining({ anonymous: true }));
    expect(api.get.mock.calls[1][0]).toMatch(/\/reviews\/public$/);
    expect(api.get.mock.calls[1][1]).toEqual(
      expect.objectContaining({ anonymous: true, query: { cursor: '3', limit: 5 } }),
    );
  });

  it('sends no cursor for the first page of comments', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ items: [], nextCursor: null });

    await service.publicComments();

    expect(api.get.mock.calls[0][1]?.query).toEqual({ cursor: undefined, limit: 10 });
  });
});
