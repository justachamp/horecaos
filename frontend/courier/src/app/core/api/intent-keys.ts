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
 *
 * **The body belongs to the intent as well.** The platform hashes the body it saw under a key and
 * answers 409 `IDEMPOTENCY_KEY_REUSED` to the same key with another body, so a retry that read the
 * courier's position afresh would never be the replay it was kept the key for. {@link runWith}
 * takes the part of the body that can change between taps -- the position -- captures it on the
 * first attempt and hands the same value to every retry. It is held in memory for as long as the
 * key is, and no longer: the platform answering, or {@link clear} at sign-out.
 */
@Injectable({ providedIn: 'root' })
export class IntentKeys {
  private readonly keys = new Map<string, string>();
  private readonly bodies = new Map<string, unknown>();

  /** Runs `call` with the key for `intent`, minting it on first use. */
  async run<T>(intent: string, call: (key: string) => Promise<T>): Promise<T> {
    let key = this.keys.get(intent);
    if (key === undefined) {
      key = newIdempotencyKey();
      this.keys.set(intent, key);
    }
    try {
      const result = await call(key);
      this.settle(intent);
      return result;
    } catch (failure) {
      if (!toApiError(failure).isNetwork) {
        this.settle(intent);
      }
      throw failure;
    }
  }

  /**
   * Runs `call` with the key for `intent` and the body `capture` produced the first time, minting
   * both on first use. `capture` is not run again for a retry of an unsettled intent, whatever it
   * would read now.
   */
  async runWith<B, T>(
    intent: string,
    capture: () => Promise<B>,
    call: (key: string, body: B) => Promise<T>,
  ): Promise<T> {
    let key = this.keys.get(intent);
    if (key === undefined) {
      key = newIdempotencyKey();
      this.keys.set(intent, key);
    }
    try {
      if (!this.bodies.has(intent)) {
        this.bodies.set(intent, await capture());
      }
      const result = await call(key, this.bodies.get(intent) as B);
      this.settle(intent);
      return result;
    } catch (failure) {
      if (!toApiError(failure).isNetwork) {
        this.settle(intent);
      }
      throw failure;
    }
  }

  /** Sign-out: nothing of a courier's unsettled requests stays in memory for whoever uses the phone next. */
  clear(): void {
    this.keys.clear();
    this.bodies.clear();
  }

  /** For a spec: whether an unsettled intent is holding a key. */
  holds(intent: string): boolean {
    return this.keys.has(intent);
  }

  private settle(intent: string): void {
    this.keys.delete(intent);
    this.bodies.delete(intent);
  }
}
