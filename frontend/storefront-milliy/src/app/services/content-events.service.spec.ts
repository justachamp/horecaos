import { TestBed } from '@angular/core/testing';

import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { ContentEventsService } from './content-events.service';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.example.uz/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const EVENTS_URL =
  'https://api.example.uz/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/content/events';

const ITEM = '20000000-0000-0000-0000-000000000001';
const OTHER = '20000000-0000-0000-0000-000000000002';

let fetchMock: ReturnType<typeof vi.fn>;

function setUp() {
  TestBed.configureTestingModule({ providers: [{ provide: APP_CONFIG, useValue: CONFIG }] });
  return TestBed.inject(ContentEventsService);
}

function bodies(): {
  channel: string;
  events: { itemId: string; revisionNo: number; event: string }[];
}[] {
  return fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string));
}

beforeEach(() => {
  vi.useFakeTimers();
  fetchMock = vi.fn(() => Promise.resolve(new Response(null, { status: 202 })));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  Reflect.deleteProperty(document, 'visibilityState');
});

describe('ContentEventsService: anonymous counters', () => {
  it('sends what happened on a short timer, as one batch for the channel', async () => {
    const events = setUp();

    events.report(ITEM, 2, 'IMPRESSION');
    events.report(OTHER, 1, 'CLICK');
    expect(fetchMock).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(EVENTS_URL);
    expect(bodies()[0]).toEqual({
      channel: 'STOREFRONT',
      events: [
        { itemId: ITEM, revisionNo: 2, event: 'IMPRESSION' },
        { itemId: OTHER, revisionNo: 1, event: 'CLICK' },
      ],
    });
  });

  it('is a plain anonymous POST: no cookie, no idempotency key, no token, no referrer, nothing that names the visitor', async () => {
    const events = setUp();

    events.report(ITEM, 2, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('omit');
    expect(init.referrerPolicy).toBe('no-referrer');
    expect(Object.keys(init.headers as Record<string, string>)).toEqual(['Content-Type']);
    expect(Object.keys(bodies()[0]).sort()).toEqual(['channel', 'events']);
    expect(Object.keys(bodies()[0].events[0]).sort()).toEqual(['event', 'itemId', 'revisionNo']);
  });

  it('reports an impression once per item and revision in a page session', async () => {
    const events = setUp();

    events.report(ITEM, 2, 'IMPRESSION');
    events.report(ITEM, 2, 'IMPRESSION');
    events.report(ITEM, 3, 'IMPRESSION');
    await vi.advanceTimersByTimeAsync(5_000);
    events.report(ITEM, 2, 'IMPRESSION');
    await vi.advanceTimersByTimeAsync(5_000);

    expect(bodies()).toHaveLength(1);
    expect(bodies()[0].events.map((event) => event.revisionNo)).toEqual([2, 3]);
  });

  it('reports every click, and a dismissal and a completion', async () => {
    const events = setUp();

    events.report(ITEM, 2, 'CLICK');
    events.report(ITEM, 2, 'CLICK');
    events.report(ITEM, 2, 'DISMISSAL');
    events.report(ITEM, 2, 'COMPLETION');
    await vi.advanceTimersByTimeAsync(5_000);

    expect(bodies()[0].events.map((event) => event.event)).toEqual([
      'CLICK',
      'CLICK',
      'DISMISSAL',
      'COMPLETION',
    ]);
  });

  it('sends a batch of at most 20, in as many requests as it takes', async () => {
    const events = setUp();

    for (let n = 1; n <= 45; n += 1) {
      events.report(`30000000-0000-0000-0000-${String(n).padStart(12, '0')}`, 1, 'IMPRESSION');
    }
    await vi.advanceTimersByTimeAsync(5_000);

    expect(bodies().map((body) => body.events.length)).toEqual([20, 20, 5]);
  });

  it('sends what it has when the page is hidden, without waiting for the timer', () => {
    const events = setUp();
    events.report(ITEM, 2, 'CLICK');

    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((fetchMock.mock.calls[0][1] as RequestInit).keepalive).toBe(true);
  });

  it('sends what it has on pagehide', () => {
    const events = setUp();
    events.report(ITEM, 2, 'CLICK');

    window.dispatchEvent(new Event('pagehide'));

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('sends nothing when there is nothing to send', async () => {
    setUp();

    window.dispatchEvent(new Event('pagehide'));
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not send the same event twice when the timer and a page hide both come', async () => {
    const events = setUp();
    events.report(ITEM, 2, 'CLICK');

    window.dispatchEvent(new Event('pagehide'));
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('ignores what it cannot file: no id, or a revision below one', async () => {
    const events = setUp();

    events.report('', 2, 'CLICK');
    events.report(ITEM, 0, 'CLICK');
    events.report(ITEM, 1.5, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('swallows a failed send: a rejected request, an error status and a missing fetch', async () => {
    const events = setUp();

    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    events.report(ITEM, 2, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 429 }));
    events.report(ITEM, 3, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    fetchMock.mockImplementationOnce(() => {
      throw new Error('blocked');
    });
    events.report(ITEM, 4, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    vi.stubGlobal('fetch', undefined);
    events.report(ITEM, 5, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not queue what failed again: the counters are best effort', async () => {
    const events = setUp();
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    events.report(ITEM, 2, 'CLICK');
    await vi.advanceTimersByTimeAsync(5_000);
    await vi.advanceTimersByTimeAsync(5_000);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('stops listening when it is destroyed', () => {
    const events = setUp();
    events.report(ITEM, 2, 'CLICK');

    TestBed.resetTestingModule();
    window.dispatchEvent(new Event('pagehide'));

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
