import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { routes } from '../app.routes';
import { ApiError } from '../core/api/problem-details';
import { CurrentLocation } from '../core/auth/current-location';
import { I18n } from '../core/i18n/i18n';
import { LiveBoard, LiveBoardSnapshot } from '../features/today/live-board';
import { ensureDesignTokensLoaded } from './design-tokens.testing';
import { WallboardShell } from './wallboard-shell';

beforeAll(() => ensureDesignTokensLoaded());

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const FRAME_MS = 20;

function snapshot(overrides: Partial<LiveBoardSnapshot> = {}): LiveBoardSnapshot {
  return {
    counts: {
      newOrders: 0,
      awaitingApproval: 0,
      inKitchen: 0,
      ready: 0,
      fulfilling: 0,
      completed: 0,
      cancelled: 0,
      totalNonTerminal: 0,
      total: 0,
    },
    period: 'BUSINESS_DAY',
    periodFrom: '2026-09-11T19:00:00Z',
    sourceMix: [],
    typeMix: [],
    branches: [],
    branchesAvailable: true,
    branchesShown: 0,
    branchesTotal: 0,
    ...overrides,
  };
}

/** Same helper `today-page.spec.ts` uses to settle the start → ensureLoaded → refresh chain, for the tests below that do not need fake timers. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(options: {
  load?: ReturnType<typeof vi.fn>;
  scope?: typeof FAKE_SCOPE | null;
}): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: () => (options.scope === undefined ? FAKE_SCOPE : options.scope),
          denied: () => options.scope === null,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: LiveBoard,
        useValue: { load: options.load ?? vi.fn().mockResolvedValue(snapshot()) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(WallboardShell);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

// ---------------------------------------------------------------- routing

describe('WallboardShell: route configuration', () => {
  it('is declared outside the console Shell — a sibling of its route, not a child', () => {
    const shellRoute = routes.find((route) => route.path === '');
    expect(shellRoute).toBeDefined();
    const nestedUnderShell = shellRoute?.children?.some((child) => child.path === 'wallboard');
    expect(nestedUnderShell).toBeFalsy();

    const topLevel = routes.find((route) => route.path === 'wallboard');
    expect(topLevel).toBeDefined();
  });

  it('guards sign-in but carries no capabilityGuard, and lazy-loads WallboardShell', async () => {
    const topLevel = routes.find((route) => route.path === 'wallboard')!;
    expect(topLevel.canActivate).toBeDefined();
    expect(topLevel.canActivateChild).toBeUndefined();

    // Same convention every other route in this file uses — `loadComponent`
    // resolves straight to the component class, not a module namespace
    // object (see e.g. the `today-page.ts` / `orders-page.ts` routes above).
    const loaded = await topLevel.loadComponent!();
    expect(loaded).toBe(WallboardShell);
  });
});

// ------------------------------------------------------------------ states

describe('WallboardShell: states', () => {
  it('shows the denied state when the operator holds no location grant', async () => {
    configure({ scope: null });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="wallboard-denied"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="wallboard-counters"]')).toBeNull();
  });

  it('renders the skeleton before the first load settles', () => {
    configure({ load: vi.fn().mockReturnValue(new Promise(() => {})) });
    const fixture = TestBed.createComponent(WallboardShell);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="wallboard-skeleton"]'),
    ).not.toBeNull();
  });

  it('shows an error band on a non-denied failure, without hiding the freshness banner', async () => {
    configure({
      load: vi.fn().mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, null, 'corr-9')),
    });
    const fixture = await render();

    expect(fixture.nativeElement.textContent).toContain('corr-9');
    expect(
      fixture.nativeElement.querySelector('[data-testid="wallboard-freshness"]'),
    ).not.toBeNull();
  });
});

describe('WallboardShell: the oversized counters', () => {
  it('renders totalNonTerminal and cancelled through q-wallboard-tile', async () => {
    configure({
      load: vi
        .fn()
        .mockResolvedValue(
          snapshot({ counts: { ...snapshot().counts, totalNonTerminal: 12, cancelled: 3 } }),
        ),
    });
    const fixture = await render();

    const inProgress = fixture.nativeElement.querySelector(
      '[data-testid="wallboard-counter-in-progress"] [data-testid="q-wallboard-tile-value"]',
    );
    const cancelled = fixture.nativeElement.querySelector(
      '[data-testid="wallboard-counter-cancelled"] [data-testid="q-wallboard-tile-value"]',
    );
    expect(inProgress?.textContent.trim()).toBe('12');
    expect(cancelled?.textContent.trim()).toBe('3');
  });
});

describe('WallboardShell: the operator band', () => {
  it('stays an honest locked note, never a raw actor id, since IA 9.2 is not built', async () => {
    configure({});
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="wallboard-operators"]')?.textContent,
    ).toContain('IA 9.2');
  });
});

// -------------------------------------------------------------- fullscreen

describe('WallboardShell: the unattended fullscreen entry', () => {
  afterEach(() => {
    Reflect.deleteProperty(document.documentElement, 'requestFullscreen');
    Object.defineProperty(document, 'fullscreenElement', { value: null, configurable: true });
  });

  it('calls requestFullscreen — nothing in this codebase did before this wave', async () => {
    const requestFullscreen = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      value: requestFullscreen,
      configurable: true,
    });
    configure({});
    const fixture = await render();

    const button = fixture.nativeElement.querySelector(
      '[data-testid="wallboard-fullscreen-enter"]',
    );
    expect(button).not.toBeNull();
    button.click();
    await flushMicrotasks();

    expect(requestFullscreen).toHaveBeenCalledTimes(1);
  });

  it('hides its own entry once the document actually goes fullscreen', async () => {
    const requestFullscreen = vi.fn().mockImplementation(() => {
      Object.defineProperty(document, 'fullscreenElement', {
        value: document.documentElement,
        configurable: true,
      });
      document.dispatchEvent(new Event('fullscreenchange'));
      return Promise.resolve();
    });
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      value: requestFullscreen,
      configurable: true,
    });
    configure({});
    const fixture = await render();

    fixture.nativeElement.querySelector('[data-testid="wallboard-fullscreen-enter"]').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="wallboard-fullscreen-enter"]'),
    ).toBeNull();
  });

  it('does nothing when the browser offers no Fullscreen API, rather than throwing', async () => {
    configure({});
    const fixture = await render();

    expect(() =>
      fixture.nativeElement.querySelector('[data-testid="wallboard-fullscreen-enter"]').click(),
    ).not.toThrow();
  });
});

// -------------------------------------------------------------- freshness

/**
 * A `LiveBoard.load` mock that succeeds exactly once and then hangs forever
 * (never resolves, never rejects). `POLL_INTERVAL_MS` is 10s, well under
 * every threshold below, so a mock that keeps succeeding on every poll tick
 * would keep resetting `lastUpdatedAt` and the freshness state could never
 * leave 'fresh' — that would test nothing. Hanging every call after the
 * first is exactly the "hung request" scenario `wallboard-shell.ts`'s own
 * doc names: elapsed time alone, ticked by the component's independent
 * one-second clock, must carry the state from fresh through aging to stale.
 */
