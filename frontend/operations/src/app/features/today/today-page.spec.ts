import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LiveBoard, LiveBoardSnapshot } from './live-board';
import { LiveOperators, LiveOperatorsBand } from './live-operators';
import { TodayPage } from './today-page';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

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

/** Settles the start → ensureLoaded → refresh → LiveBoard.load promise chain, matching `order-queue.spec.ts`'s helper. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(options: {
  load?: ReturnType<typeof vi.fn>;
  operators?: ReturnType<typeof vi.fn>;
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
      {
        provide: LiveOperators,
        useValue: {
          load:
            options.operators ??
            vi.fn().mockResolvedValue({ available: true, rows: [] } satisfies LiveOperatorsBand),
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(TodayPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('TodayPage: states', () => {
  it('shows the denied state when the operator holds no location grant', async () => {
    configure({ scope: null });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="today-denied"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="today-counters"]')).toBeNull();
  });

  it('renders the skeleton before the first load settles', () => {
    configure({ load: vi.fn().mockReturnValue(new Promise(() => {})) });
    const fixture = TestBed.createComponent(TodayPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="today-skeleton"]')).not.toBeNull();
  });

  it('shows an error band and keeps no stale counters on a non-denied failure', async () => {
    configure({
      load: vi.fn().mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, null, 'corr-9')),
    });
    const fixture = await render();

    expect(fixture.nativeElement.textContent).toContain('corr-9');
  });
});

describe('TodayPage: the oversized counters', () => {
  it('renders totalNonTerminal and cancelled verbatim from the snapshot', async () => {
    configure({
      load: vi
        .fn()
        .mockResolvedValue(
          snapshot({ counts: { ...snapshot().counts, totalNonTerminal: 12, cancelled: 3 } }),
        ),
    });
    const fixture = await render();

    expect(
      fixture.nativeElement
        .querySelector('[data-testid="today-counter-in-progress"]')
        ?.textContent.trim(),
    ).toBe('12');
    expect(
      fixture.nativeElement
        .querySelector('[data-testid="today-counter-cancelled"]')
        ?.textContent.trim(),
    ).toBe('3');
  });
});

describe('TodayPage: the period label', () => {
  it('states which trading day the cancelled counter covers, in the tenant zone', async () => {
    configure({
      load: vi.fn().mockResolvedValue(
        snapshot({
          period: 'BUSINESS_DAY',
          // 02:00 on 12 September in Tashkent (UTC+5) — a boundary past midnight.
          periodFrom: '2026-09-11T21:00:00Z',
          counts: { ...snapshot().counts, cancelled: 3 },
        }),
      ),
    });
    const fixture = await render();

    const label = fixture.nativeElement.querySelector('[data-testid="today-period"]');
    expect(label?.textContent).toContain('12.09 02:00');
    expect(label?.textContent).toContain('this trading day');
  });

  it('says nothing at all when the counters are a lifetime figure, rather than inventing a window', async () => {
    configure({
      load: vi.fn().mockResolvedValue(snapshot({ period: 'ALL_TIME', periodFrom: null })),
    });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="today-period"]')).toBeNull();
  });
});

describe('TodayPage: source and type mix', () => {
  it('renders a raw channel code but a translated fulfilment-mode label', async () => {
    configure({
      load: vi.fn().mockResolvedValue(
        snapshot({
          sourceMix: [{ key: 'TELEGRAM_BOT', count: 4 }],
          typeMix: [{ key: 'DELIVERY', count: 4 }],
        }),
      ),
    });
    const fixture = await render();

    const sourceCard = fixture.nativeElement.querySelector('[data-testid="today-source-mix"]');
    const typeCard = fixture.nativeElement.querySelector('[data-testid="today-type-mix"]');
    expect(sourceCard?.textContent).toContain('TELEGRAM_BOT');
    expect(typeCard?.textContent).toContain('Delivery');
    expect(typeCard?.textContent).not.toContain('DELIVERY');
  });

  it('shows the empty note instead of a blank band when nothing is in progress', async () => {
    configure({ load: vi.fn().mockResolvedValue(snapshot({ sourceMix: [], typeMix: [] })) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="today-source-mix"]')?.textContent,
    ).toContain('No orders in progress');
  });
});

describe('TodayPage: the branch leaderboard', () => {
  it('renders branches ranked by active load, and the partial-scope note when shown < total', async () => {
    configure({
      load: vi.fn().mockResolvedValue(
        snapshot({
          branches: [
            { locationId: 'l2', displayName: 'Юнусабад', inProgress: 9 },
            { locationId: 'l1', displayName: 'Чиланзар', inProgress: 3 },
          ],
          branchesAvailable: true,
          branchesShown: 2,
          branchesTotal: 5,
        }),
      ),
    });
    const fixture = await render();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="today-branch-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Юнусабад');
    expect(rows[0].textContent).toContain('9');
    expect(
      fixture.nativeElement.querySelector('[data-testid="today-branches-partial"]')?.textContent,
    ).toContain('2');
  });

  it('shows the unavailable note, not an empty table, when the branch band could not be read at all', async () => {
    configure({ load: vi.fn().mockResolvedValue(snapshot({ branchesAvailable: false })) });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="today-branches-unavailable"]'),
    ).not.toBeNull();
  });
});

describe('TodayPage: the operator band (IA 0.1d)', () => {
  const band = (rows: LiveOperatorsBand['rows']): LiveOperatorsBand => ({
    available: true,
    rows,
  });

  it('names each person with the orders they accepted and created, in the server order', async () => {
    configure({
      operators: vi.fn().mockResolvedValue(
        band([
          {
            operatorPrincipalId: 'sub-aziza',
            displayName: 'Aziza Karimova',
            createdCount: 4,
            acceptedCount: 9,
          },
          {
            operatorPrincipalId: 'sub-bobur',
            displayName: 'Bobur Aliyev',
            createdCount: 6,
            acceptedCount: 2,
          },
        ]),
      ),
    });
    const fixture = await render();

    const rows = fixture.nativeElement.querySelectorAll('[data-testid="today-operator-row"]');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Aziza Karimova');
    expect(rows[0].textContent).toContain('9');
    expect(rows[0].textContent).toContain('4');
    expect(rows[1].textContent).toContain('Bobur Aliyev');
  });

  it('never prints the account id: a person the tenant keeps no name for is an unnamed colleague', async () => {
    configure({
      operators: vi.fn().mockResolvedValue(
        band([
          {
            operatorPrincipalId: '7f2c9e1a-0000-4000-8000-000000000042',
            displayName: null,
            createdCount: 1,
            acceptedCount: 1,
          },
        ]),
      ),
    });
    const fixture = await render();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Unnamed colleague');
    expect(text).not.toContain('7f2c9e1a');
  });

  it('says nobody has taken an order yet, rather than printing an empty table', async () => {
    configure({});
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="today-operators-empty"]'),
    ).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="today-operator-row"]')).toBeNull();
  });

  it('says it could not load the operators, leaving the counters standing, when the band is unavailable', async () => {
    configure({
      load: vi
        .fn()
        .mockResolvedValue(
          snapshot({ counts: { ...snapshot().counts, totalNonTerminal: 12, cancelled: 3 } }),
        ),
      operators: vi.fn().mockResolvedValue({ available: false, rows: [] }),
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="today-operators-unavailable"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement
        .querySelector('[data-testid="today-counter-in-progress"]')
        ?.textContent.trim(),
    ).toBe('12');
  });

  it('is read beside the board on every refresh, for the same scope', async () => {
    const operators = vi.fn().mockResolvedValue(band([]));
    configure({ operators });
    const fixture = await render();
    expect(operators).toHaveBeenCalledWith(FAKE_SCOPE);

    fixture.nativeElement.querySelector('.today__refresh').click();
    await flushMicrotasks();

    expect(operators).toHaveBeenCalledTimes(2);
  });
});

describe('TodayPage: manual refresh', () => {
  it('re-invokes LiveBoard.load on the refresh button', async () => {
    const load = vi.fn().mockResolvedValue(snapshot());
    configure({ load });
    const fixture = await render();
    expect(load).toHaveBeenCalledTimes(1);

    fixture.nativeElement.querySelector('.today__refresh').click();
    await flushMicrotasks();

    expect(load).toHaveBeenCalledTimes(2);
  });
});
