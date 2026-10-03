import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { RealtimeClient, RealtimeFrame } from '../../core/realtime/realtime-client';
import { RosterEntryResponse, CouriersApi } from '../couriers/couriers-api';
import { DispatchApi, PlanQueueResponse } from '../delivery/dispatch-api';
import { OrderAmendmentsApi } from '../orders/order-amendments-api';
import { OrderDeliveryApi } from '../orders/order-delivery-api';
import { OrderRevealApi } from '../orders/order-reveal-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationsApi } from '../settings/locations/locations-api';
import { BoardResponse, KitchenApi, TicketResponse } from './kitchen-api';
import { KitchenQueuePage } from './kitchen-queue-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const DELIVERY_TICKET: TicketResponse = {
  ticketId: 'ticket-1',
  orderId: 'order-1',
  sequenceLabel: 'A-014',
  fulfilmentMode: 'DELIVERY',
  channelCode: 'telegram-bot',
  channelSystemType: 'WEB',
  status: 'FIRED',
  releaseMode: 'AUTO_ON_CONFIRM',
  targetReadyAt: new Date(Date.now() + 20 * 60 * 1000).toISOString(),
  version: 1,
  createdAt: new Date().toISOString(),
  items: [
    {
      itemId: 'item-1',
      orderLineId: 'line-1',
      stationId: 'station-1',
      quantity: 2,
      routedBy: 'LOCATION_VARIANT',
      status: 'QUEUED',
      version: 1,
    },
  ],
};

const AGGREGATOR_TICKET: TicketResponse = {
  ...DELIVERY_TICKET,
  ticketId: 'ticket-2',
  orderId: 'order-2',
  sequenceLabel: 'A-015',
  channelCode: 'yandex-eats',
  channelSystemType: 'AGGREGATOR',
};