function loadOnceThenHang(): ReturnType<typeof vi.fn> {
  let calls = 0;
  return vi.fn().mockImplementation(() => {
    calls += 1;
    return calls === 1 ? Promise.resolve(snapshot()) : new Promise(() => {});
  });
}

describe('WallboardShell: the freshness state', () => {
  it('renders the freshness label at the TV type step, not at caption size — this is the defect', async () => {
    configure({});
    const fixture = await render();

    const label = fixture.nativeElement.querySelector('[data-testid="wallboard-freshness-label"]');
    expect(label).not.toBeNull();
    expect(getComputedStyle(label).fontSize).toBe('128px');
    // Caption is 12px and .q-display (the desk-distance top of the scale
    // today-page.ts's own stamp renders at) is 42px — this must be neither.
    expect(getComputedStyle(label).fontSize).not.toBe('12px');
    expect(getComputedStyle(label).fontSize).not.toBe('42px');
  });

  it('degrades fresh → aging → stale purely from elapsed time, with no further poll needed', async () => {
    vi.useFakeTimers();
    try {
      configure({ load: loadOnceThenHang() });
      const fixture = TestBed.createComponent(WallboardShell);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      fixture.detectChanges();

      const state = () =>
        fixture.nativeElement
          .querySelector('[data-testid="wallboard-freshness"]')
          ?.getAttribute('data-state');

      expect(state()).toBe('fresh');

      await vi.advanceTimersByTimeAsync(20_000);
      fixture.detectChanges();
      expect(state()).toBe('aging');

      await vi.advanceTimersByTimeAsync(15_000);
      fixture.detectChanges();
      expect(state()).toBe('stale');
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports elapsed seconds in the label, so the number itself says how stale the board is', async () => {
    vi.useFakeTimers();
    try {
      configure({ load: loadOnceThenHang() });
      const fixture = TestBed.createComponent(WallboardShell);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      fixture.detectChanges();

      await vi.advanceTimersByTimeAsync(20_000);
      fixture.detectChanges();

      const label = fixture.nativeElement.querySelector(
        '[data-testid="wallboard-freshness-label"]',
      );
      expect(label?.textContent).toContain('20');
    } finally {
      vi.useRealTimers();
    }
  });
});
