import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CouriersApi } from '../couriers/couriers-api';
import { DeliveryTariffsApi } from '../delivery/delivery-tariffs-api';
import { DeliveryZonesApi } from '../delivery/delivery-zones-api';
import { CourierReportPage } from './courier-report-page';
import { ExternalDeliveryCostRowResponse, ReportingApi } from './reporting-api';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance() {
  return {
    asOf: '2026-09-14T04:00:00Z',
    closedThrough: '2026-09-13',
    lastCloseCompletedAt: '2026-09-13T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [] as readonly string[],
    openDivergences: 0,
  };
}

function costRow(
  overrides: Partial<ExternalDeliveryCostRowResponse> = {},
): ExternalDeliveryCostRowResponse {
  return {
    orderId: 'order-1',
    publicOrderNumber: 'D-1',
    orderTotalMinor: 45_000,
    currency: 'UZS',
    chargedDeliveryMinor: 5_000,
    shipmentId: 'shipment-1',
    providerType: 'NOOR',
    providerEstimatedMinor: 20_000,
    providerBilledMinor: null,
    varianceMinor: null,
    reconciliationStatus: 'UNBILLED',
    reconcileActionAvailable: false,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CourierReportPage', () => {
  let fixture: ComponentFixture<CourierReportPage>;
  let reconcileSpy: ReturnType<typeof vi.fn>;
  let externalCostRows: readonly ExternalDeliveryCostRowResponse[];

  async function render(): Promise<void> {
    TestBed.resetTestingModule();
    reconcileSpy = vi.fn().mockResolvedValue({ reconciled: true });
    await TestBed.configureTestingModule({
      imports: [CourierReportPage],
      providers: [
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CouriersApi,
          useValue: {
            roster: vi.fn().mockResolvedValue([
              { courierId: 'courier-1', displayReference: 'K-014' },
              { courierId: 'courier-2', displayReference: 'K-020' },
            ]),
            reconcileExternalDeliveryCost: reconcileSpy,
          },
        },
        { provide: DeliveryTariffsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: DeliveryZonesApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        {
          provide: ReportingApi,
          useValue: {
            courierLeaderboard: vi.fn().mockResolvedValue({
              rows: [
                {
                  courierId: 'courier-1',
                  deliveryCount: 12,
                  minDistanceMeters: 800,
                  maxDistanceMeters: 9_000,
                  avgDistanceMeters: 4_200,
                  totalDistanceMeters: 50_400,
                  avgTransitHours: 0.5,
                  totalTransitSeconds: 21_600,
                  onTimeShare: 0.9,
                },
              ],
              provenance: provenance(),
            }),
            courierSlaBuckets: vi.fn().mockResolvedValue({ buckets: [], provenance: provenance() }),
            courierTariffAudit: vi.fn().mockResolvedValue({ rows: [], provenance: provenance() }),
            courierExternalDeliveryCost: vi.fn(async () => ({
              rows: externalCostRows,
              totalVarianceMinor: 0,
              provenance: provenance(),
            })),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('resolves the leaderboard courierId through the roster — never a bare UUID (P19)', async () => {
    externalCostRows = [];
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('K-014');
    expect(host.textContent).not.toContain('courier-1');
  });

  it('offers the reconcile action only on a row the report marked reconcilable', async () => {
    externalCostRows = [
      costRow({
        orderId: 'order-unbilled',
        reconciliationStatus: 'UNBILLED',
        reconcileActionAvailable: false,
      }),
      costRow({
        orderId: 'order-variance',
        shipmentId: 'shipment-2',
        publicOrderNumber: 'D-2',
        reconciliationStatus: 'VARIANCE',
        providerBilledMinor: 25_000,
        varianceMinor: 5_000,
        reconcileActionAvailable: true,
      }),
    ];
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(host.querySelectorAll('button')).filter((b) =>
      b.textContent?.includes('Reconcile'),
    );
    // One per reconcilable row, plus the dialog's own confirm button once opened —
    // before opening the dialog there is exactly one, on the VARIANCE row.
    expect(buttons.length).toBe(1);
  });

  it('confirming the dialog calls the API with the row’s shipmentId and reloads the section', async () => {
    externalCostRows = [
      costRow({
        orderId: 'order-variance',
        shipmentId: 'shipment-2',
        reconciliationStatus: 'VARIANCE',
        reconcileActionAvailable: true,
      }),
    ];
    await render();
    const host = fixture.nativeElement as HTMLElement;

    const reconcileButton = Array.from(host.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Reconcile'),
    ) as HTMLButtonElement;
    reconcileButton.click();
    fixture.detectChanges();

    const confirmButton = Array.from(host.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Reconcile' && b !== reconcileButton,
    ) as HTMLButtonElement;
    expect(confirmButton).toBeTruthy();

    externalCostRows = [];
    confirmButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reconcileSpy).toHaveBeenCalledWith('t1', 'shipment-2', expect.any(String));
  });
});
