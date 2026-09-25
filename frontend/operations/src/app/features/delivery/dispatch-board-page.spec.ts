import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { DispatchApi, ExceptionResponse, PlanQueueResponse } from './dispatch-api';
import { DispatchBoardPage } from './dispatch-board-page';

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

describe('DispatchBoardPage', () => {
  let fixture: ComponentFixture<DispatchBoardPage>;
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
    await TestBed.configureTestingModule({
      imports: [DispatchBoardPage],
      providers: [
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
});