function board(tickets: readonly TicketResponse[]): BoardResponse {
  return {
    tickets,
    warnings: [],
    counts: {
      total: tickets.length,
      delivery: tickets.filter((t) => t.fulfilmentMode === 'DELIVERY').length,
      pickup: 0,
      dineIn: 0,
      aggregator: tickets.filter((t) => t.channelSystemType === 'AGGREGATOR').length,
    },
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KitchenQueuePage', () => {
  let fixture: ComponentFixture<KitchenQueuePage>;
  let boardResult: BoardResponse;
  let revealLineNote: ReturnType<typeof vi.fn>;
  let dispatchQueue: ReturnType<typeof vi.fn>;
  let dispatchAssign: ReturnType<typeof vi.fn>;
  let roster: ReturnType<typeof vi.fn>;
  let navigateByUrl: ReturnType<typeof vi.fn>;
  let boardSpy: ReturnType<typeof vi.fn>;
  let realtimeFrameListeners: Array<(frame: RealtimeFrame) => void>;

  async function render(customBoard?: BoardResponse, orderDetail?: unknown): Promise<void> {
    boardResult = customBoard ?? board([DELIVERY_TICKET]);
    revealLineNote = vi.fn(() => of({ lineId: 'line-1', note: 'без лука' }));
    dispatchQueue = vi.fn(() => Promise.resolve<readonly PlanQueueResponse[]>([]));
    dispatchAssign = vi.fn(() =>
      Promise.resolve({ applied: true, planStatus: 'ASSIGNED', planVersion: 2 }),
    );
    roster = vi.fn(() => Promise.resolve<readonly RosterEntryResponse[]>([]));
    navigateByUrl = vi.fn();
    boardSpy = vi.fn(() => Promise.resolve(boardResult));
    realtimeFrameListeners = [];

    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: boardSpy,
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('no summary in this test')) },
        },
        {
          provide: ApiClient,
          useValue: {
            get: () =>
              of(orderDetail ?? { value: { lines: [], kitchenNote: null }, version: null }),
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote } },
        { provide: DispatchApi, useValue: { queue: dispatchQueue, assign: dispatchAssign } },
        { provide: CouriersApi, useValue: { roster } },
        { provide: Router, useValue: { navigateByUrl } },
        {
          provide: RealtimeClient,
          useValue: {
            state: signal('open'),
            onFrame: (listener: (frame: RealtimeFrame) => void) => {
              realtimeFrameListeners.push(listener);
              return () => undefined;
            },
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders the live ticket with its channel and fulfilment mode', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="kitchen-ticket"]')).toHaveLength(1);
    expect(host.textContent).toContain('A-014');
    expect(host.textContent).toContain('telegram-bot');
  });

  // ------------------------------------------------- portions and weights (ADR 0137)

  describe('portions and weights (ADR 0137)', () => {
    function cakeTicket(quantity: number): TicketResponse {
      return {
        ...DELIVERY_TICKET,
        items: [{ ...DELIVERY_TICKET.items[0], quantity }],
      };
    }

    function orderWith(catchweight: unknown, quantity = 1) {
      return {
        version: null,
        value: {
          lines: [
            {
              lineNumber: 1,
              productName: 'Medovik',
              quantity,
              finalAmountMinor: 180_000,
              modifiers: [],
              commentPresets: [],
              lineId: 'line-1',
              hasNote: false,
              catchweight,
            },
          ],
          kitchenNote: null,
        },
      };
    }

    async function expanded(ticket: TicketResponse, order?: unknown): Promise<HTMLElement> {
      await render(board([ticket]), order);
      const host = fixture.nativeElement as HTMLElement;
      (host.querySelector('.ticket__header') as HTMLElement).click();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
      return host;
    }

    it('writes a half portion with the console’s decimal mark', async () => {
      const host = await expanded(cakeTicket(0.5));
      TestBed.inject(I18n).setLocale('ru');
      fixture.detectChanges();

      expect(host.querySelector('td.kitchen__num-col')?.textContent?.trim()).toBe('0,5');
    });

    it('shows the estimated weight of a weighed line, every unit at its nominal weight', async () => {
      const host = await expanded(
        cakeTicket(2),
        orderWith(
          {
            quantumGrams: 100,
            nominalGramsPerUnit: 1_200,
            pricePerQuantumMinor: 15_000,
            provisional: true,
          },
          2,
        ),
      );

      const weight = host.querySelector('[data-testid="kitchen-line-weight"]');
      expect(weight?.textContent).toContain('2.4\u00a0kg');
      expect(weight?.textContent).toContain('estimate');
    });

    it('shows what a line weighed once it has been weighed', async () => {
      const host = await expanded(
        cakeTicket(1),
        orderWith({
          quantumGrams: 100,
          nominalGramsPerUnit: 1_200,
          pricePerQuantumMinor: 15_000,
          provisional: false,
          actualWeightGrams: 1_340,
        }),
      );

      const weight = host.querySelector('[data-testid="kitchen-line-weight"]');
      expect(weight?.textContent).toContain('1.34\u00a0kg');
      expect(weight?.textContent).toContain('weighed');
    });

    it('says nothing about weight on a line that is not sold by weight', async () => {
      const host = await expanded(cakeTicket(1), orderWith(null));

      expect(host.querySelector('[data-testid="kitchen-line-weight"]')).toBeNull();
    });
  });

  it('refreshes at once on a KITCHEN_BOARD frame, the ADR 0045 accelerator (row 2.1)', async () => {
    await render();
    boardSpy.mockClear();

    for (const listener of realtimeFrameListeners) {
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

  it('ignores a frame on a channel this board does not care about', async () => {
    await render();
    boardSpy.mockClear();

    for (const listener of realtimeFrameListeners) {
      listener({
        kind: 'signal',
        channel: 'dispatch_board',
        scope: 'LOCATION:l1',
        resourceType: 'DeliveryPlan',
        resourceId: 'plan-1',
        version: 1,
        occurredAt: '2026-09-25T09:00:00Z',
      });
    }
    await flushMicrotasks();

    expect(boardSpy).not.toHaveBeenCalled();
  });

  it('shows the courier ETA chip when the ticket carries one (wave P11, row 2.1a)', async () => {
    const eta = new Date(Date.now() + 25 * 60 * 1000).toISOString();
    await render(board([{ ...DELIVERY_TICKET, courierEtaAt: eta }]));
    const host = fixture.nativeElement as HTMLElement;

    const chip = host.querySelector('[data-testid="kitchen-ticket-courier-eta"]');
    expect(chip).not.toBeNull();
    expect(chip?.textContent).toContain('Courier ETA');
  });

  it('shows no courier ETA chip for a ticket the join gave none — a pickup ticket, or a plan an in-house courier carries', async () => {
    await render(board([DELIVERY_TICKET]));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="kitchen-ticket-courier-eta"]')).toBeNull();
  });

  it('batch 14: shows the table a dine-in ticket was seated at, beside its number', async () => {
    await render(
      board([
        {
          ...DELIVERY_TICKET,
          fulfilmentMode: 'DINE_IN',
          channelCode: 'qr-table',
          table: {
            sessionId: 'session-1',
            tables: [{ tableId: 'table-7', code: 'T7', displayName: 'Table 7' }],
          },
        },
      ]),
    );
    const host = fixture.nativeElement as HTMLElement;

    const chip = host.querySelector(
      '[data-testid="kitchen-ticket"] .ticket__number [data-testid="order-table-chip"]',
    );
    expect(chip?.textContent?.trim()).toBe('Table T7');
  });

  it('batch 14: a delivery ticket, and a dine-in ticket nobody seated, show no table chip', async () => {
    await render(
      board([DELIVERY_TICKET, { ...AGGREGATOR_TICKET, fulfilmentMode: 'DINE_IN', table: null }]),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="kitchen-ticket"]')).toHaveLength(2);
    expect(host.querySelector('[data-testid="order-table-chip"]')).toBeNull();
  });

  it('expands to show the item row once the ticket header is clicked', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const header = host.querySelector('.ticket__header') as HTMLElement;

    header.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.ticket__items')).not.toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: KitchenApi,
          useValue: {
            board: () => Promise.resolve(board([])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        { provide: ApiClient, useValue: { get: () => of({ value: {}, version: null }) } },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        { provide: DispatchApi, useValue: { queue: vi.fn(), assign: vi.fn() } },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="kitchen-denied"]'),
    ).not.toBeNull();
  });

  // ------------------------------------------------------- P16: line notes

  it('reveals a line note through the audited OrderRevealApi rather than rendering a bare chip', async () => {
    revealLineNote = vi.fn(() => of({ lineId: 'line-1', note: 'без лука' }));
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: {
            get: () =>
              of({
                value: {
                  lines: [
                    {
                      lineNumber: 1,
                      productName: 'Lagman',
                      quantity: 1,
                      finalAmountMinor: 5000000,
                      modifiers: [],
                      commentPresets: [],
                      lineId: 'line-1',
                      hasNote: true,
                    },
                  ],
                  kitchenNote: null,
                },
                version: null,
              }),
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote } },
        {
          provide: DispatchApi,
          useValue: { queue: vi.fn(() => Promise.resolve([])), assign: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const header = host.querySelector('.ticket__header') as HTMLElement;
    header.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).not.toContain('без лука');
    const revealButton = host.querySelector(
      '[data-testid="kitchen-reveal-note"]',
    ) as HTMLButtonElement | null;
    expect(revealButton).not.toBeNull();

    revealButton?.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(revealLineNote).toHaveBeenCalledWith(
      SCOPE,
      'order-1',
      'line-1',
      expect.stringContaining('kitchen'),
    );
    const revealed = host.querySelector('[data-testid="kitchen-line-note"]');
    expect(revealed?.textContent).toContain('без лука');
  });

  // ------------------------------------------------------------- row 2.1b: presets

  it("renders a line's comment presets as chips ahead of the free note, resolved off the order-detail join", async () => {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: {
            get: () =>
              of({
                value: {
                  lines: [
                    {
                      lineNumber: 1,
                      productName: 'Lagman',
                      quantity: 1,
                      finalAmountMinor: 5000000,
                      modifiers: [],
                      commentPresets: [
                        {
                          code: 'NO_ONIONS',
                          labelRu: 'Без лука',
                          labelUz: 'Piyozsiz',
                          labelEn: 'No onions',
                        },
                        {
                          code: 'EXTRA_SPICY',
                          labelRu: 'Поострее',
                          labelUz: 'Achchiqroq',
                          labelEn: 'Extra spicy',
                        },
                      ],
                      lineId: 'line-1',
                      hasNote: false,
                    },
                  ],
                  kitchenNote: null,
                },
                version: null,
              }),
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: vi.fn(() => Promise.resolve([])), assign: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const header = host.querySelector('.ticket__header') as HTMLElement;
    header.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const chips = host.querySelector('[data-testid="kitchen-line-presets"]');
    expect(chips).not.toBeNull();
    expect(chips?.textContent).toContain('No onions');
    expect(chips?.textContent).toContain('Extra spicy');
    // No note on this line, so no reveal affordance renders beside the chips.
    expect(host.querySelector('[data-testid="kitchen-reveal-note"]')).toBeNull();
  });

  it('row 10.12: a chip reads the labels map, so a wording beyond the triple shows where the console column is blank', async () => {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: {
            get: () =>
              of({
                value: {
                  lines: [
                    {
                      lineNumber: 1,
                      productName: 'Lagman',
                      quantity: 1,
                      finalAmountMinor: 5000000,
                      modifiers: [],
                      commentPresets: [
                        {
                          code: 'NO_ONIONS',
                          labelRu: '',
                          labelUz: '',
                          labelEn: '',
                          labels: { kaa: 'Piyazsiz' },
                        },
                        {
                          code: 'EXTRA_SPICY',
                          labelRu: 'Поострее',
                          labelUz: 'Achchiqroq',
                          labelEn: 'Extra spicy',
                          labels: { en: 'Extra spicy, please' },
                        },
                      ],
                      lineId: 'line-1',
                      hasNote: false,
                    },
                  ],
                  kitchenNote: null,
                },
                version: null,
              }),
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: vi.fn(() => Promise.resolve([])), assign: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const header = host.querySelector('.ticket__header') as HTMLElement;
    header.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const chips = host.querySelector('[data-testid="kitchen-line-presets"]');
    expect(chips).not.toBeNull();
    expect(
      Array.from(chips?.querySelectorAll('li') ?? []).map((li) => li.textContent?.trim()),
    ).toEqual(['Piyazsiz', 'Extra spicy, please']);
  });

  // ------------------------------------------------------- P16: aggregator tab

  it('types the aggregator tab off channelSystemType, never a raw channel code', async () => {
    await render(board([DELIVERY_TICKET, AGGREGATOR_TICKET]));
    const host = fixture.nativeElement as HTMLElement;

    const aggregatorTab = Array.from(host.querySelectorAll('.tab')).find((tab) =>
      tab.textContent?.includes('Aggregator'),
    ) as HTMLButtonElement | undefined;
    expect(aggregatorTab).toBeDefined();

    aggregatorTab?.click();
    fixture.detectChanges();

    const visible = host.querySelectorAll('[data-testid="kitchen-ticket"]');
    expect(visible).toHaveLength(1);
    expect(host.textContent).toContain('A-015');
    expect(host.textContent).not.toContain('A-014');
  });

  // ------------------------------------------------------- P16: server-side counts

  it('renders the board counts exactly, not a client-side count over the loaded page', async () => {
    const wideBoard: BoardResponse = {
      tickets: [DELIVERY_TICKET],
      warnings: [],
      // Deliberately disagrees with the one ticket the page actually loaded
      // — asserts the badge reflects `counts`, not `tickets.length`.
      counts: { total: 40, delivery: 40, pickup: 0, dineIn: 0, aggregator: 0 },
    };
    await render(wideBoard);
    const host = fixture.nativeElement as HTMLElement;

    const allTab = Array.from(host.querySelectorAll('.tab')).find((tab) =>
      tab.textContent?.includes('All'),
    );
    expect(allTab?.querySelector('.tab__count')?.textContent?.trim()).toBe('40');
  });

  // ------------------------------------------------------- P16: assign from the pass

  it('assigns an in-house courier from the pass, joining the dispatch queue by orderId', async () => {
    dispatchQueue = vi.fn(() =>
      Promise.resolve<readonly PlanQueueResponse[]>([
        {
          planId: 'plan-1',
          orderId: 'order-1',
          status: 'PLANNED',
          customerDeliveryFeeMinor: 0,
          currency: 'UZS',
          sourceAt: new Date().toISOString(),
          estimatedReadyAt: new Date().toISOString(),
          version: 3,
        },
      ]),
    );
    roster = vi.fn(() =>
      Promise.resolve<readonly RosterEntryResponse[]>([
        {
          courierId: 'courier-1',
          displayReference: 'K-014',
          status: 'ACTIVE',
          courierTypeId: 'type-1',
          courierTypeName: 'Bike',
          vehicleClass: 'BICYCLE',
          activeAssignments: 0,
          concurrencyCeiling: 3,
        },
      ]),
    );
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        { provide: DispatchApi, useValue: { queue: dispatchQueue, assign: dispatchAssign } },
        { provide: CouriersApi, useValue: { roster } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const assignButton = host.querySelector(
      '[data-testid="kitchen-assign-courier"]',
    ) as HTMLButtonElement;
    assignButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchQueue).toHaveBeenCalledWith(SCOPE);
    const courierOption = host.querySelector(
      '[data-testid="kitchen-assign-courier-option"]',
    ) as HTMLButtonElement;
    expect(courierOption.textContent).toContain('K-014');

    courierOption.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchAssign).toHaveBeenCalledWith(
      SCOPE,
      'plan-1',
      'courier-1',
      3,
      expect.any(String),
    );
  });

  it('shows no assign affordance on a non-delivery ticket', async () => {
    await render(board([{ ...DELIVERY_TICKET, fulfilmentMode: 'PICKUP' }]));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="kitchen-assign-courier"]')).toBeNull();
  });

  // ------------------------------------------------- wave 9 w5: external dispatch from the pass (gap map row 2.1c)

  it('requests an external courier quote and accepts it over the order-keyed path, joining the dispatch queue by orderId', async () => {
    dispatchQueue = vi.fn(() =>
      Promise.resolve<readonly PlanQueueResponse[]>([
        {
          planId: 'plan-1',
          orderId: 'order-1',
          status: 'PLANNED',
          customerDeliveryFeeMinor: 15_000,
          currency: 'UZS',
          sourceAt: new Date().toISOString(),
          estimatedReadyAt: new Date().toISOString(),
          version: 3,
        },
      ]),
    );
    const externalPartners = vi.fn(() =>
      Promise.resolve([{ bindingId: 'binding-1', providerType: 'YANDEX', supportsHold: false }]),
    );
    const requestExternalCourierQuote = vi.fn(() =>
      Promise.resolve({
        priced: true,
        quoteId: 'quote-1',
        bindingId: 'binding-1',
        providerType: 'YANDEX',
        priceMinor: 15_000,
        currency: 'UZS',
        customerDeliveryFeeMinor: 15_000,
        deltaMinor: 0,
      }),
    );
    const decideExternalCourier = vi.fn(() =>
      Promise.resolve({
        applied: true,
        abandoned: false,
        planVersion: 4,
        shipmentId: 'shipment-1',
      }),
    );

    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: dispatchQueue, assign: dispatchAssign, externalPartners },
        },
        {
          provide: OrderDeliveryApi,
          useValue: { requestExternalCourierQuote, decideExternalCourier },
        },
        { provide: CouriersApi, useValue: { roster } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const openButton = host.querySelector(
      '[data-testid="kitchen-external-courier"]',
    ) as HTMLButtonElement;
    openButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchQueue).toHaveBeenCalledWith(SCOPE);
    expect(externalPartners).toHaveBeenCalledWith(SCOPE, 'plan-1');
    expect(host.querySelector('[data-testid="external-courier-dialog"]')).not.toBeNull();

    const quoteButton = host.querySelector(
      '[data-testid="external-courier-quote"]',
    ) as HTMLButtonElement;
    quoteButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestExternalCourierQuote).toHaveBeenCalledWith(SCOPE, 'order-1', 'binding-1');

    const acceptButton = host.querySelector(
      '[data-testid="external-courier-accept"]',
    ) as HTMLButtonElement;
    acceptButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(decideExternalCourier).toHaveBeenCalledWith(
      SCOPE,
      'order-1',
      'binding-1',
      'quote-1',
      'ACCEPT',
      expect.any(String),
    );
    // Settling closes the dialog and re-fetches the board (KDS_ASSIGN_REASON's
    // own sibling doc: a KDS mutation always refreshes so the ticket carries
    // its new state, not just the dialog's own local view of it).
    expect(host.querySelector('[data-testid="external-courier-dialog"]')).toBeNull();
  });

  it('renders the external partner state on the ticket from the same poll that loads the board, before any picker is ever opened', async () => {
    dispatchQueue = vi.fn(() =>
      Promise.resolve<readonly PlanQueueResponse[]>([
        {
          planId: 'plan-1',
          orderId: 'order-1',
          status: 'ASSIGNED',
          customerDeliveryFeeMinor: 15_000,
          currency: 'UZS',
          sourceAt: new Date().toISOString(),
          estimatedReadyAt: new Date().toISOString(),
          version: 4,
          shipment: {
            shipmentId: 'shipment-1',
            status: 'ASSIGNED',
            sourceType: 'PARTNER',
            providerBindingId: 'binding-1',
            version: 1,
          },
        },
      ]),
    );

    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: dispatchQueue, assign: dispatchAssign, externalPartners: vi.fn() },
        },
        {
          provide: OrderDeliveryApi,
          useValue: { requestExternalCourierQuote: vi.fn(), decideExternalCourier: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;

    // `refreshShipmentStates` joins the same branch-wide dispatch queue this
    // fixture's `dispatchQueue` returns against the board's own tickets, on
    // the very refresh that loaded them -- no picker was ever opened here.
    expect(dispatchQueue).toHaveBeenCalledWith(SCOPE);
    const state = host.querySelector('[data-testid="kitchen-external-courier-state"]');
    expect(state).not.toBeNull();
    expect(state?.textContent).toContain('External partner assigned');
    expect(host.querySelector('[data-testid="kitchen-external-courier"]')).toBeNull();
  });

  it('picks up a shipment another operator assigned meanwhile on the next 10-second poll, without any picker being opened', async () => {
    vi.useFakeTimers();
    try {
      dispatchQueue = vi.fn(() => Promise.resolve<readonly PlanQueueResponse[]>([]));

      await TestBed.configureTestingModule({
        imports: [KitchenQueuePage],
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
            useValue: {
              board: () => Promise.resolve(board([DELIVERY_TICKET])),
              stations: () => Promise.resolve([]),
            },
          },
          {
            provide: LocationsApi,
            useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
          },
          {
            provide: ApiClient,
            useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
          },
          { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
          {
            provide: DispatchApi,
            useValue: { queue: dispatchQueue, assign: dispatchAssign, externalPartners: vi.fn() },
          },
          {
            provide: OrderDeliveryApi,
            useValue: { requestExternalCourierQuote: vi.fn(), decideExternalCourier: vi.fn() },
          },
          { provide: CouriersApi, useValue: { roster } },
          { provide: Router, useValue: { navigateByUrl } },
        ],
      }).compileComponents();
      TestBed.inject(I18n).setLocale('en');
      fixture = TestBed.createComponent(KitchenQueuePage);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="kitchen-external-courier-state"]')).toBeNull();
      expect(host.querySelector('[data-testid="kitchen-external-courier"]')).not.toBeNull();

      // Another operator assigned a PARTNER courier from the order detail
      // pane meanwhile -- the dispatch queue now carries it.
      dispatchQueue.mockResolvedValue([
        {
          planId: 'plan-1',
          orderId: 'order-1',
          status: 'ASSIGNED',
          customerDeliveryFeeMinor: 15_000,
          currency: 'UZS',
          sourceAt: new Date().toISOString(),
          estimatedReadyAt: new Date().toISOString(),
          version: 4,
          shipment: {
            shipmentId: 'shipment-1',
            status: 'ASSIGNED',
            sourceType: 'PARTNER',
            providerBindingId: 'binding-1',
            version: 1,
          },
        },
      ]);

      await vi.advanceTimersByTimeAsync(10_000);
      fixture.detectChanges();

      const state = host.querySelector('[data-testid="kitchen-external-courier-state"]');
      expect(state).not.toBeNull();
      expect(state?.textContent).toContain('External partner assigned');
      expect(host.querySelector('[data-testid="kitchen-external-courier"]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  // ------------------------------------------------------- P16: counter sale

  it('routes the counter-sale action to the new-order screen', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const counterSale = host.querySelector(
      '[data-testid="kitchen-counter-sale"]',
    ) as HTMLButtonElement;

    counterSale.click();

    expect(navigateByUrl).toHaveBeenCalledWith('/orders/new');
  });

  // --------------------------------------------------------------- ADR 0136

  it('ADR 0136: a combo’s items sit under one header named from the order line, each still on its own station', async () => {
    const comboTicket: TicketResponse = {
      ...DELIVERY_TICKET,
      items: [
        {
          itemId: 'item-burger',
          orderLineId: 'line-burger',
          stationId: 'grill',
          quantity: 2,
          routedBy: 'LOCATION_VARIANT',
          status: 'QUEUED',
          version: 1,
          comboSelectionId: 'sel-1',
          comboContainerVariantId: 'cv-1',
        },
        {
          itemId: 'item-cola',
          orderLineId: 'line-cola',
          stationId: 'bar',
          quantity: 2,
          routedBy: 'LOCATION_VARIANT',
          status: 'QUEUED',
          version: 1,
          comboSelectionId: 'sel-1',
          comboContainerVariantId: 'cv-1',
        },
        {
          itemId: 'item-soup',
          orderLineId: 'line-soup',
          stationId: 'grill',
          quantity: 1,
          routedBy: 'LOCATION_VARIANT',
          status: 'QUEUED',
          version: 1,
        },
      ],
    };
    const orderLine = (lineId: string, productName: string, extra: object = {}) => ({
      lineNumber: 1,
      productName,
      quantity: 1,
      finalAmountMinor: 1000,
      modifiers: [],
      commentPresets: [],
      lineId,
      hasNote: false,
      ...extra,
    });
    const combo = {
      selectionId: 'sel-1',
      containerVariantId: 'cv-1',
      name: 'Lunch box',
      quantity: 2,
    };
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([comboTicket])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        {
          provide: ApiClient,
          useValue: {
            get: () =>
              of({
                value: {
                  lines: [
                    orderLine('line-burger', 'Burger', { combo }),
                    orderLine('line-cola', 'Cola', { combo }),
                    orderLine('line-soup', 'Soup'),
                  ],
                  kitchenNote: null,
                },
                version: null,
              }),
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: vi.fn(() => Promise.resolve([])), assign: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('.ticket__header') as HTMLElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const heads = host.querySelectorAll('[data-testid="kitchen-combo-head"]');
    expect(heads).toHaveLength(1);
    expect(heads[0].textContent).toContain('Lunch box');
    const names = [...host.querySelectorAll('.ticket__items tbody tr')].map((row) =>
      row.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(names[0]).toContain('Lunch box');
    expect(names[1]).toContain('Burger');
    expect(names[1]).toContain('grill');
    expect(names[2]).toContain('Cola');
    expect(names[2]).toContain('bar');
    expect(names[3]).toContain('Soup');
  });
});

// ================================================================ wave 10: «Изменить оплату» (row 2.1d)

const CHANNEL_FIXTURE: ChannelView = {
  id: 'chan-1',
  code: 'telegram-bot',
  systemType: 'WEB',
  displayName: 'Telegram bot',
  status: 'ACTIVE',
  pricePlaneChannelId: null,
  externallyPriced: false,
  guestOrdersAllowed: false,
  providerInstallationId: null,
  version: 1,
  locationCount: 1,
  enabledPaymentMethodCount: 2,
  enabledFulfillmentModes: ['DELIVERY'],
};

describe('KitchenQueuePage: wave 10 «Изменить оплату» (row 2.1d)', () => {
  /**
   * A dedicated `TestBed.configureTestingModule` call, mirroring "reveals a
   * line note" above, rather than the shared `render()` above (whose
   * `ApiClient` stub ignores the path and answers a lines-only shape that
   * has no `summary` for this feature's own read to destructure).
   */
  async function renderWithPaymentChange(options: {
    orderVersion?: number;
    channelCode?: string | null;
    getOrder?: ReturnType<typeof vi.fn>;
    listChannels?: ReturnType<typeof vi.fn>;
    matrices?: ReturnType<typeof vi.fn>;
    changePaymentMethod?: ReturnType<typeof vi.fn>;
  }): Promise<{ fixture: ComponentFixture<KitchenQueuePage>; host: HTMLElement }> {
    const getOrder =
      options.getOrder ??
      vi.fn(() =>
        of({
          value: {
            lines: [],
            kitchenNote: null,
            summary: {
              orderId: 'order-1',
              version: options.orderVersion ?? 4,
              channelCode: options.channelCode === undefined ? 'telegram-bot' : options.channelCode,
              currency: 'UZS',
            },
          },
          version: null,
        }),
      );
    const listChannels = options.listChannels ?? vi.fn().mockResolvedValue([CHANNEL_FIXTURE]);
    const matrices =
      options.matrices ??
      vi.fn().mockResolvedValue({ paymentMethods: { CASH: true, CLICK: true, PAYME: false } });
    const changePaymentMethod =
      options.changePaymentMethod ??
      vi.fn().mockReturnValue(
        of({
          amendmentId: 'amendment-1',
          orderId: 'order-1',
          status: 'APPLIED',
          baseRevision: 1,
          appliedRevision: 2,
          deltaTotalMinor: 0,
          requiresApproval: false,
          expiresAt: new Date().toISOString(),
          amendmentVersion: 1,
          orderVersion: 5,
          commands: ['CHANGE_PAYMENT_METHOD'],
          warnings: [],
          replayed: false,
          commandDetails: [],
          createdAt: new Date().toISOString(),
          createdByActorType: 'USER',
        }),
      );

    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([DELIVERY_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('no summary in this test')) },
        },
        { provide: ApiClient, useValue: { get: getOrder } },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        { provide: DispatchApi, useValue: { queue: vi.fn(), assign: vi.fn() } },
        { provide: CouriersApi, useValue: { roster: vi.fn(() => Promise.resolve([])) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
        { provide: OrderAmendmentsApi, useValue: { changePaymentMethod } },
        { provide: SalesChannelsApi, useValue: { list: listChannels, matrices } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const localFixture = TestBed.createComponent(KitchenQueuePage);
    localFixture.detectChanges();
    await flushMicrotasks();
    localFixture.detectChanges();
    return { fixture: localFixture, host: localFixture.nativeElement as HTMLElement };
  }

  it('opens with the order’s fresh channel matrix and proposes CHANGE_PAYMENT_METHOD against the order’s fresh version', async () => {
    const changePaymentMethod = vi.fn().mockReturnValue(
      of({
        amendmentId: 'amendment-1',
        orderId: 'order-1',
        status: 'APPLIED',
        baseRevision: 1,
        appliedRevision: 2,
        deltaTotalMinor: 0,
        requiresApproval: false,
        expiresAt: new Date().toISOString(),
        amendmentVersion: 1,
        orderVersion: 5,
        commands: ['CHANGE_PAYMENT_METHOD'],
        warnings: [],
        replayed: false,
        commandDetails: [],
        createdAt: new Date().toISOString(),
        createdByActorType: 'USER',
      }),
    );
    const { fixture, host } = await renderWithPaymentChange({
      orderVersion: 7,
      changePaymentMethod,
    });

    (
      host.querySelector('[data-testid="kitchen-change-payment-method"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-change-payment-method-dialog"]')).not.toBeNull();
    expect(
      host.querySelector('[data-testid="order-change-payment-method-dialog-option-CLICK"]'),
    ).not.toBeNull();
    // PAYME is disabled on the channel matrix — never offered.
    expect(
      host.querySelector('[data-testid="order-change-payment-method-dialog-option-PAYME"]'),
    ).toBeNull();

    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-option-CLICK"]',
      ) as HTMLInputElement
    ).click();
    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(changePaymentMethod).toHaveBeenCalledWith(SCOPE, 'order-1', 7, 'CLICK');
  });

  it('falls back to CASH only when the channel read is denied, still offering something rather than nothing', async () => {
    const { fixture, host } = await renderWithPaymentChange({
      listChannels: vi.fn().mockRejectedValue(new Error('CHANNEL_READ denied')),
    });

    (
      host.querySelector('[data-testid="kitchen-change-payment-method"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      host.querySelector('[data-testid="order-change-payment-method-dialog-option-CASH"]'),
    ).not.toBeNull();
    expect(
      host.querySelector('[data-testid="order-change-payment-method-dialog-option-CLICK"]'),
    ).toBeNull();
  });

  it('surfaces a refusal (e.g. an online-paid order changing method) as the pass’s own action notice, dialog left open', async () => {
    const changePaymentMethod = vi
      .fn()
      .mockReturnValue(
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.RESOURCE_CONFLICT,
              409,
              { status: 409, detail: 'PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND' },
              'corr-1',
            ),
        ),
      );
    const { fixture, host } = await renderWithPaymentChange({ changePaymentMethod });

    (
      host.querySelector('[data-testid="kitchen-change-payment-method"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();
    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.kitchen__notice-band')?.textContent).toContain(
      'PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND',
    );
  });
});

/**
 * Row `X.39`: the tenant's late colour on the kitchen board. Only a breached
 * ticket takes it — an at-risk ticket keeps the platform's amber — and a
 * tenant that has set nothing sees the design-system token exactly as before.
 */
describe('KitchenQueuePage: the tenant late colour (row X.39)', () => {
  const MINUTE = 60 * 1000;
  const LATE_TICKET: TicketResponse = {
    ...DELIVERY_TICKET,
    ticketId: 'late',
    sequenceLabel: 'A-001',
    targetReadyAt: new Date(Date.now() - 30 * MINUTE).toISOString(),
  };
  const AT_RISK_TICKET: TicketResponse = {
    ...DELIVERY_TICKET,
    ticketId: 'at-risk',
    sequenceLabel: 'A-002',
    targetReadyAt: new Date(Date.now() + 2 * MINUTE).toISOString(),
  };
  const ON_TIME_TICKET: TicketResponse = {
    ...DELIVERY_TICKET,
    ticketId: 'on-time',
    sequenceLabel: 'A-003',
    targetReadyAt: new Date(Date.now() + 40 * MINUTE).toISOString(),
  };

  /** Mounts the board with the policy read by `read`, without waiting for anything to settle. */
  async function mount(
    read: () => Promise<LatenessPolicy | null>,
  ): Promise<ComponentFixture<KitchenQueuePage>> {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          useValue: {
            board: () => Promise.resolve(board([ON_TIME_TICKET, AT_RISK_TICKET, LATE_TICKET])),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('no summary in this test')) },
        },
        {
          provide: ApiClient,
          useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
        },
        {
          provide: LatenessPolicyApi,
          useValue: {
            read,
            resolve: async () => (await read()) ?? PLATFORM_DEFAULT_LATENESS_POLICY,
          },
        },
        { provide: OrderRevealApi, useValue: { revealLineNote: vi.fn() } },
        {
          provide: DispatchApi,
          useValue: { queue: () => Promise.resolve([]), assign: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: () => Promise.resolve([]) } },
        { provide: Router, useValue: { navigateByUrl: vi.fn() } },
        {
          provide: RealtimeClient,
          useValue: { state: signal('open'), onFrame: () => () => undefined },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    return TestBed.createComponent(KitchenQueuePage);
  }

  async function render(policy: LatenessPolicy): Promise<HTMLElement> {
    const fixture = await mount(() => Promise.resolve(policy));
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function ticketFor(host: HTMLElement, label: string): HTMLElement {
    return Array.from(host.querySelectorAll<HTMLElement>('[data-testid="kitchen-ticket"]')).find(
      (ticket) => ticket.textContent?.includes(label),
    ) as HTMLElement;
  }

  it('paints only the breached ticket in the tenant colour', async () => {
    const host = await render({ ...PLATFORM_DEFAULT_LATENESS_POLICY, lateColour: '#8a3ffc' });

    expect(ticketFor(host, 'A-001').classList).toContain('ticket--danger');
    expect(ticketFor(host, 'A-001').style.getPropertyValue('--q-sla-late')).toBe('#8a3ffc');
    expect(ticketFor(host, 'A-002').classList).toContain('ticket--warning');
    expect(ticketFor(host, 'A-002').style.getPropertyValue('--q-sla-late')).toBe('');
    expect(ticketFor(host, 'A-003').style.getPropertyValue('--q-sla-late')).toBe('');
  });

  it('keeps the design-system token when the tenant has set no colour', async () => {
    const host = await render(PLATFORM_DEFAULT_LATENESS_POLICY);

    expect(ticketFor(host, 'A-001').classList).toContain('ticket--danger');
    expect(ticketFor(host, 'A-001').style.getPropertyValue('--q-sla-late')).toBe('');
  });

  it('applies the tenant AT-RISK window it was given: a longer warning turns a distant ticket amber', async () => {
    const thresholds = {
      atRiskBeforeSeconds: 60 * 60,
      lateAfterSeconds: 0,
      noPromiseFallbackSeconds: 2700,
    };
    const host = await render({
      delivery: thresholds,
      pickup: thresholds,
      dineIn: thresholds,
      lateColour: null,
    });

    expect(ticketFor(host, 'A-003').classList).toContain('ticket--warning');
  });
  describe('follows a policy edit made after the screen opened', () => {
    const GRACE_TWO_HOURS: LatenessPolicy = {
      delivery: {
        atRiskBeforeSeconds: 300,
        lateAfterSeconds: 2 * 60 * 60,
        noPromiseFallbackSeconds: 2700,
      },
      pickup: {
        atRiskBeforeSeconds: 300,
        lateAfterSeconds: 2 * 60 * 60,
        noPromiseFallbackSeconds: 2700,
      },
      dineIn: {
        atRiskBeforeSeconds: 300,
        lateAfterSeconds: 2 * 60 * 60,
        noPromiseFallbackSeconds: 2700,
      },
      lateColour: '#00aa00',
    };

    it('re-reads the policy on the poll: a grace published later stops the breach, and the colour follows', async () => {
      vi.useFakeTimers();
      try {
        const read = vi
          .fn()
          .mockResolvedValueOnce({ ...PLATFORM_DEFAULT_LATENESS_POLICY, lateColour: '#8a3ffc' })
          .mockResolvedValue(GRACE_TWO_HOURS);
        const fixture = await mount(read);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(ticketFor(host, 'A-001').classList).toContain('ticket--danger');
        expect(ticketFor(host, 'A-001').style.getPropertyValue('--q-sla-late')).toBe('#8a3ffc');

        // Past the minute the server caches the policy for, the next 10 s poll reads it again.
        await vi.advanceTimersByTimeAsync(70_000);
        fixture.detectChanges();

        expect(ticketFor(host, 'A-001').classList).not.toContain('ticket--danger');
      } finally {
        vi.useRealTimers();
      }
    });

    it('does not take a failed first read for the loaded policy: the next poll asks again', async () => {
      vi.useFakeTimers();
      try {
        const read = vi.fn().mockResolvedValueOnce(null).mockResolvedValue(GRACE_TWO_HOURS);
        const fixture = await mount(read);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(ticketFor(host, 'A-001').classList).toContain('ticket--danger');

        await vi.advanceTimersByTimeAsync(11_000);
        fixture.detectChanges();

        expect(ticketFor(host, 'A-001').classList).not.toContain('ticket--danger');
        expect(ticketFor(host, 'A-001').style.getPropertyValue('--q-sla-late')).toBe('');
      } finally {
        vi.useRealTimers();
      }
    });
  });
});
