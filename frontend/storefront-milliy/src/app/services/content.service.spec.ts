import { TestBed } from '@angular/core/testing';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';
import { NO_CONTENT } from '../types/content.types';
import { ContentService } from './content.service';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.example.uz/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const HOME_URL =
  '/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/content/home';

class FakeApiClient {
  get = vi.fn();
}

function documentBody(title = 'Plov week') {
  return {
    locale: 'uz-Latn',
    carousel: [
      {
        id: '20000000-0000-0000-0000-000000000001',
        kind: 'BANNER',
        revisionNo: 2,
        title,
        textLocale: 'uz-Latn',
        media: {
          kind: 'IMAGE',
          alt: 'Plov',
          renditions: [{ widthPx: 800, url: '/api/v1/storefront/tenants/t/media/a?variant=w800' }],
        },
        link: { type: 'NONE' },
      },
    ],
    groups: [
      {
        id: '30000000-0000-0000-0000-000000000001',
        revisionNo: 1,
        title: 'Popular',
        textLocale: 'uz-Latn',
        productIds: ['40000000-0000-0000-0000-000000000001'],
      },
    ],
  };
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(ContentService), api };
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 9, 9, 12, 0, 0));
});

afterEach(() => {
  vi.useRealTimers();
  Reflect.deleteProperty(document, 'visibilityState');
});

describe('ContentService: the read', () => {
  it('reads the home document anonymously, quietly, for this brand, channel and language', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());

    await service.load('ru');

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith(HOME_URL, {
      query: { channel: 'STOREFRONT', locale: 'ru' },
      anonymous: true,
      silent: true,
    });
  });

  it('exposes the document, with media put on the API origin', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());

    await service.load('uz');

    expect(service.carousel()).toHaveLength(1);
    expect(service.carousel()[0].title).toBe('Plov week');
    expect(service.carousel()[0].media.renditions[0].url).toBe(
      'https://api.example.uz/api/v1/storefront/tenants/t/media/a?variant=w800',
    );
    expect(service.groups()).toHaveLength(1);
    expect(service.stories()).toEqual([]);
    expect(service.promoGrid()).toEqual([]);
    expect(service.popup()).toBeNull();
  });

  it('starts with no content, before anything has been read', () => {
    const { service } = setUp();

    expect(service.home()).toEqual({ ...NO_CONTENT });
  });
});

describe('ContentService: a failed read is "no content", never an error', () => {
  const failures: [string, () => Promise<unknown>][] = [
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    [
      'a 404',
      () =>
        Promise.reject(
          new HorecaOSApiError({
            status: 404,
            code: 'RESOURCE_NOT_FOUND',
            detail: 'No such brand',
          }),
        ),
    ],
    [
      'a 500',
      () =>
        Promise.reject(
          new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'boom' }),
        ),
    ],
    ['a body that is text', () => Promise.resolve('<html>gateway timeout</html>')],
    ['an empty body', () => Promise.resolve(null)],
    ['a body that is a list', () => Promise.resolve([1, 2])],
  ];

  it.each(failures)(
    'after %s the page has no content and nothing is thrown',
    async (_label, respond) => {
      const { service, api } = setUp();
      api.get.mockImplementation(respond);

      await expect(service.load('uz')).resolves.toBeUndefined();

      expect(service.home()).toEqual({ ...NO_CONTENT });
    },
  );

  it('drops content it had when the next read fails', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValueOnce(documentBody());
    await service.load('uz');
    expect(service.carousel()).toHaveLength(1);

    api.get.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await service.load('uz', { force: true });

    expect(service.carousel()).toEqual([]);
  });

  it('tries again on the next load after a failure, without waiting for the cache window', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(documentBody());

    await service.load('uz');
    await service.load('uz');

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(service.carousel()).toHaveLength(1);
  });

  it('skips an item of a kind it does not know and keeps the rest', async () => {
    const { service, api } = setUp();
    const body = documentBody();
    api.get.mockResolvedValue({
      ...body,
      carousel: [{ ...body.carousel[0], id: 'x', kind: 'HOLOGRAM' }, body.carousel[0]],
    });

    await service.load('uz');

    expect(service.carousel()).toHaveLength(1);
  });
});

describe('ContentService: when it reads again', () => {
  it('reads once per session start: a second load inside the cache window asks nobody', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());

    await service.load('uz');
    vi.advanceTimersByTime(30_000);
    await service.load('uz');

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('shares one request between loads that overlap', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());

    await Promise.all([service.load('uz'), service.load('uz')]);

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('reads again once the cache window has passed', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    vi.advanceTimersByTime(61_000);
    await service.load('uz');

    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('reads again when the language changes', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    await service.load('ru');

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.get).toHaveBeenLastCalledWith(
      HOME_URL,
      expect.objectContaining({ query: { channel: 'STOREFRONT', locale: 'ru' } }),
    );
  });

  it('reads again when asked to', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    await service.load('uz', { force: true });

    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('keeps the answer for the language the visitor is on when an older one arrives late', async () => {
    const { service, api } = setUp();
    let releaseOld!: (body: unknown) => void;
    api.get
      .mockImplementationOnce(() => new Promise((resolve) => (releaseOld = resolve)))
      .mockResolvedValueOnce(documentBody('Новая неделя'));

    const old = service.load('uz');
    await service.load('ru');
    releaseOld(documentBody('Eski hafta'));
    await old;

    expect(service.carousel()[0].title).toBe('Новая неделя');
  });
});

describe('ContentService: revalidation when the page becomes visible again', () => {
  it('reads again when the page is shown after more than the cache window', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    vi.advanceTimersByTime(61_000);
    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(0);

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.get).toHaveBeenLastCalledWith(
      HOME_URL,
      expect.objectContaining({ query: { channel: 'STOREFRONT', locale: 'uz' } }),
    );
  });

  it('does not read again when the page is shown within the cache window', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    vi.advanceTimersByTime(20_000);
    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(0);

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('does not read when the page is hidden', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    vi.advanceTimersByTime(120_000);
    setVisibility('hidden');
    await vi.advanceTimersByTimeAsync(0);

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('has nothing to revalidate before the home screen ever asked', async () => {
    const { api } = setUp();
    TestBed.inject(ContentService);

    vi.advanceTimersByTime(120_000);
    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(0);

    expect(api.get).not.toHaveBeenCalled();
  });

  it('stops listening when it is destroyed', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(documentBody());
    await service.load('uz');

    TestBed.resetTestingModule();
    vi.advanceTimersByTime(120_000);
    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(0);

    expect(api.get).toHaveBeenCalledTimes(1);
  });
});
