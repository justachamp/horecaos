import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { RosterEntryResponse, CouriersApi } from '../couriers/couriers-api';
import { DispatchApi, PlanQueueResponse } from '../delivery/dispatch-api';
import { OrderDeliveryApi } from '../orders/order-delivery-api';
import { OrderRevealApi } from '../orders/order-reveal-api';
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

  async function render(customBoard?: BoardResponse): Promise<void> {
    boardResult = customBoard ?? board([DELIVERY_TICKET]);
    revealLineNote = vi.fn(() => of({ lineId: 'line-1', note: 'без лука' }));
    dispatchQueue = vi.fn(() => Promise.resolve<readonly PlanQueueResponse[]>([]));
    dispatchAssign = vi.fn(() =>
      Promise.resolve({ applied: true, planStatus: 'ASSIGNED', planVersion: 2 }),
    );
    roster = vi.fn(() => Promise.resolve<readonly RosterEntryResponse[]>([]));
    navigateByUrl = vi.fn();

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
            board: () => Promise.resolve(boardResult),
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
        { provide: OrderRevealApi, useValue: { revealLineNote } },
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
  }

  it('renders the live ticket with its channel and fulfilment mode', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="kitchen-ticket"]')).toHaveLength(1);
    expect(host.textContent).toContain('A-014');
    expect(host.textContent).toContain('telegram-bot');
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

  it('renders the external partner state on the ticket once the plan resolves a PARTNER shipment, and hides the call-external-courier button', async () => {
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
    expect(host.querySelector('[data-testid="kitchen-external-courier-state"]')).toBeNull();

    // Nothing resolves the plan eagerly (P16's own "no orderId-keyed board
    // join" limitation) -- opening the in-house picker once is what learns
    // the ticket's plan, the same seam `resolvePlanForTicket` shares.
    const assignButton = host.querySelector(
      '[data-testid="kitchen-assign-courier"]',
    ) as HTMLButtonElement;
    assignButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const state = host.querySelector('[data-testid="kitchen-external-courier-state"]');
    expect(state).not.toBeNull();
    expect(state?.textContent).toContain('External partner assigned');
    expect(host.querySelector('[data-testid="kitchen-external-courier"]')).toBeNull();
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
});
