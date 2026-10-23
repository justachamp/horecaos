import '../../../../public/push-sw.js';

/**
 * The storefront's Web Push service worker (ADR 0182, D14), run in jsdom.
 *
 * A service worker cannot be started here, but it is a plain script over `self`, and in jsdom `self` is
 * the window. Importing the script registers its listeners on it; each spec gives the window the few
 * things a worker scope has (a registration, clients, `skipWaiting`) as recorded fakes, dispatches the
 * events a browser would, and awaits what the worker passed to `waitUntil`. What is under test is what
 * the worker does with a push and a tap: the notification it shows, where it reports a tap, where it
 * sends the customer, and what it refuses to be talked into.
 */
interface WorkerScope {
  registration: { showNotification: ReturnType<typeof vi.fn> };
  clients: {
    claim: ReturnType<typeof vi.fn>;
    matchAll: ReturnType<typeof vi.fn>;
    openWindow: ReturnType<typeof vi.fn>;
  };
  skipWaiting: ReturnType<typeof vi.fn>;
}

const scope = window as unknown as WorkerScope;
const ORIGIN = window.location.origin;

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  scope.registration = { showNotification: vi.fn().mockResolvedValue(undefined) };
  scope.clients = {
    claim: vi.fn().mockResolvedValue(undefined),
    matchAll: vi.fn().mockResolvedValue([]),
    openWindow: vi.fn().mockResolvedValue(null),
  };
  scope.skipWaiting = vi.fn();
  fetchMock = vi.fn().mockResolvedValue({});
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Dispatches an event the way a browser would, and returns what the worker asked to wait for. */
async function dispatch(type: string, fields: Record<string, unknown> = {}): Promise<void> {
  const pending: Promise<unknown>[] = [];
  const event = Object.assign(new Event(type), fields, {
    waitUntil: (promise: Promise<unknown>) => {
      pending.push(promise);
    },
  });
  window.dispatchEvent(event);
  await Promise.all(pending);
}

const push = (payload: unknown) =>
  dispatch('push', { data: payload === undefined ? null : { json: () => payload } });

function tap(data: unknown): { close: ReturnType<typeof vi.fn>; fired: Promise<void> } {
  const close = vi.fn();
  return { close, fired: dispatch('notificationclick', { notification: { close, data } }) };
}

describe('push-sw.js', () => {
  it('takes over at once when it is installed, and claims its clients when it activates', async () => {
    await dispatch('install');
    await dispatch('activate');

    expect(scope.skipWaiting).toHaveBeenCalled();
    expect(scope.clients.claim).toHaveBeenCalled();
  });

  it('shows the promotion the platform pushed: its title, text, cover and replacement tag', async () => {
    await push({
      title: 'Aksiya',
      body: 'Chegirma 20%',
      image: 'https://api.example/media/a?variant=w800',
      path: '/category/pizza',
      click: 'https://api.example/providers/push/clicks/token',
      tag: 'topic-1',
    });

    expect(scope.registration.showNotification).toHaveBeenCalledWith('Aksiya', {
      body: 'Chegirma 20%',
      icon: '/favicon.ico',
      tag: 'topic-1',
      data: { path: '/category/pizza', click: 'https://api.example/providers/push/clicks/token' },
      image: 'https://api.example/media/a?variant=w800',
    });
  });

  it('does not show a cover that is not an http(s) address', async () => {
    await push({ title: 'Aksiya', image: 'javascript:alert(1)', path: '/' });

    expect(scope.registration.showNotification.mock.calls[0][1]).not.toHaveProperty('image');
  });

  it('still shows something for a push it cannot read, because a browser that is pushed to must', async () => {
    for (const unreadable of [undefined, null, 'text', { body: 'no title' }, { title: '' }]) {
      await push(unreadable);
    }

    expect(scope.registration.showNotification).toHaveBeenCalledTimes(5);
    for (const call of scope.registration.showNotification.mock.calls) {
      expect(call[0]).toBe(window.location.hostname);
      expect(call[1].body).toBeUndefined();
    }
  });

  it('survives a payload that is not JSON', async () => {
    await dispatch('push', {
      data: {
        json: () => {
          throw new SyntaxError('not json');
        },
      },
    });

    expect(scope.registration.showNotification).toHaveBeenCalledTimes(1);
  });

  it('reports a tap to the platform with no body, no credentials and no CORS, then opens the path', async () => {
    const { close, fired } = tap({
      path: '/menu',
      click: 'https://api.example/providers/push/clicks/token',
    });
    await fired;

    expect(close).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledWith('https://api.example/providers/push/clicks/token', {
      method: 'POST',
      mode: 'no-cors',
      credentials: 'omit',
      keepalive: true,
      referrerPolicy: 'no-referrer',
    });
    expect(scope.clients.openWindow).toHaveBeenCalledWith(`${ORIGIN}/menu`);
  });

  it('opens the front page when the path would leave the storefront', async () => {
    for (const path of ['//evil.example/x', 'https://evil.example', 'menu', 42, undefined]) {
      scope.clients.openWindow.mockClear();

      await tap({ path, click: null }).fired;

      expect(scope.clients.openWindow, String(path)).toHaveBeenCalledWith(`${ORIGIN}/`);
    }
  });

  it('reports a tap only to an http(s) address, and a failed report does not stop the page opening', async () => {
    for (const click of ['javascript:alert(1)', 'data:text/plain,x', null, 42]) {
      await tap({ path: '/', click }).fired;
    }
    expect(fetchMock).not.toHaveBeenCalled();

    fetchMock.mockRejectedValue(new TypeError('offline'));
    scope.clients.openWindow.mockClear();
    await tap({ path: '/menu', click: 'https://api.example/providers/push/clicks/token' }).fired;
    expect(scope.clients.openWindow).toHaveBeenCalledWith(`${ORIGIN}/menu`);
  });

  it('brings an open storefront window forward instead of opening a second', async () => {
    const navigate = vi.fn().mockResolvedValue(undefined);
    const focus = vi.fn().mockResolvedValue(undefined);
    scope.clients.matchAll.mockResolvedValue([
      { url: 'https://other.example/', focus: vi.fn(), navigate: vi.fn() },
      { url: `${ORIGIN}/cart`, focus, navigate },
    ]);

    await tap({ path: '/menu', click: null }).fired;

    expect(navigate).toHaveBeenCalledWith(`${ORIGIN}/menu`);
    expect(focus).toHaveBeenCalled();
    expect(scope.clients.openWindow).not.toHaveBeenCalled();
  });
});
