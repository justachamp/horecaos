import { DestroyRef } from '@angular/core';

export interface VisibilityPollOptions {
  /**
   * Whether {@link startVisibilityPoll} calls `tick` once, synchronously,
   * before returning. Default `true` — the shape `voice-presence.ts` already
   * had. Pass `false` for a screen whose own `ngOnInit` already does an
   * async first fetch with its own prerequisites (`order-queue.ts` resolves
   * `CurrentLocation`/the lateness policy before its first `refresh()`) — the
   * interval alone is what that screen wants from this function.
   */
  readonly immediate?: boolean;
}

/**
 * The one visibility-gated poll, extracted (ADR 0045, wave P08's own named
 * trap: "eleven screens each hand-roll the same visibility-gated 10s poll").
 *
 * Before this wave, `order-queue.ts`, `voice-presence.ts` and every other
 * live screen each wrote their own `setInterval` plus a
 * `visibilitychange` listener plus the matching teardown — eleven copies of
 * exactly the same twelve lines, each one a place the pause-when-hidden
 * behaviour could silently drift or be dropped by a screen that forgot it.
 * A background tab polling anyway is not a cosmetic waste: it is a real
 * request against the one API replica ADR 0034 provisions, from a tab nobody
 * is looking at.
 *
 * `tick` fires on every visible interval at `intervalMs`, and once more
 * whenever the tab returns to `visible` — the "coming back from lunch, the
 * numbers are stale" case a pure interval alone would leave the operator
 * staring at for up to `intervalMs`.
 *
 * `destroyRef` cleans up the interval and the listener automatically; no
 * caller needs its own `onDestroy` for this.
 */
export function startVisibilityPoll(
  tick: () => void,
  intervalMs: number,
  destroyRef: DestroyRef,
  options: VisibilityPollOptions = {},
): void {
  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      tick();
    }
  };

  document.addEventListener('visibilitychange', onVisibilityChange);
  const handle = setInterval(() => {
    if (document.visibilityState === 'visible') {
      tick();
    }
  }, intervalMs);

  destroyRef.onDestroy(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange);
    clearInterval(handle);
  });

  if (options.immediate ?? true) {
    tick();
  }
}
