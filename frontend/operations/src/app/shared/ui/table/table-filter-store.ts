import { Injectable } from '@angular/core';

/**
 * Persists one screen's filter state across navigation and reload — X.18.
 *
 * The legacy dashboard kept per-tab filters in `localStorage` and merchants
 * noticed the console regression when it did not (`operations-gap-map.md`
 * `X.18`). `viewId` is the screen's own stable key (`'orders.queue'`,
 * `'catalog.products'`, …) — callers own uniqueness, the same way a route
 * path does.
 *
 * `localStorage` is per-browser, per-viewer state, not a shared or durable
 * record, so a read failure (private browsing, a full quota, a disabled
 * store) degrades to "no persisted filters" rather than throwing — every
 * method is wrapped and never lets a storage error reach the caller.
 */
@Injectable({ providedIn: 'root' })
export class TableFilterStore {
  /** Returns `fallback` unchanged when nothing is stored, or storage is unavailable. */
  load<F>(viewId: string, fallback: F): F {
    try {
      const raw = window.localStorage.getItem(this.key(viewId));
      if (raw === null) {
        return fallback;
      }
      return JSON.parse(raw) as F;
    } catch {
      return fallback;
    }
  }

  save<F>(viewId: string, filters: F): void {
    try {
      window.localStorage.setItem(this.key(viewId), JSON.stringify(filters));
    } catch {
      // Best-effort. A filter that fails to persist is a worse day, not a broken one.
    }
  }

  clear(viewId: string): void {
    try {
      window.localStorage.removeItem(this.key(viewId));
    } catch {
      // See save().
    }
  }

  private key(viewId: string): string {
    return `q-data-table.filters.${viewId}`;
  }
}
