import { InjectionToken } from '@angular/core';

/** A browser's `PushSubscription.toJSON()`, narrowed to what the platform takes. */
export interface BrowserPushSubscription {
  readonly endpoint: string;
  readonly expirationTime: number | null;
  readonly keys: { readonly p256dh: string; readonly auth: string };
}

/**
 * The browser end of Web Push (ADR 0182, D14), behind a token so that a spec can stand in for a browser:
 * a service worker, a push manager and a permission dialog all need a real one, and jsdom has none of
 * them.
 */
export interface PushBrowser {
  /** Whether this browser can do Web Push at all: a service worker, the Push API and notifications. */
  readonly supported: boolean;
  /** The notification permission as the browser holds it; `'unsupported'` when it has none to hold. */
  permission(): NotificationPermission | 'unsupported';
  /** Asks the customer. Must be called from the tap that started the opt-in: a browser refuses otherwise. */
  requestPermission(): Promise<NotificationPermission>;
  /** The subscription this browser already holds under the storefront's worker, if any. */
  current(): Promise<BrowserPushSubscription | null>;
  /** Subscribes under the platform's application server key (base64url), replacing one made under another key. */
  subscribe(applicationServerKey: string): Promise<BrowserPushSubscription>;
  /** Drops the browser's own subscription. A browser that holds none is not an error. */
  unsubscribe(): Promise<void>;
}

/** Where the worker is served from: the storefront's root, so it can show a notification for any page. */
export const PUSH_WORKER_URL = '/push-sw.js';

/** A base64url string as the bytes `PushManager.subscribe` wants. */
export function applicationServerKeyBytes(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(base64Url.length / 4) * 4, '=');
  const raw = atob(padded);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i += 1) {
    bytes[i] = raw.charCodeAt(i);
  }
  return bytes;
}

function narrow(subscription: PushSubscription): BrowserPushSubscription {
  const json = subscription.toJSON();
  return {
    endpoint: json.endpoint ?? subscription.endpoint,
    expirationTime: json.expirationTime ?? null,
    keys: { p256dh: json.keys?.['p256dh'] ?? '', auth: json.keys?.['auth'] ?? '' },
  };
}

async function registration(): Promise<ServiceWorkerRegistration> {
  // `updateViaCache: 'none'` so that a new worker is picked up on the next visit without the web server
  // having to say so: the storefront's web server caches every script it serves for a year.
  await navigator.serviceWorker.register(PUSH_WORKER_URL, { scope: '/', updateViaCache: 'none' });
  return navigator.serviceWorker.ready;
}

/** The real thing, used everywhere but in a spec. */
export function browserPush(): PushBrowser {
  const supported =
    typeof navigator !== 'undefined' &&
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window;

  return {
    supported,
    permission: () => (supported ? Notification.permission : 'unsupported'),
    requestPermission: () => Notification.requestPermission(),
    current: async () => {
      const subscription = await (await registration()).pushManager.getSubscription();
      return subscription === null ? null : narrow(subscription);
    },
    subscribe: async (applicationServerKey) => {
      const manager = (await registration()).pushManager;
      const options = {
        userVisibleOnly: true,
        applicationServerKey: applicationServerKeyBytes(applicationServerKey),
      };
      try {
        return narrow(await manager.subscribe(options));
      } catch (failure) {
        // A subscription made under another application server key blocks a new one: the platform's key
        // was rotated since the customer subscribed. Dropping the old one and subscribing again is the
        // browser's own remedy, and the customer has just asked for exactly this.
        if (failure instanceof DOMException && failure.name === 'InvalidStateError') {
          await (await manager.getSubscription())?.unsubscribe();
          return narrow(await manager.subscribe(options));
        }
        throw failure;
      }
    },
    unsubscribe: async () => {
      const subscription = await (await registration()).pushManager.getSubscription();
      await subscription?.unsubscribe();
    },
  };
}

export const PUSH_BROWSER = new InjectionToken<PushBrowser>('PUSH_BROWSER', {
  providedIn: 'root',
  factory: browserPush,
});
