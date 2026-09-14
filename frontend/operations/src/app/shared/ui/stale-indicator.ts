import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * "This board stopped updating" — the marker `X.34`'s own gap text names
 * directly: "a board that stopped updating looks exactly like a quiet shift"
 * (wave P08).
 *
 * Age is recomputed on a one-second timer rather than once when {@link
 * updatedAt} last changed — a `computed()` never re-evaluates on the strength
 * of wall-clock time alone (see this repo's own `CLAUDE.md` on exactly that
 * trap), so a screen that stops fetching entirely would otherwise never
 * cross {@link ageThresholdMs} at all. The ticking is internal to this
 * component: a caller passes `updatedAt` and nothing else, and the interval
 * is torn down with the component via `DestroyRef`.
 */
@Component({
  selector: 'q-stale-indicator',
  imports: [TPipe],
  templateUrl: './stale-indicator.html',
  styleUrl: './stale-indicator.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaleIndicator {
  readonly updatedAt = input.required<Date | null>();

  /** 30s — comfortably above the 10s poll's own interval, so one missed tick is not yet "stale". */
  readonly ageThresholdMs = input(30_000);

  private readonly now = signal(Date.now());

  protected readonly stale = computed(() => {
    const at = this.updatedAt();
    if (!at) {
      return false;
    }
    return this.now() - at.getTime() >= this.ageThresholdMs();
  });

  constructor() {
    const handle = setInterval(() => this.now.set(Date.now()), 1_000);
    inject(DestroyRef).onDestroy(() => clearInterval(handle));
  }
}
