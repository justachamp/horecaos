import { applicationServerKeyBytes, browserPush } from './push-browser';

describe('applicationServerKeyBytes', () => {
  it('reads a base64url key as the bytes PushManager.subscribe wants', () => {
    // 0xfb 0xff 0xbf is "-_-_" in base64url and "+/+/" in base64: the two alphabets differ here.
    expect(Array.from(applicationServerKeyBytes('-_-_'))).toEqual([0xfb, 0xff, 0xbf]);
  });

  it('accepts a key without padding, as the platform serves it', () => {
    // 65 octets, the length of an uncompressed P-256 point, is 87 characters unpadded.
    const key = btoa(String.fromCharCode(...new Array(65).fill(4)))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    const bytes = applicationServerKeyBytes(key);

    expect(key).toHaveLength(87);
    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(4);
  });
});

describe('browserPush in a browser with no service worker', () => {
  it('says it is unsupported instead of throwing, so a webview renders nothing', () => {
    const browser = browserPush();

    expect(browser.supported).toBe(false);
    expect(browser.permission()).toBe('unsupported');
  });
});

describe('browserPush reading and dropping a subscription', () => {
  const SUBSCRIPTION_JSON = {
    endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
    expirationTime: null,
    keys: { p256dh: 'p256dh-key', auth: 'auth-secret' },
  };

  function stubBrowser(registration: unknown) {
    const register = vi.fn();
    const getRegistration = vi.fn().mockResolvedValue(registration);
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: { register, getRegistration, ready: Promise.resolve(registration) },
    });
    vi.stubGlobal('PushManager', class {});
    vi.stubGlobal('Notification', { permission: 'default' });
    return { register, getRegistration };
  }

  function registrationHolding(subscription: unknown) {
    return { pushManager: { getSubscription: vi.fn().mockResolvedValue(subscription) } };
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    Reflect.deleteProperty(navigator, 'serviceWorker');
  });

  it('reads the subscription this browser holds without installing a worker', async () => {
    const held = { ...SUBSCRIPTION_JSON, toJSON: () => SUBSCRIPTION_JSON, unsubscribe: vi.fn() };
    const { register } = stubBrowser(registrationHolding(held));

    expect(await browserPush().current()).toEqual(SUBSCRIPTION_JSON);
    expect(register).not.toHaveBeenCalled();
  });

  it('says it holds none when no worker was ever installed, and does not install one to find out', async () => {
    const { register } = stubBrowser(undefined);

    expect(await browserPush().current()).toBeNull();
    expect(register).not.toHaveBeenCalled();
  });

  it('drops the subscription it holds without installing a worker', async () => {
    const unsubscribe = vi.fn().mockResolvedValue(true);
    const held = { ...SUBSCRIPTION_JSON, toJSON: () => SUBSCRIPTION_JSON, unsubscribe };
    const { register } = stubBrowser(registrationHolding(held));

    await browserPush().unsubscribe();

    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(register).not.toHaveBeenCalled();
  });

  it('has nothing to drop, and says nothing, when no worker was ever installed', async () => {
    const { register } = stubBrowser(undefined);

    await expect(browserPush().unsubscribe()).resolves.toBeUndefined();
    expect(register).not.toHaveBeenCalled();
  });
});
