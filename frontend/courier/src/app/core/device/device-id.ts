import { Injectable } from '@angular/core';

import { newIdempotencyKey } from '../api/idempotency';

const STORAGE_KEY = 'courier.deviceId';

/**
 * An opaque id for this installation, sent when a duty session opens (ADR 0045: it "names a
 * handset, not a person, and is not a hardware identifier").
 *
 * A random UUID made on first use and kept in local storage so the same phone is the same device
 * tomorrow. Where storage is refused the id lasts for the page's life: a session then names a
 * device that no longer exists after a reload, which costs nothing.
 */
@Injectable({ providedIn: 'root' })
export class DeviceId {
  private fallback: string | null = null;

  value(): string {
    try {
      const stored = globalThis.localStorage.getItem(STORAGE_KEY);
      if (stored !== null && stored !== '') {
        return stored;
      }
      const made = newIdempotencyKey();
      globalThis.localStorage.setItem(STORAGE_KEY, made);
      return made;
    } catch {
      this.fallback ??= newIdempotencyKey();
      return this.fallback;
    }
  }
}
