import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import type { ContentEvent, ContentEventType } from '../types/content.types';

/** How long events wait for company before they are sent. */
export const EVENT_FLUSH_DELAY_MS = 2_000;
/** The most events the platform takes in one batch. */
export const EVENT_BATCH_SIZE = 20;

/**
 * The anonymous counters of ADR 0157, Decision 10: impressions, clicks, dismissals and story
 * completions, reported so the console can show a brand how its content is doing.
 *
 * <h2>What goes on the wire, and what never does</h2>
 *
 * A batch is `{ channel, events: [{ itemId, revisionNo, event }] }` and nothing else. There is no
 * visitor identifier, no session, no `Idempotency-Key`, no bearer: the platform promises never to
 * store one, and the way to keep that promise from the client's side is to have none to send.
 *
 * <h2>Best effort</h2>
 *
 * A count is worth less than the page it is counted on. Events are queued, sent in batches of at
 * most 20 shortly after the last one and as the page goes away (`visibilitychange` to hidden, then
 * `pagehide`), and a failed send is dropped without a retry, a toast or a log line: a missed
 * impression is a rounding error, a retry storm against a rate-limited endpoint is not.
 *
 * An impression counts once per item *and revision* per page session; editing an item publishes a
 * new revision, which is a new thing to count.
 */
@Injectable({ providedIn: 'root' })
export class ContentEventsService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly doc = inject(DOCUMENT);

  private readonly impressed = new Set<string>();
  private queue: ContentEvent[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const onVisibility = (): void => {
      if (this.doc.visibilityState === 'hidden') {
        void this.flush();
      }
    };
    const onPageHide = (): void => {
      void this.flush();
    };
    const view = this.doc.defaultView;
    this.doc.addEventListener('visibilitychange', onVisibility);
    view?.addEventListener('pagehide', onPageHide);
    inject(DestroyRef).onDestroy(() => {
      this.doc.removeEventListener('visibilitychange', onVisibility);
      view?.removeEventListener('pagehide', onPageHide);
      this.cancelTimer();
    });
  }

  /** Once per item and revision per page session; later calls for the same pair do nothing. */
  impression(itemId: string, revisionNo: number): void {
    const key = `${itemId}#${revisionNo}`;
    if (this.impressed.has(key)) {
      return;
    }
    this.impressed.add(key);
    this.enqueue(itemId, revisionNo, 'IMPRESSION');
  }

  click(itemId: string, revisionNo: number): void {
    this.enqueue(itemId, revisionNo, 'CLICK');
  }

  dismissal(itemId: string, revisionNo: number): void {
    this.enqueue(itemId, revisionNo, 'DISMISSAL');
  }

  completion(itemId: string, revisionNo: number): void {
    this.enqueue(itemId, revisionNo, 'COMPLETION');
  }

  /** Sends what is queued, in batches of at most 20. Never rejects. */
  async flush(): Promise<void> {
    this.cancelTimer();
    const events = this.queue;
    this.queue = [];
    const sends: Promise<void>[] = [];
    for (let at = 0; at < events.length; at += EVENT_BATCH_SIZE) {
      sends.push(this.send(events.slice(at, at + EVENT_BATCH_SIZE)));
    }
    await Promise.all(sends);
  }

  private enqueue(itemId: string, revisionNo: number, event: ContentEventType): void {
    this.queue.push({ itemId, revisionNo, event });
    if (this.timer === null) {
      this.timer = setTimeout(() => {
        this.timer = null;
        void this.flush();
      }, EVENT_FLUSH_DELAY_MS);
    }
  }

  private async send(events: ContentEvent[]): Promise<void> {
    try {
      await this.api.report(
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/content/events`,
        { channel: this.config.channel, events },
      );
    } catch {
      // Best effort by contract: a missed count is not worth a retry, a toast or a log line.
    }
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
