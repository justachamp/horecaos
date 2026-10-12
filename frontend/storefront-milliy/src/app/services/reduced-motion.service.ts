import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/**
 * Whether the visitor asked their device for less motion (`prefers-reduced-motion: reduce`).
 *
 * A signal that follows the setting while the page is open. A browser that cannot answer is read as
 * "no preference", which is the CSS default too.
 */
@Injectable({ providedIn: 'root' })
export class ReducedMotionService {
  private readonly state = signal(false);

  /** True while the visitor has asked for reduced motion. */
  readonly reduced = this.state.asReadonly();

  constructor() {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return;
    }
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    this.state.set(query.matches);
    const listener = (event: { matches: boolean }) => this.state.set(event.matches);
    query.addEventListener('change', listener);
    inject(DestroyRef).onDestroy(() => query.removeEventListener('change', listener));
  }
}
