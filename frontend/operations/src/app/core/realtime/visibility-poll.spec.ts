import { DestroyRef, EnvironmentInjector, createEnvironmentInjector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { startVisibilityPoll } from './visibility-poll';

/**
 * The "degrade to poll" property, at the level that actually matters: the
 * poll itself does not know or care whether `RealtimeClient` ever connects —
 * see that class's own doc. These tests exercise the extracted utility in
 * isolation, which is also wave P08's own named trap fix: "eleven screens
 * each hand-roll the same visibility-gated 10s poll; extract one."
 */
describe('startVisibilityPoll', () => {
  let injector: EnvironmentInjector;
  let destroyRef: DestroyRef;
  const originalVisibilityState = Object.getOwnPropertyDescriptor(document, 'visibilityState');

  function setVisibility(state: DocumentVisibilityState): void {
    Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    injector = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
    destroyRef = injector.get(DestroyRef);
    setVisibility('visible');
  });

  afterEach(() => {
    try {
      injector.destroy();
    } catch {
      // Already destroyed by the one test that exercises teardown itself.
    }
    vi.useRealTimers();
    if (originalVisibilityState) {
      Object.defineProperty(document, 'visibilityState', originalVisibilityState);
    }
  });

  it('ticks immediately by default, then on every interval while visible', () => {
    const tick = vi.fn();

    startVisibilityPoll(tick, 10_000, destroyRef);
    expect(tick).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(10_000);
    expect(tick).toHaveBeenCalledTimes(2);

    vi.advanceTimersByTime(10_000);
    expect(tick).toHaveBeenCalledTimes(3);
  });

  it('does not tick immediately when immediate: false — a caller with its own first fetch', () => {
    const tick = vi.fn();

    startVisibilityPoll(tick, 10_000, destroyRef, { immediate: false });

    expect(tick).not.toHaveBeenCalled();
    vi.advanceTimersByTime(10_000);
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('a hidden tab never ticks from the interval — the correctness property this exists for', () => {
    const tick = vi.fn();
    startVisibilityPoll(tick, 10_000, destroyRef, { immediate: false });
    setVisibility('hidden');

    vi.advanceTimersByTime(30_000);

    expect(tick).not.toHaveBeenCalled();
  });

  it('returning to the tab ticks immediately, rather than waiting out the rest of the interval', () => {
    const tick = vi.fn();
    startVisibilityPoll(tick, 10_000, destroyRef, { immediate: false });

    setVisibility('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(tick).not.toHaveBeenCalled();

    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('tears itself down on destroy — no further ticks, on the interval or on visibility', () => {
    const tick = vi.fn();
    startVisibilityPoll(tick, 10_000, destroyRef, { immediate: false });

    injector.destroy();

    vi.advanceTimersByTime(30_000);
    setVisibility('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));

    expect(tick).not.toHaveBeenCalled();
  });

  it('the poll runs unconditionally — nothing about it depends on RealtimeClient ever connecting', () => {
    // The property "degrade to poll" actually rests on: this function takes
    // no realtime state of any kind as an input, so a session where the
    // accelerator never manages to connect ticks exactly as one where it
    // connects on the first try. There is nothing to assert beyond the
    // signature already proving it — recorded here as the test this row's
    // own "degrade-to-poll fallback" requirement names explicitly.
    const tick = vi.fn();
    startVisibilityPoll(tick, 10_000, destroyRef);
    vi.advanceTimersByTime(10_000);
    expect(tick).toHaveBeenCalledTimes(2);
  });
});
