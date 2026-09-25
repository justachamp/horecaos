import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { CurrentLocation } from '../core/auth/current-location';
import { I18n } from '../core/i18n/i18n';
import { RealtimeClient, RealtimeFrame } from '../core/realtime/realtime-client';
import {
  BoardResponse,
  ItemResponse,
  KitchenApi,
  TicketResponse,
} from '../features/kitchen/kitchen-api';
import { WallboardKitchenPage } from './wallboard-kitchen-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function ticket(overrides: Partial<TicketResponse> = {}): TicketResponse {
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
    items: [
      {
        itemId: 'item-1',
        orderLineId: 'line-1',
        stationId: 'station-1',
        quantity: 2,
        routedBy: 'FALLBACK',
        status: 'QUEUED',
        version: 1,
      },
    ],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('WallboardKitchenPage', () => {
  let fixture: ComponentFixture<WallboardKitchenPage>;

  function setUp(
    board: BoardResponse,
    overrides: {
      readonly scope?: LocationScope | null;
      readonly denied?: boolean;
      readonly start?: ReturnType<typeof vi.fn>;
      readonly ready?: ReturnType<typeof vi.fn>;
      readonly recall?: ReturnType<typeof vi.fn>;
    } = {},
  ): {
    frameListeners: Array<(frame: RealtimeFrame) => void>;
    boardSpy: ReturnType<typeof vi.fn>;
  } {
    const frameListeners: Array<(frame: RealtimeFrame) => void> = [];
    const onFrame = vi.fn((listener: (frame: RealtimeFrame) => void) => {
      frameListeners.push(listener);
      return () => undefined;
    });
    const boardSpy = vi.fn().mockResolvedValue(board);

    TestBed.configureTestingModule({
      imports: [WallboardKitchenPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>('scope' in overrides ? overrides.scope! : SCOPE),
            denied: signal(overrides.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: KitchenApi,
          useValue: {
            board: boardSpy,
            stations: vi.fn().mockResolvedValue([]),
            start: overrides.start ?? vi.fn(() => of({} as ItemResponse)),
            ready: overrides.ready ?? vi.fn(() => of({} as ItemResponse)),
            recall: overrides.recall ?? vi.fn(() => of({} as ItemResponse)),
          },
        },
        { provide: RealtimeClient, useValue: { onFrame, state: signal('open') } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(WallboardKitchenPage);
    return { frameListeners, boardSpy };
  }

  it('renders a queued line’s Start button visibly, with no hover required', async () => {
    setUp({ tickets: [ticket()], warnings: [] });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const startButton = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="wallboard-kitchen-item-start-item-1"]',
    );
    expect(startButton).not.toBeNull();
    expect(startButton?.textContent?.trim()).toBe('Start');
  });

  it('touch shell mode: tapping Start calls the kitchen API and applies the response', async () => {
    const start = vi.fn(() =>
      of({
        applied: true,
        item: {
          itemId: 'item-1',
          orderLineId: 'line-1',
          stationId: 'station-1',
          quantity: 2,
          routedBy: 'FALLBACK',
          status: 'STARTED',
          version: 2,
        },
        ticketStatus: 'IN_PRODUCTION',
        ticketVersion: 2,
      } satisfies ItemResponse),
    );
    setUp({ tickets: [ticket()], warnings: [] }, { start });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const startButton = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '[data-testid="wallboard-kitchen-item-start-item-1"]',
    );
    startButton?.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(start).toHaveBeenCalledWith(SCOPE, 'item-1');
    // Started now offers Ready instead of Start — no hover, a plain re-render.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="wallboard-kitchen-item-start-item-1"]')).toBeNull();
    expect(
      host.querySelector('[data-testid="wallboard-kitchen-item-ready-item-1"]'),
    ).not.toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    setUp({ tickets: [], warnings: [] }, { scope: null, denied: true });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="wallboard-kitchen-denied"]',
      ),
    ).not.toBeNull();
  });

  it('refreshes at once on a KITCHEN_BOARD frame, the ADR 0045 accelerator', async () => {
    const { frameListeners, boardSpy } = setUp({ tickets: [], warnings: [] });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    boardSpy.mockClear();

    for (const listener of frameListeners) {
      listener({
        kind: 'signal',
        channel: 'kitchen_board',
        scope: 'LOCATION:l1',
        resourceType: 'KitchenTicket',
        resourceId: 'ticket-1',
        version: 2,
        occurredAt: '2026-09-25T09:00:00Z',
      });
    }
    await flushMicrotasks();

    expect(boardSpy).toHaveBeenCalled();
  });

  it('the offline/disconnected banner names how long ago the board last refreshed', async () => {
    vi.useFakeTimers();
    try {
      const { boardSpy } = setUp({ tickets: [], warnings: [] });
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      // The connection drops after the first successful load: every later
      // poll hangs rather than resolving, so lastUpdatedAt freezes while the
      // clock keeps advancing — the state a stuck connection actually
      // produces, not a poll that keeps quietly succeeding.
      boardSpy.mockImplementation(() => new Promise(() => undefined));

      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="wallboard-kitchen-offline-banner"]',
        ),
      ).toBeNull();

      // Past the stale threshold (30s at the 10s poll) with no successful
      // refresh in between — a hung connection, not a working slow one.
      await vi.advanceTimersByTimeAsync(31_000);
      fixture.detectChanges();

      const banner = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="wallboard-kitchen-offline-banner"]',
      );
      expect(banner).not.toBeNull();
      const lastUpdated = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="wallboard-kitchen-last-updated"]',
      );
      expect(lastUpdated?.textContent).toMatch(/Updated/);
    } finally {
      vi.useRealTimers();
    }
  });
});
