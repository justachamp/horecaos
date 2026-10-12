import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../core/config/app-config';
import type { ContentEventName } from '../types/content.types';

/** How long events wait for company before they are sent. */
const FLUSH_DELAY_MS = 4_000;
/** The endpoint takes 1..20 events a request. */
const MAX_BATCH = 20;

/**
 * Anonymous counters for content (ADR 0157, Decision 10): which banner, story or pop-up was seen,
 * tapped, dismissed or watched to the end.
 *
 * <h2>What it sends, and what it does not</h2>
 *
 * `{ channel, events: [{ itemId, revisionNo, event }] }` and nothing else. No account, no token, no
 * idempotency key, no correlation id, no cookie, no referrer, no device or visitor identifier of
 * any kind -- an anonymous browser has none the server could count against, and a counter that
 * carried one would be a tracker. That is why this does not go through `ApiClient` (which stamps
 * every mutation with an `Idempotency-Key` and every platform call with a correlation id) but is a
 * bare `fetch` with `credentials: 'omit'`.
 *
 * <h2>Best effort</h2>
 *
 * An impression is reported once per item and revision in a page session. Events wait a few
 * seconds so they travel together, go at once when the page is hidden or closed (`keepalive`, so
 * the browser finishes the request after the page is gone), and are chunked to the endpoint's limit
 * of 20. A failure -- offline, blocked, rate-limited, a status that is not 2xx -- is swallowed:
 * nothing is shown, retried or queued again.
 */
@Injectable({ providedIn: 'root' })
export class ContentEventsService {
  private readonly config = inject(APP_CONFIG);
  private readonly document = inject(DOCUMENT);

  private queue: { itemId: string; revisionNo: number; event: ContentEventName }[] = [];
  private readonly impressed = new Set<string>();
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const onVisibility = () => {
      if (this.document.visibilityState === 'hidden') {
        this.flush();
      }
    };
    const onPageHide = () => this.flush();
    this.document.addEventListener('visibilitychange', onVisibility);
    const view = this.document.defaultView;
    view?.addEventListener('pagehide', onPageHide);
    inject(DestroyRef).onDestroy(() => {
      this.document.removeEventListener('visibilitychange', onVisibility);
      view?.removeEventListener('pagehide', onPageHide);
      if (this.timer !== null) {
        clearTimeout(this.timer);
        this.timer = null;
      }
      this.queue = [];
    });
  }

  /** Files one event. An impression already filed for this item and revision is not filed again. */
  report(itemId: string, revisionNo: number, event: ContentEventName): void {
    if (!itemId || !Number.isInteger(revisionNo) || revisionNo < 1) {
      return;
    }
    if (event === 'IMPRESSION') {
      const key = `${itemId}:${revisionNo}`;
      if (this.impressed.has(key)) {
        return;
      }
      this.impressed.add(key);
    }
    this.queue.push({ itemId, revisionNo, event });
    this.timer ??= setTimeout(() => this.flush(), FLUSH_DELAY_MS);
  }

  /** Sends everything waiting, in batches of at most 20. */
  flush(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const waiting = this.queue;
    this.queue = [];
    for (let start = 0; start < waiting.length; start += MAX_BATCH) {
      this.send(waiting.slice(start, start + MAX_BATCH));
    }
  }

  private send(
    events: readonly { itemId: string; revisionNo: number; event: ContentEventName }[],
  ): void {
    const { apiBaseUrl, tenantId, brandId, channel } = this.config;
    try {
      void fetch(`${apiBaseUrl}/storefront/tenants/${tenantId}/brands/${brandId}/content/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ channel, events }),
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
        keepalive: true,
      }).then(
        () => undefined,
        () => undefined,
      );
    } catch {
      // No fetch, or the browser refused to start the request: counters are best effort.
    }
  }
}
