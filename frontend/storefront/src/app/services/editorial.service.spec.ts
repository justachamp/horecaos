import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { EditorialService } from './editorial.service';
import { LangService } from './lang.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.example.uz/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
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
  readonly langId = signal('ru');
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: LangService, useValue: new FakeLangService() },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(EditorialService), api };
}

const NONE = { news: false, gallery: false, recipes: false, vacancies: false };

describe('EditorialService.loadFeatures', () => {
  afterEach(() => vi.useRealTimers());

  it('starts with every kind off and asks nothing until it is told to', () => {
    const { service, api } = setUp();

    expect(service.features()).toEqual(NONE);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('reads the flags with the channel and language, anonymous and quiet', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ news: true, gallery: false, recipes: false, vacancies: true });

    await service.loadFeatures();

    expect(api.get).toHaveBeenCalledWith(`${BASE}/features`, {
      query: { channel: 'STOREFRONT', locale: 'ru' },
      anonymous: true,
      quiet: true,
    });
    expect(service.features()).toEqual({
      news: true,
      gallery: false,
      recipes: false,
      vacancies: true,
    });
  });

  it.each([
    ['a network failure', () => Promise.reject(new Error('offline'))],
    ['a 404', () => Promise.reject({ status: 404 })],
    ['a 500', () => Promise.reject({ status: 500 })],
    ['a body that is not a document', () => Promise.resolve('<html>')],
  ])('%s leaves every kind off and never throws', async (_label, answer) => {
    const { service, api } = setUp();
    api.get.mockImplementation(answer);

    await expect(service.loadFeatures()).resolves.toBeUndefined();

    expect(service.features()).toEqual(NONE);
  });

  it('a failed re-read turns the entry points back off', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValueOnce({ news: true });
    await service.loadFeatures();
    expect(service.features().news).toBe(true);

    vi.setSystemTime(Date.now() + 61_000);
    api.get.mockRejectedValueOnce(new Error('offline'));
    await service.loadFeatures();

    expect(service.features()).toEqual(NONE);
  });

  it('does not ask again inside the 60 s cache window, and does after it', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValue({ news: true });

    await service.loadFeatures();
    vi.setSystemTime(Date.now() + 30_000);
    await service.loadFeatures();
    expect(api.get).toHaveBeenCalledTimes(1);

    vi.setSystemTime(Date.now() + 40_000);
    await service.loadFeatures();
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('two callers racing share one request', async () => {
    const { service, api } = setUp();
    let resolve!: (body: unknown) => void;
    api.get.mockReturnValue(new Promise((r) => (resolve = r)));

    const first = service.loadFeatures();
    const second = service.loadFeatures();
    resolve({ news: true });
    await Promise.all([first, second]);

    expect(api.get).toHaveBeenCalledTimes(1);
  });
});

describe('EditorialService.list', () => {
  const page = {
    items: [
      {
        slug: 'a',
        title: 'A',
        textLocale: 'ru',
        publishedAt: '2026-10-01T09:00:00Z',
      },
    ],
    nextCursor: 'next-1',
  };

  it.each([
    ['news', 'news'],
    ['gallery', 'gallery'],
    ['recipes', 'recipes'],
    ['careers', 'careers'],
  ] as const)('reads %s from .../%s with the channel and language', async (kind, path) => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(page);

    await service.list(kind);

    expect(api.get).toHaveBeenCalledWith(`${BASE}/${path}`, {
      query: { channel: 'STOREFRONT', locale: 'ru', cursor: undefined, limit: undefined },
      anonymous: true,
      quiet: true,
    });
  });

  it('passes the cursor and the limit on', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(page);

    await service.list('news', { cursor: 'c-9', limit: 12 });

    expect(api.get.mock.calls[0][1].query).toMatchObject({ cursor: 'c-9', limit: 12 });
  });

  it('gives the normalised page', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(page);

    const result = await service.list('news');

    expect(result.status).toBe('ok');
    if (result.status === 'ok') {
      expect(result.data.items.map((i) => i.slug)).toEqual(['a']);
      expect(result.data.nextCursor).toBe('next-1');
    }
  });

  it.each([
    ['a 404 (the kind is off, or there is nothing)', () => Promise.reject({ status: 404 })],
    ['a 403', () => Promise.reject({ status: 403 })],
    ['a 500', () => Promise.reject({ status: 500 })],
    ['a network failure', () => Promise.reject(new Error('offline'))],
    ['a body that is not a page', () => Promise.resolve('<html>')],
  ])('%s is "unavailable", not a throw', async (_label, answer) => {
    const { service, api } = setUp();
    api.get.mockImplementation(answer);

    await expect(service.list('news')).resolves.toEqual({ status: 'unavailable' });
  });
});

describe('EditorialService.detail', () => {
  const doc = {
    slug: 'summer-menu',
    title: 'Summer menu',
    textLocale: 'ru',
    publishedAt: '2026-10-01T09:00:00Z',
    blocks: [{ type: 'DIVIDER' }],
    images: [],
  };

  it('reads .../{kind}/{slug}', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(doc);

    const result = await service.detail('recipes', 'summer-menu');

    expect(api.get).toHaveBeenCalledWith(`${BASE}/recipes/summer-menu`, {
      query: { channel: 'STOREFRONT', locale: 'ru' },
      anonymous: true,
      quiet: true,
    });
    expect(result.status).toBe('ok');
  });

  it('encodes the slug into the path: it is a segment, never a path of its own', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(doc);

    await service.detail('news', '../../etc/passwd');

    expect(api.get.mock.calls[0][0]).toBe(`${BASE}/news/..%2F..%2Fetc%2Fpasswd`);
  });

  it.each([
    ['a 404', () => Promise.reject({ status: 404 })],
    ['a 500', () => Promise.reject({ status: 500 })],
    ['a network failure', () => Promise.reject(new Error('offline'))],
    ['a body that is not a document', () => Promise.resolve({ blocks: [] })],
  ])('%s is "unavailable", not a throw', async (_label, answer) => {
    const { service, api } = setUp();
    api.get.mockImplementation(answer);

    await expect(service.detail('news', 'x')).resolves.toEqual({ status: 'unavailable' });
  });
});
