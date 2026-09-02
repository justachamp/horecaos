import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { DispatchApi, PlanQueueResponse } from './dispatch-api';
import { DispatchBoardPage } from './dispatch-board-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const PLAN: PlanQueueResponse = {
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

const COURIER: RosterEntryResponse = {
  courierId: 'courier-1',
  displayReference: 'K-014',
  status: 'ACTIVE',
  courierTypeId: 'type-1',
  courierTypeName: 'Scooter',
  vehicleClass: 'SCOOTER',
  activeAssignments: 0,
  concurrencyCeiling: 2,
  engagementId: 'engagement-1',
  engagementStatus: 'ACTIVE',
  warningState: 'VALID',
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DispatchBoardPage', () => {
  let fixture: ComponentFixture<DispatchBoardPage>;

  async function render(): Promise<void> {
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
        { provide: DispatchApi, useValue: { queue: vi.fn().mockResolvedValue([PLAN]) } },
        { provide: CouriersApi, useValue: { roster: vi.fn().mockResolvedValue([COURIER]) } },
        { provide: ApiClient, useValue: { get: () => of({ value: [], version: null }) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DispatchBoardPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists an unassigned plan and the fleet rail', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="dispatch-row"]')).toHaveLength(1);
    expect(host.querySelectorAll('[data-testid="fleet-card"]')).toHaveLength(1);
    expect(host.textContent).toContain('K-014');
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
        { provide: DispatchApi, useValue: { queue: vi.fn() } },
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
});
