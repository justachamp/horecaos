import { TestBed } from '@angular/core/testing';

import { PushSubscriptionService } from './push-subscription.service';
import { PushApi, type PushStatus } from '../core/api/push-api';
import { HorecaOSApiError } from '../core/api/problem-details';
import {
  PUSH_BROWSER,
  type BrowserPushSubscription,
  type PushBrowser,
} from '../core/push/push-browser';

class FakePushApi {
  status = vi.fn<() => Promise<PushStatus>>();
  subscribe = vi.fn<(body: unknown, key?: string) => Promise<void>>().mockResolvedValue(undefined);
  unsubscribe = vi.fn<(key?: string) => Promise<void>>().mockResolvedValue(undefined);
}

const SUBSCRIPTION: BrowserPushSubscription = {
  endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
  expirationTime: null,
  keys: { p256dh: 'p256dh-key', auth: 'auth-secret' },
};

function browser(overrides: Partial<PushBrowser> = {}): PushBrowser & {
  requestPermission: ReturnType<typeof vi.fn>;
  subscribe: ReturnType<typeof vi.fn>;
  unsubscribe: ReturnType<typeof vi.fn>;
} {
  return {
    supported: true,
    permission: vi.fn().mockReturnValue('default'),
    requestPermission: vi.fn().mockResolvedValue('granted'),
    current: vi.fn().mockResolvedValue(null),
    subscribe: vi.fn().mockResolvedValue(SUBSCRIPTION),
    unsubscribe: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  } as PushBrowser & {
    requestPermission: ReturnType<typeof vi.fn>;
    subscribe: ReturnType<typeof vi.fn>;
    unsubscribe: ReturnType<typeof vi.fn>;
  };
}

function setUp(pushBrowser = browser()) {
  const api = new FakePushApi();
  api.status.mockResolvedValue({ available: true, publicKey: 'vapid-key', subscribed: false });
  TestBed.configureTestingModule({
    providers: [
      { provide: PushApi, useValue: api },
      { provide: PUSH_BROWSER, useValue: pushBrowser },
    ],
  });
  return { service: TestBed.inject(PushSubscriptionService), api, pushBrowser };
}

function notFound(): HorecaOSApiError {
  return new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no account' });
}

describe('PushSubscriptionService.refresh', () => {
  it('is unknown before the first read', () => {
    expect(setUp().service.state()).toBe('unknown');
  });

  it('is unsupported in a browser with no Web Push, and asks the platform nothing', async () => {
    const { service, api } = setUp(browser({ supported: false }));

    expect(await service.refresh()).toBe('unsupported');
    expect(api.status).not.toHaveBeenCalled();
  });

  it('is unavailable until the platform can send push at all', async () => {
    const { service, api } = setUp();
    api.status.mockResolvedValue({ available: false, publicKey: null, subscribed: false });

    expect(await service.refresh()).toBe('unavailable');
  });

  it('treats a guest -- signed in, no account at this brand yet -- as unavailable, not a failure', async () => {
    const { service, api } = setUp();
    api.status.mockRejectedValue(notFound());

    expect(await service.refresh()).toBe('unavailable');
  });

  it('rethrows a failure that is not a missing account', async () => {
    const { service, api } = setUp();
    const failure = new HorecaOSApiError({
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      detail: 'offline',
    });
    api.status.mockRejectedValue(failure);

    await expect(service.refresh()).rejects.toBe(failure);
  });

  it('is off when the customer has not turned it on, on when they have, blocked when the browser refuses', async () => {
    const { service, api, pushBrowser } = setUp();
    expect(await service.refresh()).toBe('off');

    api.status.mockResolvedValue({ available: true, publicKey: 'vapid-key', subscribed: true });
    expect(await service.refresh()).toBe('on');

    api.status.mockResolvedValue({ available: true, publicKey: 'vapid-key', subscribed: false });
    (pushBrowser.permission as ReturnType<typeof vi.fn>).mockReturnValue('denied');
    expect(await service.refresh()).toBe('blocked');
  });

  it('never asks the browser for permission just by reading', async () => {
    const { service, pushBrowser } = setUp();

    await service.refresh();

    expect(pushBrowser.requestPermission).not.toHaveBeenCalled();
    expect(pushBrowser.subscribe).not.toHaveBeenCalled();
  });
});

