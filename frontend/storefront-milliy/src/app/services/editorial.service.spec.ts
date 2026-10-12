import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { NO_EDITORIAL } from '../types/editorial.types';
import { EditorialService } from './editorial.service';
import { LangService } from './lang.service';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.example.uz/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const BASE =
  '/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/content';

class FakeApiClient {
  get = vi.fn();
}
class FakeLangService {
  langId = () => 'ru';
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
      { provide: LangService, useClass: FakeLangService },
    ],
  });
  return { service: TestBed.inject(EditorialService), api };
}

const notFound = () =>
  new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'No such listing' });

const pageBody = () => ({
  items: [
    {
      slug: 'plov-week',
      title: 'Plov week',
      textLocale: 'ru',
      publishedAt: '2026-10-01T08:30:00Z',
      cover: {
        kind: 'IMAGE',
        renditions: [{ widthPx: 800, url: '/api/v1/storefront/tenants/t/media/a?variant=w800' }],
      },
    },
  ],
  nextCursor: 'next-1',
});

const detailBody = () => ({
  slug: 'plov-week',
  kind: 'NEWS',
  title: 'Plov week',
  textLocale: 'ru',
  publishedAt: '2026-10-01T08:30:00Z',
  blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Hello' }] }],
  images: [],
});

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 9, 9, 12, 0, 0));
});

afterEach(() => {
  vi.useRealTimers();
});

describe('EditorialService.loadFeatures', () => {
  it('reads which kinds the brand runs, anonymously and quietly', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ news: true, gallery: false, recipes: true, vacancies: false });

    await service.loadFeatures();

    expect(api.get).toHaveBeenCalledWith(`${BASE}/features`, { anonymous: true, silent: true });
    expect(service.features()).toEqual({
      news: true,
      gallery: false,
      recipes: true,
      vacancies: false,
    });
  });

  it('starts with every kind off', () => {
    const { service } = setUp();

    expect(service.features()).toEqual({ ...NO_EDITORIAL });
  });

  it.each([
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['a 404', () => Promise.reject(notFound())],
    ['a body that is text', () => Promise.resolve('<html>')],
  ])('turns every kind off after %s, and does not throw', async (_label, respond) => {
    const { service, api } = setUp();
    api.get.mockResolvedValueOnce({ news: true, gallery: true, recipes: true, vacancies: true });
    await service.loadFeatures();
    api.get.mockImplementationOnce(respond);

    await expect(service.loadFeatures({ force: true })).resolves.toBeUndefined();

    expect(service.features()).toEqual({ ...NO_EDITORIAL });
  });

  it('asks once inside the cache window, and again after it', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ news: true, gallery: false, recipes: false, vacancies: false });

    await service.loadFeatures();
    vi.advanceTimersByTime(30_000);
    await service.loadFeatures();
    expect(api.get).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(31_000);
    await service.loadFeatures();
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('shares one request between loads that overlap', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ news: true });

    await Promise.all([service.loadFeatures(), service.loadFeatures()]);

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('asks again right away after a failure', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ news: true });

    await service.loadFeatures();
    await service.loadFeatures();

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(service.features().news).toBe(true);
  });
});

describe('EditorialService.list', () => {
  it.each([
    ['news', 'news'],
    ['gallery', 'gallery'],
    ['recipes', 'recipes'],
    ['careers', 'careers'],
  ] as const)('reads the %s listing for this channel and language', async (kind, segment) => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(pageBody());

    await service.list(kind);

    expect(api.get).toHaveBeenCalledWith(`${BASE}/${segment}`, {
      query: { channel: 'STOREFRONT', locale: 'ru', cursor: undefined, limit: undefined },
      anonymous: true,
      silent: true,
    });
  });

  it('asks for the page after a cursor, with a limit', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(pageBody());

    await service.list('news', { cursor: 'next-1', limit: 12 });

    expect(api.get).toHaveBeenCalledWith(
      `${BASE}/news`,
      expect.objectContaining({
        query: { channel: 'STOREFRONT', locale: 'ru', cursor: 'next-1', limit: 12 },
      }),
    );
  });

  it('returns the page, with media put on the API origin', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(pageBody());

    const result = await service.list('news');

    expect(result.status).toBe('ok');
    if (result.status === 'ok') {
      expect(result.value.nextCursor).toBe('next-1');
      expect(result.value.items[0].cover?.renditions[0].url).toBe(
        'https://api.example.uz/api/v1/storefront/tenants/t/media/a?variant=w800',
      );
    }
  });

  it.each([
    [
      'a 404, which is also what a kind that is switched off answers',
      () => Promise.reject(notFound()),
    ],
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    [
      'a 500',
      () =>
        Promise.reject(new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' })),
    ],
    ['a body that is text', () => Promise.resolve('<html>')],
  ])('is "unavailable", and does not throw, after %s', async (_label, respond) => {
    const { service, api } = setUp();
    api.get.mockImplementation(respond);

    await expect(service.list('news')).resolves.toEqual({ status: 'unavailable' });
  });
});

describe('EditorialService.detail', () => {
  it('reads one entry by its slug, for this channel and language', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(detailBody());

    const result = await service.detail('careers', 'line-cook');

    expect(api.get).toHaveBeenCalledWith(`${BASE}/careers/line-cook`, {
      query: { channel: 'STOREFRONT', locale: 'ru' },
      anonymous: true,
      silent: true,
    });
    expect(result.status).toBe('ok');
  });

  it('puts a slug into the path as one segment, whatever it contains', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(detailBody());

    await service.detail('news', 'a/b?c=d#e');

    expect(api.get.mock.calls[0][0]).toBe(`${BASE}/news/a%2Fb%3Fc%3Dd%23e`);
  });

  it('returns the entry with its blocks read', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(detailBody());

    const result = await service.detail('news', 'plov-week');

    expect(result.status === 'ok' && result.value.blocks).toEqual([
      { type: 'PARAGRAPH', inlines: [{ text: 'Hello' }] },
    ]);
  });

  it.each([
    ['a 404', () => Promise.reject(notFound())],
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['a body that is not an entry', () => Promise.resolve({ nonsense: true })],
  ])('is "unavailable", and does not throw, after %s', async (_label, respond) => {
    const { service, api } = setUp();
    api.get.mockImplementation(respond);

    await expect(service.detail('news', 'plov-week')).resolves.toEqual({ status: 'unavailable' });
  });

  it('does not ask at all for an empty slug', async () => {
    const { service, api } = setUp();

    await expect(service.detail('news', '')).resolves.toEqual({ status: 'unavailable' });

    expect(api.get).not.toHaveBeenCalled();
  });
});
