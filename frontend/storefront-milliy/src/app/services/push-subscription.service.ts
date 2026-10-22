import { Injectable, computed, inject, signal } from '@angular/core';

import { PushApi } from '../core/api/push-api';
import { newIdempotencyKey } from '../core/api/idempotency';
import { isNotFound } from '../core/api/problem-details';
import { PUSH_BROWSER } from '../core/push/push-browser';

/**
 * Where a customer stands with promotional push in this browser (ADR 0182, D14).
 *
 *  - `unknown`     before the first read
 *  - `unsupported` this browser cannot do Web Push (no service worker, no Push API, no notifications): a
 *                  Telegram mini app webview, an in-app browser, an old handset
 *  - `unavailable` the platform cannot send push (its sending identity is not set up), or the caller has
 *                  no account at this brand to opt in with
 *  - `off`         push could be turned on: the customer has not, and the browser has not refused
 *  - `blocked`     the customer, or their browser, has refused notifications; the page cannot ask again
 *  - `on`          the platform holds this customer's subscription
 *
 * **The opt-in is two questions, and both must be answered yes.** The page asks its own (a prompt the
 * customer taps), and only then does the browser ask its own. Neither is asked on page load: a browser
 * that is asked without a tap treats the answer as a spam signal and may refuse for good.
 */
export type PushState = 'unknown' | 'unsupported' | 'unavailable' | 'off' | 'blocked' | 'on';

@Injectable({ providedIn: 'root' })
export class PushSubscriptionService {
  private readonly api = inject(PushApi);
  private readonly browser = inject(PUSH_BROWSER);

  private readonly current = signal<PushState>('unknown');

  readonly state = computed(() => this.current());

  /** True while an opt-in or an opt-out is in flight, so a double tap cannot start two. */
  readonly busy = signal(false);

  /** Reads where the customer stands. Never asks the browser for permission. */
  async refresh(): Promise<PushState> {
    if (!this.browser.supported) {
      return this.settle('unsupported');
    }
    try {
      const status = await this.api.status();
      if (!status.available || status.publicKey === null) {
        return this.settle('unavailable');
      }
      this.key = status.publicKey;
      if (status.subscribed) {
        return this.settle('on');
      }
      return this.settle(this.browser.permission() === 'denied' ? 'blocked' : 'off');
    } catch (failure) {
      if (isNotFound(failure)) {
        // Signed in, no account at this brand yet: nothing to opt in with, which is not a failure.
        return this.settle('unavailable');
      }
      throw failure;
    }
  }

  /**
   * Turns push on. Call it from the tap on the page's own prompt, and call nothing before it: the browser
   * only shows its permission dialog for a request made inside a user gesture.
   *
   * @returns the state afterwards: `on`, or `blocked` when the customer said no to the browser
   * @throws when the platform refuses the subscription or cannot be reached; the browser's own
   *         subscription is dropped first, so nothing is left subscribed that the platform does not know
   */
  async enable(): Promise<PushState> {
    if (this.busy() || this.key === null) {
      return this.current();
    }
    this.busy.set(true);
    try {
      const permission = await this.browser.requestPermission();
      if (permission !== 'granted') {
        return this.settle(permission === 'denied' ? 'blocked' : 'off');
      }
      const subscription = await this.browser.subscribe(this.key);
      try {
        await this.api.subscribe(subscription, newIdempotencyKey());
      } catch (failure) {
        await this.browser.unsubscribe().catch(() => undefined);
        throw failure;
      }
      return this.settle('on');
    } finally {
      this.busy.set(false);
    }
  }

  /** Turns push off: the platform forgets the subscription and withdraws the consent, then the browser drops its own. */
  async disable(): Promise<PushState> {
    if (this.busy()) {
      return this.current();
    }
    this.busy.set(true);
    try {
      await this.api.unsubscribe(newIdempotencyKey());
      await this.browser.unsubscribe().catch(() => undefined);
      return this.settle(this.browser.permission() === 'denied' ? 'blocked' : 'off');
    } finally {
      this.busy.set(false);
    }
  }

  /** Forgotten when the session ends: the next customer on this handset must not inherit this one's state. */
  forget(): void {
    this.key = null;
    this.current.set('unknown');
  }

  private key: string | null = null;

  private settle(state: PushState): PushState {
    this.current.set(state);
    return state;
  }
}
