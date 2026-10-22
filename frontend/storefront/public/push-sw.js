/*
 * The storefront's Web Push service worker (ADR 0182, D14).
 *
 * It does two things and nothing else. When the platform pushes a promotional message to this browser
 * it shows it, and when the customer taps the notification it tells the platform that the tap happened
 * (that tap is the only "read" signal Web Push offers) and opens the storefront at the path the message
 * named.
 *
 * What it deliberately does not do:
 *   - It caches nothing and intercepts no request: it has no `fetch` handler, so it cannot change how a
 *     page loads.
 *   - It holds no session. It cannot call an API that needs a customer, so it does not try to
 *     re-subscribe on `pushsubscriptionchange`: when a browser rotates a subscription the page offers
 *     "turn on" again on the next visit, and a customer who turned push off somewhere else is never
 *     silently turned back on.
 *   - It trusts nothing in a payload beyond its shape. The path a tap opens is resolved against this
 *     worker's own origin and falls back to the front page if it leaves it, and the tap is reported only
 *     to an http(s) URL.
 *
 * The payload is encrypted to this browser by the platform (RFC 8291) and decrypted by the browser before
 * this code sees it: { title, body, image?, path, click, tag }.
 */
'use strict';

self.addEventListener('install', () => {
  // A new version of this worker replaces the old one at once: it has no state worth finishing.
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  event.waitUntil(show(event));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  event.waitUntil(Promise.all([report(data.click), open(data.path)]));
});

/** The decrypted payload, or null when there is none or it is not JSON. */
function read(event) {
  if (!event.data) {
    return null;
  }
  try {
    const payload = event.data.json();
    return payload && typeof payload === 'object' ? payload : null;
  } catch (unreadable) {
    return null;
  }
}

function show(event) {
  const payload = read(event);
  if (!payload || typeof payload.title !== 'string' || payload.title.length === 0) {
    // A browser that is pushed to must show something (userVisibleOnly), so a message with no readable
    // payload still gets a notification: the storefront's own name, and no claim about its content.
    return self.registration.showNotification(self.location.hostname, {
      icon: '/favicon.ico',
      tag: 'storefront-push',
      data: { path: '/' },
    });
  }
  const options = {
    body: typeof payload.body === 'string' ? payload.body : '',
    icon: '/favicon.ico',
    // The platform's replacement key: a retry of one message replaces the copy on screen.
    tag: typeof payload.tag === 'string' ? payload.tag : undefined,
    data: {
      path: typeof payload.path === 'string' ? payload.path : '/',
      click: typeof payload.click === 'string' ? payload.click : null,
    },
  };
  if (typeof payload.image === 'string' && isHttp(payload.image)) {
    options.image = payload.image;
  }
  return self.registration.showNotification(payload.title, options);
}

function isHttp(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:';
  } catch (invalid) {
    return false;
  }
}

/**
 * Tells the platform the customer tapped. A simple cross-origin POST with no body and no custom header,
 * so the browser sends it without asking the platform's permission first (no CORS is configured for it)
 * and `keepalive` lets it finish even if the tap navigates away at once. The answer is not readable and
 * is not needed.
 */
function report(url) {
  if (typeof url !== 'string' || !isHttp(url)) {
    return Promise.resolve();
  }
  return fetch(url, {
    method: 'POST',
    mode: 'no-cors',
    credentials: 'omit',
    keepalive: true,
    referrerPolicy: 'no-referrer',
  }).catch(() => undefined);
}

/** Where a tap goes: the named path on this origin, else the front page. Never another site. */
function target(path) {
  const origin = self.location.origin;
  if (typeof path === 'string' && path.startsWith('/') && !path.startsWith('//')) {
    try {
      const resolved = new URL(path, origin);
      if (resolved.origin === origin) {
        return resolved.href;
      }
    } catch (invalid) {
      // fall through to the front page
    }
  }
  return new URL('/', origin).href;
}

async function open(path) {
  const destination = target(path);
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  for (const client of windows) {
    if (new URL(client.url).origin === self.location.origin && 'focus' in client) {
      if ('navigate' in client) {
        await client.navigate(destination).catch(() => undefined);
      }
      return client.focus();
    }
  }
  return self.clients.openWindow(destination);
}
