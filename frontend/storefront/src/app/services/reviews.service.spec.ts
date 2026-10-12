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

function setUp(config: AppConfig = CONFIG): { service: ReviewsService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: config },
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

describe('ReviewsService -- tag icons (ADR 0159)', () => {
  const CROSS_ORIGIN: AppConfig = { ...CONFIG, apiBaseUrl: 'https://api.example.uz/api/v1' };
  const ICON = '/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/media/a-1';

  function offered(iconUrl: string | null) {
    return { id: 't1', code: 'tasty', category: 'FOOD', labels: { en: 'Tasty' }, iconUrl };
  }

  it('puts the icon of a tag the customer is offered on the origin the API is served from', async () => {
    const { service, api } = setUp(CROSS_ORIGIN);
    api.get.mockResolvedValue({
      enabled: true,
      maxTagsPerReview: 5,
      publicComments: false,
      consentTextKey: null,
      positive: [offered(ICON), offered(null)],
      negative: [offered(ICON)],
    });

    const config = await service.feedbackConfig();

    expect(config.positive.map((tag) => tag.iconUrl)).toEqual([
      `https://api.example.uz${ICON}`,
      null,
    ]);
    expect(config.negative[0].iconUrl).toBe(`https://api.example.uz${ICON}`);
  });

  it('does the same for the tags on the published comments', async () => {
    const { service, api } = setUp(CROSS_ORIGIN);
    api.get.mockResolvedValue({
      visibility: 'RATING_AND_COMMENTS',
      nextCursor: null,
      items: [
        {
          rating: 5,
          comment: 'Lovely',
          date: '2026-10-08',
          branchName: null,
          tags: [
            { code: 'tasty', category: 'FOOD', sentiment: 'POSITIVE', labels: {}, iconUrl: ICON },
          ],
        },
      ],
    });

    const page = await service.publicComments();

    expect(page.items[0].tags[0].iconUrl).toBe(`https://api.example.uz${ICON}`);
  });

  it('builds the address from the asset id on the customer’s own review, which carries no path', async () => {
    const { service, api } = setUp(CROSS_ORIGIN);
    const own = {
      id: 'r1',
      orderId: 'order-1',
      rating: 5,
      comment: null,
      submittedAt: '2026-10-08T10:00:00Z',
      tags: [
        {
          id: 't1',
          code: 'tasty',
          category: 'FOOD',
          sentiment: 'POSITIVE',
          labels: {},
          iconAssetId: 'a-1',
        },
        {
          id: 't2',
          code: 'fast',
          category: 'SERVICE',
          sentiment: 'POSITIVE',
          labels: {},
          iconAssetId: null,
        },
      ],
    };
    api.list.mockResolvedValue({ items: [own], nextCursor: null });
    api.mutate.mockResolvedValue(own);

    const listed = await service.myReviews();
    const submitted = await service.submit('order-1', 5);

    for (const review of [listed.items[0], submitted]) {
      expect(review.tags?.map((tag) => tag.iconUrl)).toEqual([
        `https://api.example.uz${ICON}`,
        null,
      ]);
    }
  });

  it('shows no icon for a path that is not the platform’s own', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      enabled: true,
      maxTagsPerReview: 5,
      publicComments: false,
      consentTextKey: null,
      positive: [offered('https://evil.example/x.svg'), offered('//evil.example/x.svg')],
      negative: [],
    });

    const config = await service.feedbackConfig();

    expect(config.positive.map((tag) => tag.iconUrl)).toEqual([null, null]);
  });
});
