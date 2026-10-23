import { Injectable } from '@angular/core';

import { newIdempotencyKey } from './idempotency';
import { toApiError } from './problem-details';

/**
 * One `Idempotency-Key` per intent, kept until the intent is settled (ADR 0031).
 *
 * A courier whose connection drops after tapping "picked up" will tap again, and the second tap
 * must replay the first answer rather than perform the step twice -- so the key belongs to the
 * intent ("pick up this delivery"), not to the request. It is kept across a request that never
 * arrived (`NETWORK`), where the platform may or may not have seen it, and dropped once the
 * platform has answered at all: after an answer, a new tap is a new decision.
 */
@Injectable({ providedIn: 'root' })
export class IntentKeys {
  private readonly keys = new Map<string, string>();

  /** Runs `call` with the key for `intent`, minting it on first use. */
  async run<T>(intent: string, call: (key: string) => Promise<T>): Promise<T> {
    let key = this.keys.get(intent);
    if (key === undefined) {
      key = newIdempotencyKey();
      this.keys.set(intent, key);
    }
    try {
      const result = await call(key);
      this.keys.delete(intent);
      return result;
    } catch (failure) {
      if (!toApiError(failure).isNetwork) {
        this.keys.delete(intent);
      }
      throw failure;
    }
  }

  /** For a spec: whether an unsettled intent is holding a key. */
  holds(intent: string): boolean {
    return this.keys.has(intent);
  }
}
