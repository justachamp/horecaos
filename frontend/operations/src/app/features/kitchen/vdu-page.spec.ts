import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { BoardResponse, KitchenApi, TicketResponse } from './kitchen-api';
import { VduPage } from './vdu-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function ticket(overrides: Partial<TicketResponse>): TicketResponse {
  return {
    ticketId: 'ticket-1',
    orderId: 'order-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    channelCode: null,
    status: 'FIRED',
    releaseMode: 'AUTO_ON_CONFIRM',
    releaseAt: null,
    targetReadyAt: null,
    version: 1,
    createdAt: new Date().toISOString(),
    items: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('VduPage', () => {
  let fixture: ComponentFixture<VduPage>;

  async function render(
    board: BoardResponse,
    policy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [VduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: { board: () => Promise.resolve(board) } },
        {
          provide: LatenessPolicyApi,
          useValue: {
            resolve: () => Promise.resolve(policy),
            read: () => Promise.resolve(policy),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VduPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders every live ticket’s sequence label, ready ones first', async () => {
    await render({
      tickets: [
        ticket({ ticketId: 'a', sequenceLabel: 'A-002', status: 'FIRED' }),
        ticket({ ticketId: 'b', sequenceLabel: 'A-001', status: 'READY' }),
      ],
      warnings: [],
    });

    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="vdu-card"]',
    );
    expect(cards).toHaveLength(2);
    expect(cards[0].textContent?.trim()).toBe('A-001');
    expect(cards[1].textContent?.trim()).toBe('A-002');
  });

  it('shows the provider-assigned reference beside sequenceLabel, when the order carries one (gap map row 2.4)', async () => {
    await render({
      tickets: [
        ticket({ ticketId: 'a', sequenceLabel: 'A-002', externalReference: 'YE-2291-04' }),
        ticket({ ticketId: 'b', sequenceLabel: 'A-003' }),
      ],
      warnings: [],
    });

    const host = fixture.nativeElement as HTMLElement;
    const sequences = Array.from(host.querySelectorAll('[data-testid="vdu-sequence"]')).map((el) =>
      el.textContent?.trim(),
    );
    expect(sequences).toEqual(['A-002', 'A-003']);

    const references = host.querySelectorAll('[data-testid="vdu-external-reference"]');
    expect(references).toHaveLength(1);
    expect(references[0].textContent?.trim()).toBe('YE-2291-04');
  });

  it('warns a ticket by its own fulfilment mode’s at-risk window (rows X.39 / 10.3b)', async () => {
    // Every ticket is due in five minutes; delivery warns ten minutes ahead, pickup two, dine-in none.
    const dueInFiveMinutes = new Date(Date.now() + 5 * 60_000).toISOString();
    const perMode: LatenessPolicy = {
      delivery: { atRiskBeforeSeconds: 600, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      pickup: { atRiskBeforeSeconds: 120, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      dineIn: { atRiskBeforeSeconds: 0, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    };
    await render(
      {
        tickets: [
          ticket({
            ticketId: 'd',
            sequenceLabel: 'D',
            fulfilmentMode: 'DELIVERY',
            targetReadyAt: dueInFiveMinutes,
          }),
          ticket({
            ticketId: 'p',
            sequenceLabel: 'P',
            fulfilmentMode: 'PICKUP',
            targetReadyAt: dueInFiveMinutes,
          }),
          ticket({
            ticketId: 'h',
            sequenceLabel: 'H',
            fulfilmentMode: 'DINE_IN',
            targetReadyAt: dueInFiveMinutes,
          }),
        ],
        warnings: [],
      },
      perMode,
    );

    const cardOf = (label: string): HTMLElement =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="vdu-card"]',
        ),
      ).find((card) => card.textContent?.trim() === label) as HTMLElement;
    expect(cardOf('D').classList.contains('vdu__card--warning')).toBe(true);
    expect(cardOf('P').classList.contains('vdu__card--warning')).toBe(false);
    expect(cardOf('H').classList.contains('vdu__card--warning')).toBe(false);
  });

  it('paints a breached ticket with its own mode’s grace: the same overdue ticket is late for pickup only', async () => {
    const overdueByThirtySeconds = new Date(Date.now() - 30_000).toISOString();
    const perMode: LatenessPolicy = {
      delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 120, noPromiseFallbackSeconds: 2700 },
      pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    };
    await render(
      {
        tickets: [
          ticket({
            ticketId: 'd',
            sequenceLabel: 'D',
            fulfilmentMode: 'DELIVERY',
            targetReadyAt: overdueByThirtySeconds,
          }),
          ticket({
            ticketId: 'p',
            sequenceLabel: 'P',
            fulfilmentMode: 'PICKUP',
            targetReadyAt: overdueByThirtySeconds,
          }),
        ],
        warnings: [],
      },
      perMode,
    );

    const cardOf = (label: string): HTMLElement =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="vdu-card"]',
        ),
      ).find((card) => card.textContent?.trim() === label) as HTMLElement;
    expect(cardOf('D').classList.contains('vdu__card--danger')).toBe(false);
    expect(cardOf('P').classList.contains('vdu__card--danger')).toBe(true);
  });

  it('colours a ticket by its ORDER’s clock, as the board does: an unpromised order that waited for approval is late though its ticket just opened (ADR 0150)', async () => {
    const twenty: LatenessPolicy = {
      delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
      pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
    };
    await render(
      {
        tickets: [
          ticket({
            ticketId: 'late',
            sequenceLabel: 'L',
            createdAt: new Date(Date.now() - 10_000).toISOString(),
            targetReadyAt: null,
            orderCreatedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
            orderPromisedAt: null,
            orderTerminal: false,
          }),
          ticket({
            ticketId: 'road',
            sequenceLabel: 'R',
            targetReadyAt: new Date(Date.now() - 10 * 60_000).toISOString(),
            orderCreatedAt: new Date(Date.now() - 30 * 60_000).toISOString(),
            orderPromisedAt: new Date(Date.now() + 10 * 60_000).toISOString(),
            orderTerminal: false,
          }),
        ],
        warnings: [],
      },
      twenty,
    );

    const cardOf = (label: string): HTMLElement =>
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
          '[data-testid="vdu-card"]',
        ),
      ).find((card) => card.textContent?.trim() === label) as HTMLElement;
    expect(cardOf('L').classList.contains('vdu__card--danger')).toBe(true);
    expect(cardOf('R').classList.contains('vdu__card--danger')).toBe(false);
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [VduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: { board: vi.fn() } },
        {
          provide: LatenessPolicyApi,
          useValue: {
            resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
            read: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VduPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="vdu-denied"]'),
    ).not.toBeNull();
  });
});