describe('PushSubscriptionService.enable', () => {
  it('asks the browser, subscribes under the platform’s key, and tells the platform with a fresh key', async () => {
    const { service, api, pushBrowser } = setUp();
    await service.refresh();

    const state = await service.enable();

    expect(state).toBe('on');
    expect(pushBrowser.requestPermission).toHaveBeenCalledTimes(1);
    expect(pushBrowser.subscribe).toHaveBeenCalledWith('vapid-key');
    expect(api.subscribe).toHaveBeenCalledWith(SUBSCRIPTION, expect.any(String));
    expect(service.state()).toBe('on');
  });

  it('records nothing when the customer says no to the browser, and is blocked when the browser says never', async () => {
    const { service, api, pushBrowser } = setUp();
    await service.refresh();
    pushBrowser.requestPermission.mockResolvedValue('denied');

    expect(await service.enable()).toBe('blocked');
    expect(api.subscribe).not.toHaveBeenCalled();
    expect(pushBrowser.subscribe).not.toHaveBeenCalled();

    pushBrowser.requestPermission.mockResolvedValue('default');
    expect(await service.enable()).toBe('off');
  });

  it('drops the browser’s subscription when the platform refuses it, so nothing is left subscribed that the platform does not know', async () => {
    const { service, api, pushBrowser } = setUp();
    await service.refresh();
    const refused = new HorecaOSApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'PUSH_ENDPOINT_NOT_ALLOWED',
    });
    api.subscribe.mockRejectedValue(refused);

    await expect(service.enable()).rejects.toBe(refused);

    expect(pushBrowser.unsubscribe).toHaveBeenCalledTimes(1);
    expect(service.state()).toBe('off');
  });

  it('does nothing before the platform has said what key to subscribe under', async () => {
    const { service, pushBrowser } = setUp();

    expect(await service.enable()).toBe('unknown');
    expect(pushBrowser.requestPermission).not.toHaveBeenCalled();
  });

  it('cannot be started twice by a double tap', async () => {
    const { service, pushBrowser } = setUp();
    await service.refresh();
    let release: (value: NotificationPermission) => void = () => undefined;
    pushBrowser.requestPermission.mockReturnValue(
      new Promise<NotificationPermission>((resolve) => {
        release = resolve;
      }),
    );

    const first = service.enable();
    const second = service.enable();
    release('granted');
    await Promise.all([first, second]);

    expect(pushBrowser.requestPermission).toHaveBeenCalledTimes(1);
    expect(service.busy()).toBe(false);
  });
});

describe('PushSubscriptionService.disable', () => {
  it('tells the platform first, then drops the browser’s own subscription', async () => {
    const { service, api, pushBrowser } = setUp();
    api.status.mockResolvedValue({ available: true, publicKey: 'vapid-key', subscribed: true });
    await service.refresh();

    const state = await service.disable();

    expect(state).toBe('off');
    expect(api.unsubscribe).toHaveBeenCalledWith(expect.any(String));
    expect(pushBrowser.unsubscribe).toHaveBeenCalledTimes(1);
    expect(api.unsubscribe.mock.invocationCallOrder[0]).toBeLessThan(
      pushBrowser.unsubscribe.mock.invocationCallOrder[0],
    );
  });

  it('stays on, and says so, when the platform cannot be reached: the customer asked to be off, and is not', async () => {
    const { service, api, pushBrowser } = setUp();
    api.status.mockResolvedValue({ available: true, publicKey: 'vapid-key', subscribed: true });
    await service.refresh();
    const failure = new HorecaOSApiError({
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      detail: 'offline',
    });
    api.unsubscribe.mockRejectedValue(failure);

    await expect(service.disable()).rejects.toBe(failure);

    expect(service.state()).toBe('on');
    expect(pushBrowser.unsubscribe).not.toHaveBeenCalled();
  });
});

describe('PushSubscriptionService.forget', () => {
  it('returns to unknown when the session ends', async () => {
    const { service } = setUp();
    await service.refresh();

    service.forget();

    expect(service.state()).toBe('unknown');
  });
});
