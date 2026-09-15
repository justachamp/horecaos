import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ChallengeState, OrderHandoverApi } from '../orders/order-handover-api';
import { BoardResponse, KitchenApi, TicketResponse } from './kitchen-api';
import { ExpoPage } from './expo-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function ready(overrides: Partial<TicketResponse>): TicketResponse {
  return {
    ticketId: 'ticket-1',
    orderId: 'order-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    channelCode: 'telegram-bot',
    status: 'READY',
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
        routedBy: 'LOCATION_VARIANT',
        status: 'READY',
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

describe('ExpoPage', () => {
  let fixture: ComponentFixture<ExpoPage>;

  async function render(
    kitchenApi: Partial<KitchenApi>,
    apiClient?: Partial<ApiClient>,
    handoverApi?: Partial<OrderHandoverApi>,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ExpoPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: kitchenApi },
        {
          provide: ApiClient,
          useValue: apiClient ?? {
            get: () => of({ value: { lines: [], kitchenNote: null }, version: null }),
          },
        },
        // `q-order-handover-panel` (wave T02) is always rendered per ticket;
        // "no challenge for this order" is the harmless default every test
        // gets unless it says otherwise.
        {
          provide: OrderHandoverApi,
          useValue: handoverApi ?? { challenge: () => of(null) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ExpoPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders the ready queue with each line’s station', async () => {
    const board: BoardResponse = { tickets: [ready({})], warnings: [] };
    await render({ board: () => Promise.resolve(board), stations: () => Promise.resolve([]) });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="expo-ticket"]')).toHaveLength(1);
    expect(host.textContent).toContain('A-014');
    expect(host.textContent).toContain('station-1');
  });

  it('hands a ticket over and removes it from the pass, once packed and proven', async () => {
    const board: BoardResponse = { tickets: [ready({})], warnings: [] };
    const handOver = vi.fn().mockReturnValue(of({ ...ready({}), status: 'HANDED_OVER' }));
    await render({
      board: () => Promise.resolve(board),
      stations: () => Promise.resolve([]),
      handOver,
    });

    const host = fixture.nativeElement as HTMLElement;
    const handOverButton = host.querySelector('[data-testid="expo-handover"]') as HTMLButtonElement;

    // Not yet packed — refused even though this ticket's order carries no
    // handover challenge at all (the default "no challenge" stub).
    expect(handOverButton.disabled).toBe(true);

    (host.querySelector('[data-testid="expo-packed"]') as HTMLInputElement).click();
    fixture.detectChanges();
    expect(handOverButton.disabled).toBe(false);

    handOverButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(handOver).toHaveBeenCalledWith(SCOPE, 'ticket-1');
    expect(host.querySelector('[data-testid="expo-empty"]')).not.toBeNull();
  });

  it('refuses hand-over until the marketplace handover compare is settled', async () => {
    const board: BoardResponse = { tickets: [ready({})], warnings: [] };
    const handOver = vi.fn().mockReturnValue(of({ ...ready({}), status: 'HANDED_OVER' }));
    const pending: ChallengeState = {
      id: 'challenge-1',
      type: 'CODE',
      status: 'PENDING',
      attempts: 0,
      maxAttempts: 5,
      attemptsRemaining: 5,
    };
    await render(
      { board: () => Promise.resolve(board), stations: () => Promise.resolve([]), handOver },
      undefined,
      { challenge: () => of(pending) },
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="expo-packed"]') as HTMLInputElement).click();
    fixture.detectChanges();

    // Packed, but the handover challenge is still PENDING — still refused.
    const handOverButton = host.querySelector('[data-testid="expo-handover"]') as HTMLButtonElement;
    expect(handOverButton.disabled).toBe(true);
    expect(host.querySelector('[data-testid="order-handover-panel"]')).not.toBeNull();
  });

  it('shows the per-department ready roll-up (gap map row 2.3)', async () => {
    const board: BoardResponse = {
      tickets: [
        ready({
          items: [
            {
              itemId: 'item-1',
              orderLineId: 'line-1',
              stationId: 'grill',
              quantity: 2,
              routedBy: 'LOCATION_VARIANT',
              status: 'READY',
              version: 1,
            },
            {
              itemId: 'item-2',
              orderLineId: 'line-2',
              stationId: 'grill',
              quantity: 1,
              routedBy: 'LOCATION_VARIANT',
              status: 'STARTED',
              version: 1,
            },
            {
              itemId: 'item-3',
              orderLineId: 'line-3',
              stationId: 'cold',
              quantity: 3,
              routedBy: 'LOCATION_VARIANT',
              status: 'READY',
              version: 1,
            },
          ],
        }),
      ],
      warnings: [],
    };
    await render({ board: () => Promise.resolve(board), stations: () => Promise.resolve([]) });

    const chips = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="expo-rollup-chip"]'),
    ).map((chip) => chip.textContent?.trim());

    expect(chips).toContain('grill: 2/3');
    expect(chips).toContain('cold: 3/3');
  });

  it('refuses to mark a ticket packed while a line is still not ready', async () => {
    const board: BoardResponse = {
      tickets: [
        ready({
          items: [
            {
              itemId: 'item-1',
              orderLineId: 'line-1',
              stationId: 'grill',
              quantity: 1,
              routedBy: 'LOCATION_VARIANT',
              status: 'STARTED',
              version: 1,
            },
          ],
        }),
      ],
      warnings: [],
    };
    await render({ board: () => Promise.resolve(board), stations: () => Promise.resolve([]) });

    const checkbox = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="expo-packed"]',
    ) as HTMLInputElement;
    expect(checkbox.disabled).toBe(true);
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [ExpoPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: { board: vi.fn(), stations: vi.fn() } },
        { provide: ApiClient, useValue: { get: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ExpoPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="expo-denied"]'),
    ).not.toBeNull();
  });
});
