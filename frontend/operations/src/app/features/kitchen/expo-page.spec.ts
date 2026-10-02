import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ChallengeState, OrderHandoverApi } from '../orders/order-handover-api';
import { ActualWeightResult, OrderWeighingApi } from '../orders/order-weighing-api';
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
    weighingApi?: Partial<OrderWeighingApi>,
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
        // ADR 0137: the scale is only drawn for an order with a weighed line.
        {
          provide: OrderWeighingApi,
          useValue: weighingApi ?? { captureActualWeight: () => NEVER },
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

  it('batch 14: the pass names the table a dine-in ticket is going to, and no table on a delivery one', async () => {
    const board: BoardResponse = {
      tickets: [
        ready({
          ticketId: 'ticket-hall',
          orderId: 'order-hall',
          sequenceLabel: 'H-007',
          fulfilmentMode: 'DINE_IN',
          table: {
            sessionId: 'session-1',
            tables: [{ tableId: 'table-7', code: 'T7', displayName: 'Table 7' }],
          },
        }),
        ready({ ticketId: 'ticket-delivery', orderId: 'order-delivery', sequenceLabel: 'D-001' }),
      ],
      warnings: [],
    };
    await render({ board: () => Promise.resolve(board), stations: () => Promise.resolve([]) });

    const tickets = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="expo-ticket"]'),
    );
    expect(tickets).toHaveLength(2);
    const chipOf = (ticket: Element): string | null =>
      ticket.querySelector('[data-testid="order-table-chip"]')?.textContent?.trim() ?? null;
    expect(chipOf(tickets[0])).toBe('Table T7');
    expect(chipOf(tickets[1])).toBeNull();
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

  // ----------------------------------------------- portions and weighing (ADR 0137)

  describe('portions and weighing at handover (ADR 0137)', () => {
    function weighedOrder(provisional: boolean, version = 7) {
      return {
        version,
        value: {
          summary: {
            orderId: 'order-1',
            status: 'READY',
            currency: 'UZS',
            version,
            totalMinor: provisional ? 180_000 : 201_000,
          },
          kitchenNote: null,
          lines: [
            {
              lineId: 'line-1',
              lineNumber: 1,
              productName: 'Medovik',
              quantity: 1,
              finalAmountMinor: provisional ? 180_000 : 201_000,
              modifiers: [],
              commentPresets: [],
              hasNote: false,
              catchweight: {
                quantumGrams: 100,
                nominalGramsPerUnit: 1_200,
                pricePerQuantumMinor: 15_000,
                provisional,
                actualWeightGrams: provisional ? null : 1_340,
              },
            },
          ],
        },
      };
    }

    function result(): ActualWeightResult {
      return {
        orderId: 'order-1',
        lineId: 'line-1',
        changed: true,
        actualWeightGrams: 1_340,
        lineFinalAmountMinor: 201_000,
        totalMinor: 201_000,
        deltaTotalMinor: 21_000,
        revision: 2,
        orderVersion: 8,
      };
    }

    const BOARD: BoardResponse = { tickets: [ready({})], warnings: [] };

    /** `ApiClient.get` is generic over the response; a fixture reader is not, so it is cast once, here. */
    const api = (read: () => unknown): Partial<ApiClient> => ({
      get: read as unknown as ApiClient['get'],
    });

    function kitchen(extra: Partial<KitchenApi> = {}): Partial<KitchenApi> {
      return { board: () => Promise.resolve(BOARD), stations: () => Promise.resolve([]), ...extra };
    }

    function pack(host: HTMLElement): void {
      (host.querySelector('[data-testid="expo-packed"]') as HTMLInputElement).click();
      fixture.detectChanges();
    }

    const handOverButton = (host: HTMLElement) =>
      host.querySelector('[data-testid="expo-handover"]') as HTMLButtonElement;

    it('writes a half portion as 0,5 in the table and in the department roll-up', async () => {
      const half = ready({
        items: [
          {
            itemId: 'item-1',
            orderLineId: 'line-1',
            stationId: 'grill',
            quantity: 0.5,
            routedBy: 'LOCATION_VARIANT',
            status: 'READY',
            version: 1,
          },
        ],
      });
      await render({
        board: () => Promise.resolve({ tickets: [half], warnings: [] }),
        stations: () => Promise.resolve([]),
      });

      TestBed.inject(I18n).setLocale('ru');
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('tbody td.expo__num-col')?.textContent?.trim()).toBe('0,5');
      expect(host.querySelector('[data-testid="expo-rollup-chip"]')?.textContent).toContain(
        '0,5/0,5',
      );
    });

    it('shows a weighed line’s estimated weight under its name, so the pass knows what to cut', async () => {
      await render(
        kitchen(),
        api(() => of(weighedOrder(true))),
      );

      const weight = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="expo-line-weight"]',
      );
      expect(weight?.textContent).toContain('1.2\u00a0kg');
    });

    it('shows the weight a line was weighed at once it is weighed', async () => {
      await render(
        kitchen(),
        api(() => of(weighedOrder(false))),
      );

      const weight = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="expo-line-weight"]',
      );
      expect(weight?.textContent).toContain('1.34\u00a0kg');
      expect(weight?.textContent).toContain('weighed');
    });

    it('will not hand an order over while a weighed line is unweighed, and says why', async () => {
      const handOver = vi.fn().mockReturnValue(of({ ...ready({}), status: 'HANDED_OVER' }));
      await render(
        kitchen({ handOver }),
        api(() => of(weighedOrder(true))),
      );
      const host = fixture.nativeElement as HTMLElement;

      pack(host);

      expect(handOverButton(host).disabled).toBe(true);
      expect(host.querySelector('[data-testid="expo-weigh-first"]')).not.toBeNull();
      handOverButton(host).click();
      expect(handOver).not.toHaveBeenCalled();
    });

    it('offers the scale on the ticket, and lets the order go once the weight is recorded', async () => {
      let order = weighedOrder(true);
      const capture = vi.fn(() => {
        order = weighedOrder(false, 8);
        return of(result());
      });
      await render(
        kitchen(),
        api(() => of(order)),
        undefined,
        {
          captureActualWeight: capture,
        },
      );
      const host = fixture.nativeElement as HTMLElement;
      pack(host);
      expect(handOverButton(host).disabled).toBe(true);

      const input = host.querySelector('[data-testid="order-weigh-input"]') as HTMLInputElement;
      input.value = '1340';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      (host.querySelector('[data-testid="order-weigh-save"]') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(capture).toHaveBeenCalledWith(SCOPE, 'order-1', 'line-1', 1_340, 7);
      expect(host.querySelector('[data-testid="expo-weigh-first"]')).toBeNull();
      expect(handOverButton(host).disabled).toBe(false);
    });

    it('does not hand over while it has not yet read the order, rather than guess there is nothing to weigh', async () => {
      await render(
        kitchen(),
        api(() => NEVER),
      );
      const host = fixture.nativeElement as HTMLElement;

      pack(host);

      expect(handOverButton(host).disabled).toBe(true);
    });

    it('does not hold back an order with nothing sold by weight', async () => {
      await render(
        kitchen(),
        api(() => of({ value: { lines: [], kitchenNote: null }, version: null })),
      );
      const host = fixture.nativeElement as HTMLElement;

      pack(host);

      expect(handOverButton(host).disabled).toBe(false);
      expect(host.querySelector('[data-testid="order-weighing"]')).toBeNull();
    });

    it('notices on the next poll that another screen weighed the order', async () => {
      let order = weighedOrder(true);
      await render(
        kitchen(),
        api(() => of(order)),
      );
      const host = fixture.nativeElement as HTMLElement;
      pack(host);
      expect(handOverButton(host).disabled).toBe(true);

      order = weighedOrder(false, 8);
      await (fixture.componentInstance as unknown as { refresh(): Promise<void> }).refresh();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(handOverButton(host).disabled).toBe(false);
    });
  });
});
