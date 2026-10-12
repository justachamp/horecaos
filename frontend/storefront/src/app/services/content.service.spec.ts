import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ContentService } from './content.service';
import { LangService } from './lang.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.example.uz/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const HOME_PATH =
  '/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/content/home';

class FakeApiClient {
  get = vi.fn();
}

class FakeLangService {
  readonly langId = signal('uz');
}

function wireBanner(id: string) {
  return {
    id,
    kind: 'BANNER',
    revisionNo: 1,
    title: `Banner ${id}`,
    textLocale: 'uz-Latn',
    media: {
      kind: 'IMAGE',
      alt: 'x',
      renditions: [{ widthPx: 800, url: '/api/v1/storefront/tenants/t/media/a?variant=w800' }],
    },
    link: { type: 'NONE' },
  };
}

function document1(...ids: string[]) {
  return { locale: 'uz-Latn', carousel: ids.map(wireBanner) };
}

function setUp() {
  const api = new FakeApiClient();
  const lang = new FakeLangService();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: LangService, useValue: lang },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(ContentService), api, lang };
}

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('ContentService.load: the document is one request of its own', () => {
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('asks nothing until the page starts it', () => {
    const { api } = setUp();

    expect(api.get).not.toHaveBeenCalled();
  });

  it('reads the anonymous, quiet content/home with the channel and the page language', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));

    await service.load();

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith(HOME_PATH, {
      query: { channel: 'STOREFRONT', locale: 'uz' },
      anonymous: true,
      quiet: true,
    });
  });

  it('exposes the normalised document, with rendition paths put on the API origin', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a', 'b'));

    await service.load();

    expect(service.carousel().map((i) => i.id)).toEqual(['a', 'b']);
    const media = service.carousel()[0].media;
    expect(media.kind === 'IMAGE' && media.renditions[0].url).toBe(
      'https://api.example.uz/api/v1/storefront/tenants/t/media/a?variant=w800',
    );
  });

  it('starts empty: no carousel, groups, stories, grid or popup before anything is read', () => {
    const { service } = setUp();

    expect(service.home()).toBeNull();
    expect(service.carousel()).toEqual([]);
    expect(service.groups()).toEqual([]);
    expect(service.stories()).toEqual([]);
    expect(service.promoGrid()).toEqual([]);
    expect(service.popup()).toBeNull();
  });

  it('is once per session start: a second load() from the page re-entering home asks nothing', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));

    await service.load();
    await service.load();

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('two callers racing the start share the one request', async () => {
    const { service, api } = setUp();
    let resolve!: (body: unknown) => void;
    api.get.mockReturnValue(new Promise((r) => (resolve = r)));

    const first = service.load();
    const second = service.load();
    resolve(document1('a'));
    await Promise.all([first, second]);

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(service.carousel()).toHaveLength(1);
  });
});

describe('ContentService.load: a failed read is "no content" and never a throw', () => {
  it.each([
    ['a network failure', () => Promise.reject(new Error('network down'))],
    ['a 404', () => Promise.reject({ status: 404 })],
    ['a 500', () => Promise.reject({ status: 500 })],
    ['a body that is a string', () => Promise.resolve('<html>gateway</html>')],
    ['a body that is null', () => Promise.resolve(null)],
    ['a body that is an array', () => Promise.resolve([1, 2, 3])],
  ])('%s resolves to no content', async (_label, answer) => {
    const { service, api } = setUp();
    api.get.mockImplementation(answer);

    await expect(service.load()).resolves.toBeUndefined();

    expect(service.home()).toBeNull();
    expect(service.carousel()).toEqual([]);
    expect(service.popup()).toBeNull();
  });

  it('a request that throws synchronously is still no content', async () => {
    const { service, api } = setUp();
    api.get.mockImplementation(() => {
      throw new Error('boom');
    });

    await expect(service.load()).resolves.toBeUndefined();

    expect(service.home()).toBeNull();
  });
});

describe('ContentService: revalidation', () => {
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('re-reads when the language changes, in the new language', async () => {
    const { service, api, lang } = setUp();
    api.get.mockResolvedValue(document1('a'));
    await service.load();

    api.get.mockResolvedValue(document1('b'));
    lang.langId.set('ru');
    TestBed.tick();
    await vi.waitFor(() => expect(service.carousel().map((i) => i.id)).toEqual(['b']));

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.get.mock.calls[1][1].query).toEqual({ channel: 'STOREFRONT', locale: 'ru' });
  });

  it('does not read on a language change before the page has started it', () => {
    const { api, lang } = setUp();

    lang.langId.set('ru');
    TestBed.tick();

    expect(api.get).not.toHaveBeenCalled();
  });

  it('ignores a slow answer for the language the visitor has already left', async () => {
    const { service, api, lang } = setUp();
    let resolveFirst!: (body: unknown) => void;
    api.get.mockReturnValueOnce(new Promise((r) => (resolveFirst = r)));
    const first = service.load();

    api.get.mockResolvedValueOnce(document1('ru-doc'));
    lang.langId.set('ru');
    TestBed.tick();
    await vi.waitFor(() => expect(service.carousel().map((i) => i.id)).toEqual(['ru-doc']));

    resolveFirst(document1('uz-doc'));
    await first;

    expect(service.carousel().map((i) => i.id)).toEqual(['ru-doc']);
  });

  it('re-reads when the page becomes visible again after more than the 60 s cache window', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));
    await service.load();

    vi.setSystemTime(Date.now() + 61_000);
    api.get.mockResolvedValue(document1('b'));
    setVisibility('visible');
    await vi.waitFor(() => expect(service.carousel().map((i) => i.id)).toEqual(['b']));

    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('does not re-read when the page comes back inside the 60 s window, or while it goes hidden', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));
    await service.load();

    vi.setSystemTime(Date.now() + 30_000);
    setVisibility('visible');
    vi.setSystemTime(Date.now() + 120_000);
    setVisibility('hidden');
    await Promise.resolve();

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('a failed revalidation leaves no content, like any failed read', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));
    await service.load();

    vi.setSystemTime(Date.now() + 61_000);
    api.get.mockRejectedValue(new Error('offline'));
    setVisibility('visible');
    await vi.waitFor(() => expect(service.carousel()).toEqual([]));
  });

  it('keeps the same document object when a revalidation brings back the same content', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const { service, api } = setUp();
    api.get.mockResolvedValue(document1('a'));
    await service.load();
    const before = service.home();

    vi.setSystemTime(Date.now() + 61_000);
    setVisibility('visible');
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
    await Promise.resolve();

    expect(service.home()).toBe(before);
  });
});
