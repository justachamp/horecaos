import { TestBed } from '@angular/core/testing';

import { ReducedMotionService } from './reduced-motion.service';

type Listener = (event: { matches: boolean }) => void;

function stubMatchMedia(initial: boolean) {
  const listeners = new Set<Listener>();
  const query = {
    matches: initial,
    addEventListener: vi.fn((_type: string, listener: Listener) => listeners.add(listener)),
    removeEventListener: vi.fn((_type: string, listener: Listener) => listeners.delete(listener)),
  };
  const matchMedia = vi.fn(() => query);
  vi.stubGlobal('matchMedia', matchMedia);
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: matchMedia });
  return {
    matchMedia,
    query,
    listeners,
    change: (matches: boolean) => {
      query.matches = matches;
      listeners.forEach((listener) => listener({ matches }));
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(window, 'matchMedia');
});

describe('ReducedMotionService', () => {
  it('asks the browser about prefers-reduced-motion: reduce', () => {
    const media = stubMatchMedia(true);

    const service = TestBed.inject(ReducedMotionService);

    expect(media.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    expect(service.reduced()).toBe(true);
  });

  it('is off when the visitor has not asked for reduced motion', () => {
    stubMatchMedia(false);

    expect(TestBed.inject(ReducedMotionService).reduced()).toBe(false);
  });

  it('follows the visitor changing the setting while the page is open', () => {
    const media = stubMatchMedia(false);
    const service = TestBed.inject(ReducedMotionService);

    media.change(true);
    expect(service.reduced()).toBe(true);

    media.change(false);
    expect(service.reduced()).toBe(false);
  });

  it('stops listening when it is destroyed', () => {
    const media = stubMatchMedia(false);
    TestBed.inject(ReducedMotionService);

    TestBed.resetTestingModule();

    expect(media.listeners.size).toBe(0);
  });

  it('is off, and does not throw, in a browser that cannot answer', () => {
    Reflect.deleteProperty(window, 'matchMedia');

    expect(TestBed.inject(ReducedMotionService).reduced()).toBe(false);
  });
});
