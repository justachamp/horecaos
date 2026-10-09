import { TestBed } from '@angular/core/testing';

import { ReducedMotionService } from './reduced-motion.service';

type Listener = (event: { matches: boolean }) => void;

function stubMatchMedia(matches: boolean) {
  const listeners = new Set<Listener>();
  const query = {
    matches,
    addEventListener: (_type: string, listener: Listener) => listeners.add(listener),
    removeEventListener: (_type: string, listener: Listener) => listeners.delete(listener),
  };
  const matchMedia = vi.fn(() => query);
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: matchMedia,
  });
  return {
    matchMedia,
    listeners,
    change: (next: boolean) => listeners.forEach((listener) => listener({ matches: next })),
  };
}

describe('ReducedMotionService', () => {
  afterEach(() => {
    Reflect.deleteProperty(window, 'matchMedia');
  });

  it('reads prefers-reduced-motion: reduce', () => {
    const stub = stubMatchMedia(true);

    const service = TestBed.inject(ReducedMotionService);

    expect(stub.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    expect(service.reduced()).toBe(true);
  });

  it('is false when the visitor has not asked for less motion', () => {
    stubMatchMedia(false);

    expect(TestBed.inject(ReducedMotionService).reduced()).toBe(false);
  });

  it('follows the setting while the page is open', () => {
    const stub = stubMatchMedia(false);
    const service = TestBed.inject(ReducedMotionService);

    stub.change(true);

    expect(service.reduced()).toBe(true);
  });

  it('stops listening when it is destroyed', () => {
    const stub = stubMatchMedia(false);
    TestBed.inject(ReducedMotionService);

    TestBed.resetTestingModule();

    expect(stub.listeners.size).toBe(0);
  });

  it('is false, not an error, where matchMedia does not exist', () => {
    Reflect.deleteProperty(window, 'matchMedia');

    expect(TestBed.inject(ReducedMotionService).reduced()).toBe(false);
  });
});
