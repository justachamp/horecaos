import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/**
 * Whether the visitor asked their device for less motion (`prefers-reduced-motion: reduce`).
 *
 * Moving media on the home screen (a banner video, a story's video slide) honours it by showing
 * the poster only and by never advancing a video slide on its own (ADR 0157, Front ends). Followed
 * live, so switching the setting while the page is open takes effect without a reload. Where the
 * browser has no `matchMedia` the answer is "no preference", which is what the setting's absence
 * means.
 */
@Injectable({ providedIn: 'root' })
export class ReducedMotionService {
  /** True while the visitor prefers reduced motion. */
  readonly reduced = signal(false);

  constructor() {
    const query = inject(DOCUMENT).defaultView?.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (!query) {
      return;
    }
    this.reduced.set(query.matches);
    const onChange = (event: { matches: boolean }): void => this.reduced.set(event.matches);
    query.addEventListener?.('change', onChange);
    inject(DestroyRef).onDestroy(() => query.removeEventListener?.('change', onChange));
  }
}
