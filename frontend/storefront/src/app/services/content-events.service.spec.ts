import { TestBed } from '@angular/core/testing';

import { ContentEventsService } from './content-events.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const EVENTS_PATH =
  '/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/content/events';

class FakeApiClient {
  report = vi.fn(async (_path: string, _body: unknown): Promise<void> => {});
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(ContentEventsService), api };
}

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

type Body = { channel: string; events: { itemId: string; revisionNo: number; event: string }[] };
const bodyOf = (call: readonly unknown[]) => call[1] as Body;

describe('ContentEventsService: what is reported', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('batches the events into one anonymous POST after a short delay', async () => {
    const { service, api } = setUp();

    service.impression('a', 1);
    service.click('b', 2);
    expect(api.report).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(3000);

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(api.report.mock.calls[0][0]).toBe(EVENTS_PATH);
    expect(bodyOf(api.report.mock.calls[0])).toEqual({
      channel: 'STOREFRONT',
      events: [
        { itemId: 'a', revisionNo: 1, event: 'IMPRESSION' },
        { itemId: 'b', revisionNo: 2, event: 'CLICK' },
      ],
    });
  });

  it('sends nothing but the channel and the events: no identifier of any kind', async () => {
    const { service, api } = setUp();

    service.impression('a', 1);
    await service.flush();

    const body = bodyOf(api.report.mock.calls[0]);
    expect(Object.keys(body).sort()).toEqual(['channel', 'events']);
    expect(Object.keys(body.events[0]).sort()).toEqual(['event', 'itemId', 'revisionNo']);
  });

  it('counts an impression once per item and revision per page session', async () => {
    const { service, api } = setUp();

    service.impression('a', 1);
    service.impression('a', 1);
    service.impression('a', 1);
    await service.flush();
    service.impression('a', 1);
    await service.flush();

    expect(api.report).toHaveBeenCalledTimes(1);
    expect(bodyOf(api.report.mock.calls[0]).events).toHaveLength(1);
  });

  it('counts the same item again when its revision is new, and another item separately', async () => {
    const { service, api } = setUp();

    service.impression('a', 1);
    service.impression('a', 2);
    service.impression('b', 1);
    await service.flush();

    expect(bodyOf(api.report.mock.calls[0]).events).toHaveLength(3);
  });

  it('does not de-duplicate clicks, dismissals or completions', async () => {
    const { service, api } = setUp();

    service.click('a', 1);
    service.click('a', 1);
    service.dismissal('p', 1);
    service.completion('s', 1);
    service.completion('s', 1);
    await service.flush();

    expect(bodyOf(api.report.mock.calls[0]).events.map((e) => e.event)).toEqual([
      'CLICK',
      'CLICK',
      'DISMISSAL',
      'COMPLETION',
      'COMPLETION',
    ]);
  });

  it('chunks a large queue into batches of at most 20', async () => {
    const { service, api } = setUp();

    for (let i = 0; i < 45; i++) {
      service.impression(`item-${i}`, 1);
    }
    await service.flush();

    expect(api.report.mock.calls.map((c) => bodyOf(c).events.length)).toEqual([20, 20, 5]);
  });

  it('posts nothing when there is nothing to say', async () => {
    const { service, api } = setUp();

    await service.flush();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(api.report).not.toHaveBeenCalled();
  });

  it('does not send the same event twice (a flush empties the queue)', async () => {
    const { service, api } = setUp();

    service.click('a', 1);
    await service.flush();
    await service.flush();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(api.report).toHaveBeenCalledTimes(1);
  });
});

describe('ContentEventsService: when it flushes', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('flushes at once when the page goes hidden', async () => {
    const { service, api } = setUp();

    service.click('a', 1);
    setVisibility('hidden');
    await Promise.resolve();

    expect(api.report).toHaveBeenCalledTimes(1);
  });

  it('does not flush when the page comes back to visible', async () => {
    const { service, api } = setUp();

    service.click('a', 1);
    setVisibility('visible');
    await Promise.resolve();

    expect(api.report).not.toHaveBeenCalled();
  });

  it('flushes on pagehide', async () => {
    const { service, api } = setUp();

    service.click('a', 1);
    window.dispatchEvent(new Event('pagehide'));
    await Promise.resolve();

    expect(api.report).toHaveBeenCalledTimes(1);
  });
});

describe('ContentEventsService: a failure is swallowed', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('a rejected POST neither throws nor is retried', async () => {
    const { service, api } = setUp();
    api.report.mockRejectedValue(new Error('429'));

    service.click('a', 1);
    await expect(service.flush()).resolves.toBeUndefined();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(api.report).toHaveBeenCalledTimes(1);
  });

  it('a request that throws synchronously is swallowed as well', async () => {
    const { service, api } = setUp();
    api.report.mockImplementation(() => {
      throw new Error('sync');
    });

    service.click('a', 1);

    await expect(service.flush()).resolves.toBeUndefined();
  });

  it('one failed chunk does not stop the next one', async () => {
    const { service, api } = setUp();
    api.report.mockRejectedValueOnce(new Error('first chunk failed'));

    for (let i = 0; i < 25; i++) {
      service.click(`item-${i}`, 1);
    }
    await service.flush();

    expect(api.report).toHaveBeenCalledTimes(2);
  });
});
