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
    expect(suspendedColumn?.querySelector('[data-testid="board-column-ineligible"]')?.textContent).toContain(
      'Suspended (compliance)',
    );

    const lapsedColumn = host.querySelector('[data-column-id="courier-3"]');
    expect(lapsedColumn?.classList.contains('q-board-column--ineligible')).toBe(true);
    expect(lapsedColumn?.querySelector('[data-testid="board-column-ineligible"]')?.textContent).toContain(
      'Compliance document lapsed',
    );
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
});
