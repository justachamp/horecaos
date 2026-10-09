import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { OrderMapPointsApi, OrderMapPointsResponse } from '../../core/api/order-map-points-api';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { applyRegionalFormats, resetRegionalFormats } from '../../core/format/regional-format';
import { I18n } from '../../core/i18n/i18n';
import { RealtimeClient, RealtimeFrame } from '../../core/realtime/realtime-client';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { CourierPositionsApi } from './courier-positions-api';
import { DispatchApi, ExceptionResponse, PlanQueueResponse } from './dispatch-api';
import { DispatchBoardPage } from './dispatch-board-page';
import { MapRegionService } from './map-region';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const UNASSIGNED_PLAN: PlanQueueResponse = {
  planId: 'plan-1',
  orderId: 'order-1',
  status: 'WAITING_TO_SOURCE',
  distanceMeters: 1800,
  customerDeliveryFeeMinor: 12_000,
  currency: 'UZS',
  sourceAt: new Date().toISOString(),
  estimatedReadyAt: new Date().toISOString(),
  version: 1,
  shipment: null,
  destinationLabel: "Yunusobod, Amir Temur ko'chasi",
};

const CARRIED_PLAN: PlanQueueResponse = {
  planId: 'plan-2',
  orderId: 'order-2',
  status: 'ASSIGNED',
  distanceMeters: 900,
  customerDeliveryFeeMinor: 8_000,
  currency: 'UZS',
  sourceAt: new Date().toISOString(),
  estimatedReadyAt: new Date().toISOString(),
  version: 1,
  shipment: {
    shipmentId: 'shipment-1',
    status: 'ASSIGNED',
    sourceType: 'INTERNAL',
    courierId: 'courier-1',
    providerBindingId: null,
    version: 1,
  },
};

const MANUAL_ACTION_PLAN: PlanQueueResponse = {
  planId: 'plan-3',
  orderId: 'order-3',
  status: 'MANUAL_ACTION_REQUIRED',
  distanceMeters: null,
  customerDeliveryFeeMinor: 5_000,
  currency: 'UZS',
  sourceAt: new Date().toISOString(),
  estimatedReadyAt: new Date().toISOString(),
  version: 1,
  shipment: null,
};

const EXCEPTION: ExceptionResponse = {
  exceptionId: 'exception-1',
  reasonCode: 'NO_PROVIDER_AVAILABLE',
  severity: 'HIGH',
  status: 'OPEN',
  detail: 'No courier accepted the booking',
  raisedAt: new Date().toISOString(),
};

const COURIER: RosterEntryResponse = {
  courierId: 'courier-1',
  displayReference: 'K-014',
  status: 'ACTIVE',
  courierTypeId: 'type-1',
  courierTypeName: 'Scooter',
  vehicleClass: 'SCOOTER',
  activeAssignments: 1,
  concurrencyCeiling: 2,
  engagementId: 'engagement-1',
  engagementStatus: 'ACTIVE',
  warningState: 'VALID',
};

const SUSPENDED_COURIER: RosterEntryResponse = {
  ...COURIER,
  courierId: 'courier-2',
  displayReference: 'K-020',
  engagementStatus: 'SUSPENDED_COMPLIANCE',
};

