import { Injectable } from '@angular/core';

const STORAGE_KEY = 'qoida_recent_searches';
const LIMIT = 8;

/**
 * What this customer searched for lately, on this device.
 *
 * Replaces `/customers/items/searches/recently-searched`, which the platform
 * does not serve. It is deliberately local rather than something to add to the
 * platform in passing: a search history is personal data, it would need a
 * retention rule, an erasure path and a purpose recorded against every read
 * (ADR 0029), and none of that is worth carrying for a convenience whose entire
 * job is saving somebody from retyping a word.
 *
 * Local storage can throw outright in a WebView with site data disabled, so
 * every access is guarded and a failure simply means no history.
 */
@Injectable({ providedIn: 'root' })
export class RecentSearchesService {
  list(): string[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed.filter((entry) => typeof entry === 'string') : [];
    } catch {
      return [];
    }
  }

  /** Most recent first, de-duplicated case-insensitively, capped. */
  remember(term: string): void {
    const trimmed = term.trim();
    if (!trimmed) {
      return;
    }
    const existing = this.list().filter(
      (entry) => entry.toLocaleLowerCase() !== trimmed.toLocaleLowerCase(),
    );
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([trimmed, ...existing].slice(0, LIMIT)));
    } catch {
      // No history on this device. Not worth telling anybody about.
    }
  }

  clear(): void {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Nothing stored, nothing to clear.
    }
  }
}