/**
 * A wall display is opened once and left up for days: an owner publishing new
 * lateness numbers must reach it through the ordinary poll, not a browser reload.
 */
describe('VduPage: the lateness policy follows an edit', () => {
  const GRACE_ONE_HOUR: LatenessPolicy = {
    delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 3600, noPromiseFallbackSeconds: 2700 },
    pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 3600, noPromiseFallbackSeconds: 2700 },
    dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 3600, noPromiseFallbackSeconds: 2700 },
  };

  function setUp(read: ReturnType<typeof vi.fn>): ComponentFixture<VduPage> {
    // Overdue by thirty seconds: LATE under the platform default (no grace),
    // not yet late once the tenant allows an hour of grace.
    const overdue = ticket({
      ticketId: 'd',
      sequenceLabel: 'D',
      targetReadyAt: new Date(Date.now() - 30_000).toISOString(),
    });
    TestBed.configureTestingModule({
      imports: [VduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: KitchenApi,
          useValue: { board: () => Promise.resolve({ tickets: [overdue], warnings: [] }) },
        },
        {
          provide: LatenessPolicyApi,
          useValue: { read, resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY) },
        },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    return TestBed.createComponent(VduPage);
  }

  function isDanger(fixture: ComponentFixture<VduPage>): boolean {
    return (fixture.nativeElement as HTMLElement)
      .querySelector('[data-testid="vdu-card"]')!
      .classList.contains('vdu__card--danger');
  }

  it('applies a policy published after the screen opened, on a later poll', async () => {
    vi.useFakeTimers();
    try {
      const read = vi
        .fn()
        .mockResolvedValueOnce(PLATFORM_DEFAULT_LATENESS_POLICY)
        .mockResolvedValue(GRACE_ONE_HOUR);
      const fixture = setUp(read);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      expect(isDanger(fixture)).toBe(true);

      // The policy is cached server-side for a minute, so a minute and a poll later it is read again.
      await vi.advanceTimersByTimeAsync(70_000);
      fixture.detectChanges();

      expect(isDanger(fixture)).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not treat a failed first read as the loaded policy: the next poll asks again', async () => {
    vi.useFakeTimers();
    try {
      const read = vi.fn().mockResolvedValueOnce(null).mockResolvedValue(GRACE_ONE_HOUR);
      const fixture = setUp(read);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      expect(isDanger(fixture)).toBe(true);

      await vi.advanceTimersByTimeAsync(11_000);
      fixture.detectChanges();

      expect(isDanger(fixture)).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});