const LAPSED_COURIER: RosterEntryResponse = {
  ...COURIER,
  courierId: 'courier-3',
  displayReference: 'K-030',
  engagementStatus: 'ACTIVE',
  warningState: 'LAPSED',
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const REVEALED: OrderMapPointsResponse = {
  windowFrom: '2026-10-07T00:00:00Z',
  windowTo: '2026-10-08T00:00:00Z',
  points: [
    {
      orderId: 'o-open',
      publicOrderNumber: 'F-100',
      status: 'FULFILLING',
      createdAt: '2026-10-07T08:00:00Z',
      latitude: 41.31,
      longitude: 69.24,
    },
    {
      orderId: 'o-done',
      publicOrderNumber: 'F-099',
      status: 'COMPLETED',
      createdAt: '2026-10-07T07:00:00Z',
      latitude: 41.3,
      longitude: 69.22,
    },
  ],
  withoutPoint: 1,
  truncated: false,
};

describe('DispatchBoardPage', () => {
  let fixture: ComponentFixture<DispatchBoardPage>;
  let mapProvider: NullMapProvider;
  let positions: { fleet: ReturnType<typeof vi.fn> };
  let orderPoints: { reveal: ReturnType<typeof vi.fn> };

  afterEach(() => resetRegionalFormats());
  let dispatchApi: {
    queue: ReturnType<typeof vi.fn>;
    assign: ReturnType<typeof vi.fn>;
    unassign: ReturnType<typeof vi.fn>;
    exceptions: ReturnType<typeof vi.fn>;
    externalPartners: ReturnType<typeof vi.fn>;
    externalQuote: ReturnType<typeof vi.fn>;
    externalBook: ReturnType<typeof vi.fn>;
    cancelShipment: ReturnType<typeof vi.fn>;
  };
  /** Every listener `RealtimeClient.onFrame` was handed — the fake stub below never fires one on its own; a test dispatches a frame by calling one of these directly. */
  let frameListeners: Array<(frame: RealtimeFrame) => void>;
  let realtimeState: ReturnType<
    typeof signal<'connecting' | 'open' | 'reconnecting' | 'unavailable'>
  >;

  function fakeRealtimeClient(): {
    onFrame: ReturnType<typeof vi.fn>;
    state: typeof realtimeState;
  } {
    frameListeners = [];
    realtimeState = signal('open');
    return {
      onFrame: vi.fn((listener: (frame: RealtimeFrame) => void) => {
        frameListeners.push(listener);
        return () => undefined;
      }),
      state: realtimeState,
    };
  }

  async function render(
    plans: readonly PlanQueueResponse[] = [UNASSIGNED_PLAN],
    fleet: readonly RosterEntryResponse[] = [COURIER],
  ): Promise<void> {
    dispatchApi = {
      queue: vi.fn().mockResolvedValue(plans),
      assign: vi.fn(),
      unassign: vi.fn(),
      exceptions: vi.fn().mockResolvedValue([]),
      externalPartners: vi.fn().mockResolvedValue([]),
      externalQuote: vi.fn(),
      externalBook: vi.fn(),
      cancelShipment: vi.fn(),
    };
    mapProvider = new NullMapProvider();
    positions = {
      fleet: vi.fn().mockResolvedValue({
        pins: [
          {
            courierId: 'courier-1',
            latitude: 41.32,
            longitude: 69.25,
            accuracyMeters: 10,
            activeAssignmentCount: 1,
            capturedAt: new Date().toISOString(),
          },
        ],
        withoutPin: [],
      }),
    };
    orderPoints = { reveal: vi.fn().mockResolvedValue(REVEALED) };
    await TestBed.configureTestingModule({
      imports: [DispatchBoardPage],
      providers: [
        provideNullMapProvider(mapProvider),
        { provide: CourierPositionsApi, useValue: positions },
        { provide: OrderMapPointsApi, useValue: orderPoints },
        {
          provide: MapRegionService,
          useValue: { ensureLoaded: () => Promise.resolve(), primary: () => null },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DispatchApi, useValue: dispatchApi },
        { provide: CouriersApi, useValue: { roster: vi.fn().mockResolvedValue(fleet) } },
        { provide: ApiClient, useValue: { get: () => of({ value: [], version: null }) } },
        { provide: RealtimeClient, useValue: fakeRealtimeClient() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DispatchBoardPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lays out an unassigned plan under the pool column and a courier column showing its load', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="dispatch-card"]')).toHaveLength(1);
    expect(host.textContent).toContain('K-014');
    // The courier column shows activeAssignments/concurrencyCeiling as its load.
    expect(host.textContent).toContain('1 / 2');
    const unassignedColumn = host.querySelector('[data-column-id="__unassigned__"]');
    expect(unassignedColumn?.querySelector('[data-testid="dispatch-card"]')).not.toBeNull();
  });

  it('writes the delivery fee on a card the way the brand chose (row 10.12)', async () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });
    await render();
    const row = (fixture.nativeElement as HTMLElement).querySelector('.dispatch-card__row.q-tnum');

    expect(row?.textContent?.replace(/\s/g, ' ')).toContain('UZS 12,000');
  });

  it('shows the non-PII destination label on the card, and nothing when the plan carries none (row 3.1)', async () => {
    await render([UNASSIGNED_PLAN, CARRIED_PLAN]);
    const host = fixture.nativeElement as HTMLElement;

    const labels = host.querySelectorAll('[data-testid="dispatch-card-destination"]');
    expect(labels).toHaveLength(1);
    expect(labels[0].textContent?.trim()).toBe("Yunusobod, Amir Temur ko'chasi");
    // The full address never reaches this card at all — only the masked
    // label ordering computed, and CARRIED_PLAN carries none.
    expect(labels[0].textContent).not.toMatch(/\d/);
  });

  it("places a carried plan under its courier's column", async () => {
    await render([CARRIED_PLAN]);
    const host = fixture.nativeElement as HTMLElement;

    const courierColumn = host.querySelector('[data-column-id="courier-1"]');
    expect(courierColumn?.querySelector('[data-testid="dispatch-card"]')).not.toBeNull();
    expect(courierColumn?.querySelector('[data-testid="dispatch-card-exception"]')).toBeNull();
  });

  it('fetches exceptions only for a MANUAL_ACTION_REQUIRED plan and shows the reason on its card', async () => {
    await render([MANUAL_ACTION_PLAN]);
    dispatchApi.exceptions.mockResolvedValueOnce([EXCEPTION]);

    // A second, manual refresh (the visible button) picks up the exceptions mock above.
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('.dispatch__refresh') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.exceptions).toHaveBeenCalledWith(SCOPE, 'plan-3');
    expect(host.querySelector('[data-testid="dispatch-card-exception"]')?.textContent).toContain(
      'No courier accepted the booking',
    );
  });

  // -------------------------------------------------- row 3.1: ADR 0045 accelerator

  it('refreshes at once on a DISPATCH_BOARD frame, the ADR 0045 accelerator', async () => {
    await render();
    dispatchApi.queue.mockClear();

    for (const listener of frameListeners) {
      listener({
        kind: 'signal',
        channel: 'dispatch_board',
        scope: 'LOCATION:l1',
        resourceType: 'DeliveryPlan',
        resourceId: 'plan-1',
        version: 2,
        occurredAt: '2026-09-25T09:00:00Z',
      });
    }
    await flushMicrotasks();

    expect(dispatchApi.queue).toHaveBeenCalledWith(SCOPE);
  });

  it('ignores a frame on a channel this board does not care about', async () => {
    await render();
    dispatchApi.queue.mockClear();

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

    expect(dispatchApi.queue).not.toHaveBeenCalled();
  });

  it('resumes without gaps: a resync frame (a reconnect after a drop) re-reads the whole board', async () => {
    await render();
    dispatchApi.queue.mockClear();

    for (const listener of frameListeners) {
      listener({ kind: 'resync' });
    }
    await flushMicrotasks();

    expect(dispatchApi.queue).toHaveBeenCalledWith(SCOPE);
  });

  it('shows the connection-state banner’s reconnecting text when the transport degrades', async () => {
    await render();
    realtimeState.set('reconnecting');
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Reconnecting');
  });

  it('stamps the board with when it was last refreshed', async () => {
    await render();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="dispatch-updated-at"]'),
    ).not.toBeNull();
  });

  /**
   * Row 3.1 review (dispatch-board refresh race): `refresh()` is triggered
   * by four independent, uncoordinated sources — the 10s poll, a
   * DISPATCH_BOARD/resync realtime frame, `manualRefresh()`, and the
   * assign/unassign `finally` block — any of which can overlap. An older
   * `refresh()` call's response landing after a newer one's must not
   * silently overwrite the newer, already-committed board state.
   */
  it('drops a refresh() response that resolves after a newer refresh() has already replaced the board', async () => {
    let resolveSlow: ((plans: readonly PlanQueueResponse[]) => void) | undefined;
    let resolveFast: ((plans: readonly PlanQueueResponse[]) => void) | undefined;

    await render([UNASSIGNED_PLAN]);

    dispatchApi.queue.mockClear();
    dispatchApi.queue
      .mockImplementationOnce(
        () =>
          new Promise<readonly PlanQueueResponse[]>((resolve) => {
            resolveSlow = resolve;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<readonly PlanQueueResponse[]>((resolve) => {
            resolveFast = resolve;
          }),
      );

    const host = fixture.nativeElement as HTMLElement;
    // A dispatcher on a slow connection clicks Refresh — its request hangs.
    (host.querySelector('.dispatch__refresh') as HTMLButtonElement).click();
    await flushMicrotasks();
    expect(dispatchApi.queue).toHaveBeenCalledTimes(1);

    // Before it returns, another operator's assign fires a DISPATCH_BOARD
    // signal, triggering a second, independent refresh() over the accelerator.
    for (const listener of frameListeners) {
      listener({
        kind: 'signal',
        channel: 'dispatch_board',
        scope: 'LOCATION:l1',
        resourceType: 'DeliveryPlan',
        resourceId: 'plan-2',
        version: 2,
        occurredAt: '2026-09-25T09:00:00Z',
      });
    }
    await flushMicrotasks();
    expect(dispatchApi.queue).toHaveBeenCalledTimes(2);

    // Ordinary network jitter: the SECOND refresh's fetch, started later,
    // resolves FIRST — with the correct, newly-assigned state.
    expect(resolveFast).toBeDefined();
    resolveFast!([CARRIED_PLAN]);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      host
        .querySelector('[data-column-id="courier-1"]')
        ?.querySelector('[data-testid="dispatch-card"]'),
    ).not.toBeNull();

    // ...and only THEN does the FIRST, slower refresh's stale response
    // land. It must be dropped, not silently revert the board back to
    // unassigned.
    expect(resolveSlow).toBeDefined();
    resolveSlow!([UNASSIGNED_PLAN]);
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      host
        .querySelector('[data-column-id="courier-1"]')
        ?.querySelector('[data-testid="dispatch-card"]'),
    ).not.toBeNull();
    expect(
      host
        .querySelector('[data-column-id="__unassigned__"]')
        ?.querySelector('[data-testid="dispatch-card"]'),
    ).toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DispatchBoardPage],
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
          provide: DispatchApi,
          useValue: { queue: vi.fn(), assign: vi.fn(), unassign: vi.fn(), exceptions: vi.fn() },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn() } },
        { provide: ApiClient, useValue: { get: () => of({ value: [], version: null }) } },
        { provide: RealtimeClient, useValue: fakeRealtimeClient() },
        { provide: CourierPositionsApi, useValue: { fleet: vi.fn() } },
        {
          provide: MapRegionService,
          useValue: { ensureLoaded: () => Promise.resolve(), primary: () => null },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DispatchBoardPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="dispatch-denied"]'),
    ).not.toBeNull();
  });

  it("surfaces a refused drop in the notice band with the server's own reason", async () => {
    await render();

    fixture.componentInstance['onRejected']({
      card: UNASSIGNED_PLAN,
      fromColumnId: '__unassigned__',
      toColumnId: 'courier-1',
      reason: 'STALE_VERSION',
    });
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('STALE_VERSION');
  });

  it('calls DispatchApi.assign with the exact scope, plan and courier when a drop is accepted', async () => {
    await render();
    dispatchApi.assign.mockResolvedValue({ applied: true, reason: null });

    const outcome = await fixture.componentInstance['assignFn'](UNASSIGNED_PLAN, 'courier-1');

    expect(dispatchApi.assign).toHaveBeenCalledWith(
      SCOPE,
      'plan-1',
      'courier-1',
      1,
      'OPERATIONS_MANUAL_ASSIGN',
    );
    expect(outcome.applied).toBe(true);
  });

  it("marks a suspended or compliance-lapsed courier's column as not accepting drops, with the reason shown", async () => {
    await render([UNASSIGNED_PLAN], [COURIER, SUSPENDED_COURIER, LAPSED_COURIER]);
    const host = fixture.nativeElement as HTMLElement;

    const activeColumn = host.querySelector('[data-column-id="courier-1"]');
    expect(activeColumn?.classList.contains('q-board-column--ineligible')).toBe(false);
    expect(activeColumn?.querySelector('[data-testid="board-column-ineligible"]')).toBeNull();

    const suspendedColumn = host.querySelector('[data-column-id="courier-2"]');
    expect(suspendedColumn?.classList.contains('q-board-column--ineligible')).toBe(true);
    expect(
      suspendedColumn?.querySelector('[data-testid="board-column-ineligible"]')?.textContent,
    ).toContain('Suspended (compliance)');

    const lapsedColumn = host.querySelector('[data-column-id="courier-3"]');
    expect(lapsedColumn?.classList.contains('q-board-column--ineligible')).toBe(true);
    expect(
      lapsedColumn?.querySelector('[data-testid="board-column-ineligible"]')?.textContent,
    ).toContain('Compliance document lapsed');
  });

  it('manually unassigns a carried plan and shows the refusal reason when the compare-and-set loses', async () => {
    await render([CARRIED_PLAN]);
    dispatchApi.unassign.mockResolvedValue({
      applied: false,
      planStatus: 'ASSIGNED',
      planVersion: 1,
      reason: 'STALE_VERSION',
    });

    const host = fixture.nativeElement as HTMLElement;
    const button = host.querySelector('.dispatch-card__unassign') as HTMLButtonElement;
    button.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.unassign).toHaveBeenCalledWith(SCOPE, 'plan-2', 1, 'OPERATIONS_UNASSIGN');
    expect(host.textContent).toContain('STALE_VERSION');
  });

  // ---------------------------------------------------------- gap map row 1.2f

  it('offers «Вызвать курьера» on an unassigned pool card, over the plan-keyed DispatchApi path', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    // `render()` builds `dispatchApi` fresh with empty defaults -- every
    // per-test mock behaviour is configured after it returns, exactly like
    // the exceptions test above configures `dispatchApi.exceptions`.
    dispatchApi.externalPartners.mockResolvedValue([
      { bindingId: 'binding-1', providerType: 'YANDEX', supportsHold: false },
    ]);
    dispatchApi.externalQuote.mockResolvedValue({
      priced: true,
      quoteId: 'quote-1',
      bindingId: 'binding-1',
      providerType: 'YANDEX',
      priceMinor: 12_000,
      currency: 'UZS',
      customerDeliveryFeeMinor: 12_000,
      deltaMinor: 0,
    });
    dispatchApi.externalBook.mockResolvedValue({
      applied: true,
      abandoned: false,
      planVersion: 2,
      shipmentId: 'shipment-9',
    });

    (
      host.querySelector('[data-testid="dispatch-card-external-courier"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.externalPartners).toHaveBeenCalledWith(SCOPE, 'plan-1');
    expect(host.querySelector('[data-testid="external-courier-dialog"]')).not.toBeNull();

    (host.querySelector('[data-testid="external-courier-quote"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.externalQuote).toHaveBeenCalledWith(SCOPE, 'plan-1', 'binding-1');

    (host.querySelector('[data-testid="external-courier-accept"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.externalBook).toHaveBeenCalledWith(
      SCOPE,
      'plan-1',
      'binding-1',
      'quote-1',
      'ACCEPT',
      'OPERATIONS_DISPATCH_EXTERNAL_BOOKING_ACCEPT',
    );
    expect(host.querySelector('[data-testid="external-courier-dialog"]')).toBeNull();
  });

  it("renders a PARTNER shipment's own booking state on its card", async () => {
    const partnerPlan: PlanQueueResponse = {
      ...CARRIED_PLAN,
      planId: 'plan-4',
      shipment: {
        shipmentId: 'shipment-4',
        status: 'PICKED_UP',
        sourceType: 'PARTNER',
        courierId: null,
        providerBindingId: 'binding-1',
        version: 2,
      },
    };
    await render([partnerPlan]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="dispatch-card-provider"]')?.textContent).toContain(
      'Picked up',
    );
  });

  // ---------------------------------------------------------- gap map row 1.2g

  it('cancels a carried shipment through DispatchApi against the shipment id and version', async () => {
    await render([CARRIED_PLAN]);
    dispatchApi.cancelShipment.mockResolvedValue({ applied: true, outcome: 'INTERNAL_CANCELLED' });
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="dispatch-card-cancel-shipment"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="dispatch-cancel-shipment-dialog"]')).not.toBeNull();

    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.cancelShipment).toHaveBeenCalledWith(
      SCOPE,
      'shipment-1',
      1,
      'OPERATIONS_DISPATCH_SHIPMENT_CANCEL',
    );
    expect(host.querySelector('[data-testid="dispatch-cancel-shipment-dialog"]')).toBeNull();
  });

  it('surfaces a refused cancel (ALREADY_DELIVERED) as a notice, never a thrown error', async () => {
    await render([CARRIED_PLAN]);
    dispatchApi.cancelShipment.mockResolvedValue({
      applied: false,
      conflictReason: 'ALREADY_DELIVERED',
    });
    const host = fixture.nativeElement as HTMLElement;

    (
      host.querySelector('[data-testid="dispatch-card-cancel-shipment"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('ALREADY_DELIVERED');
  });

  it('offers no cancel-shipment action once the shipment is already CANCELLED', async () => {
    const cancelledPlan: PlanQueueResponse = {
      ...CARRIED_PLAN,
      shipment: { ...CARRIED_PLAN.shipment!, status: 'CANCELLED' },
    };
    await render([cancelledPlan]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="dispatch-card-cancel-shipment"]')).toBeNull();
  });

  // ---------------------------------------------------------- gap map row 3.1

  it('selects several unassigned pool cards and bulk-assigns them to one courier, with per-item outcomes', async () => {
    const secondUnassigned: PlanQueueResponse = {
      ...UNASSIGNED_PLAN,
      planId: 'plan-5',
      version: 1,
    };
    await render([UNASSIGNED_PLAN, secondUnassigned]);
    dispatchApi.assign.mockImplementation((_scope, planId: string) =>
      Promise.resolve(
        planId === 'plan-5'
          ? {
              applied: false,
              planStatus: 'WAITING_TO_SOURCE',
              planVersion: 1,
              reason: 'STALE_VERSION',
            }
          : { applied: true, planStatus: 'ASSIGNED', planVersion: 2 },
      ),
    );
    const host = fixture.nativeElement as HTMLElement;

    const checkboxes = host.querySelectorAll<HTMLInputElement>(
      '[data-testid="dispatch-card-select"]',
    );
    expect(checkboxes).toHaveLength(2);
    checkboxes[0].click();
    checkboxes[1].click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="dispatch-bulk-bar"]')?.textContent).toContain('2');

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="dispatch-bulk-courier-select"]',
    )!;
    select.value = 'courier-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="dispatch-bulk-assign"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(dispatchApi.assign).toHaveBeenCalledWith(
      SCOPE,
      'plan-1',
      'courier-1',
      1,
      'OPERATIONS_DISPATCH_BULK_ASSIGN',
    );
    expect(dispatchApi.assign).toHaveBeenCalledWith(
      SCOPE,
      'plan-5',
      'courier-1',
      1,
      'OPERATIONS_DISPATCH_BULK_ASSIGN',
    );
    expect(host.querySelector('[data-testid="dispatch-bulk-result"]')?.textContent).toContain('1');
    // The selection clears once submitted, whatever each item's own outcome.
    expect(host.querySelector('[data-testid="dispatch-bulk-bar"]')).toBeNull();
  });

  it('the bulk-assign button stays disabled until both a selection and a courier are chosen', async () => {
    await render([UNASSIGNED_PLAN]);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="dispatch-card-select"]') as HTMLInputElement).click();
    fixture.detectChanges();

    expect(
      (host.querySelector('[data-testid="dispatch-bulk-assign"]') as HTMLButtonElement).disabled,
    ).toBe(true);

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="dispatch-bulk-courier-select"]',
    )!;
    select.value = 'courier-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      (host.querySelector('[data-testid="dispatch-bulk-assign"]') as HTMLButtonElement).disabled,
    ).toBe(false);
  });

  it('offers no bulk-select checkbox on a carried card, but does on an unassigned MANUAL_ACTION_REQUIRED one (the same eligibility drag-assign already uses)', async () => {
    await render([CARRIED_PLAN, MANUAL_ACTION_PLAN]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="dispatch-card-select"]')).toHaveLength(1);
  });

  // ------------------------------------------------ ADR 0145, row 3.1: the map pane

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const press = (testId: string): void =>
    host().querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
  const settle = async (): Promise<void> => {
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  };

  it('keeps the map closed until it is asked for, and reads no position and no doorstep before then', async () => {
    await render();

    expect(host().querySelector('[data-testid="dispatch-map"]')).toBeNull();
    expect(positions.fleet).not.toHaveBeenCalled();
    expect(orderPoints.reveal).not.toHaveBeenCalled();
  });

  it('opens the map with the couriers on it by the reference the roster shows, and no doorsteps yet', async () => {
    await render([CARRIED_PLAN]);

    press('dispatch-map-toggle');
    await settle();

    expect(positions.fleet).toHaveBeenCalledWith(SCOPE);
    expect(mapProvider.map.livePins).toHaveLength(1);
    expect(mapProvider.map.livePins[0].tone).toBe('courier');
    expect(mapProvider.map.livePins[0].label).toBe('K-014');
    expect(orderPoints.reveal).not.toHaveBeenCalled();
  });

  it('opens the day’s delivery orders only when the dispatcher presses the button, stating this screen as the purpose', async () => {
    await render();
    press('dispatch-map-toggle');
    await settle();

    press('order-points-reveal');
    await settle();

    expect(orderPoints.reveal).toHaveBeenCalledTimes(1);
    expect(orderPoints.reveal).toHaveBeenCalledWith(
      SCOPE,
      expect.stringContaining('dispatch board'),
    );
    const orderPins = mapProvider.map.livePins.filter((pin) => pin.tone === 'order');
    expect(orderPins).toHaveLength(1);
    expect(orderPins[0].label).toContain('F-100');
    expect(host().querySelector('[data-testid="order-points-without"]')).not.toBeNull();
  });

  it('shows finished orders too, as closed pins, when asked', async () => {
    await render();
    press('dispatch-map-toggle');
    await settle();
    press('order-points-reveal');
    await settle();
    expect(mapProvider.map.livePins.some((pin) => pin.tone === 'closed')).toBe(false);

    const toggle = host().querySelector<HTMLInputElement>(
      '[data-testid="order-points-open-only"]',
    )!;
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change'));
    await settle();

    expect(mapProvider.map.livePins.filter((pin) => pin.tone === 'closed')).toHaveLength(1);
  });

  it('keeps the couriers moving with the board but does not open the doorsteps again on a refresh', async () => {
    await render();
    press('dispatch-map-toggle');
    await settle();
    press('order-points-reveal');
    await settle();
    const reads = positions.fleet.mock.calls.length;

    press('dispatch-map-toggle');
    press('dispatch-map-toggle');
    await settle();
    host().querySelector<HTMLButtonElement>('.dispatch__refresh')!.click();
    await settle();

    expect(positions.fleet.mock.calls.length).toBeGreaterThan(reads);
    expect(orderPoints.reveal).toHaveBeenCalledTimes(1);
  });

  it('says so, and keeps the couriers, when the caller may not open the day’s doorsteps', async () => {
    await render([CARRIED_PLAN]);
    orderPoints.reveal.mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
    press('dispatch-map-toggle');
    await settle();

    press('order-points-reveal');
    await settle();

    expect(host().querySelector('[data-testid="order-points-denied"]')).not.toBeNull();
    expect(mapProvider.map.livePins.filter((pin) => pin.tone === 'courier')).toHaveLength(1);
  });

  it('stops reading positions once the map is hidden', async () => {
    await render();
    press('dispatch-map-toggle');
    await settle();
    press('dispatch-map-toggle');
    await settle();
    const reads = positions.fleet.mock.calls.length;

    host().querySelector<HTMLButtonElement>('.dispatch__refresh')!.click();
    await settle();

    expect(positions.fleet.mock.calls.length).toBe(reads);
  });
});
