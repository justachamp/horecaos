import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CallCentreApi, CallHourStat } from '../orders/call-centre-api';
import { StaffReportPage } from './staff-report-page';
import { OperatorLeaderboardRowResponse, ProvenanceResponse, ReportingApi } from './reporting-api';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance(): ProvenanceResponse {
  return {
    asOf: '2026-09-13T04:00:00Z',
    closedThrough: '2026-09-12',
    lastCloseCompletedAt: '2026-09-12T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: ['receipt_depth.v1'],
    provisionalMetrics: [],
    openDivergences: 0,
  };
}

function staffRow(
  overrides: Partial<OperatorLeaderboardRowResponse> = {},
): OperatorLeaderboardRowResponse {
  return {
    operatorPrincipalId: '018f6f4e-1000-7000-8000-00000000aaaa',
    principalKind: 'STAFF',
    subject: '018f6f4e-1000-7000-8000-00000000aaaa',
    orderCount: 12,
    grossRevenueSom: 1_200_000,
    netRevenueSom: 1_200_000,
    averageCheckSom: 100_000,
    avgHandlingSeconds: 75,
    deliveryCount: 8,
    pickupCount: 4,
    dineInCount: 0,
    avgItemsPerOrder: 2.5,
    byChannel: [{ channelCode: 'ADMIN', orderCount: 12 }],
    ...overrides,
  };
}

function machineRow(): OperatorLeaderboardRowResponse {
  return staffRow({
    operatorPrincipalId: 'channel:BOT',
    principalKind: 'MACHINE',
    subject: 'BOT',
    orderCount: 30,
    grossRevenueSom: 900_000,
    avgHandlingSeconds: null,
    byChannel: [{ channelCode: 'BOT', orderCount: 30 }],
  });
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('StaffReportPage', () => {
  let fixture: ComponentFixture<StaffReportPage>;
  let reportingApi: {
    operatorLeaderboard: ReturnType<typeof vi.fn>;
    operatorProducts: ReturnType<typeof vi.fn>;
  };
  let callCentreApi: { callStats: ReturnType<typeof vi.fn> };

  async function render(scope: LocationScope | null = SCOPE): Promise<void> {
    reportingApi = {
      operatorLeaderboard: vi
        .fn()
        .mockResolvedValue({ rows: [staffRow(), machineRow()], provenance: provenance() }),
      operatorProducts: vi.fn().mockResolvedValue({
        operatorPrincipalId: staffRow().operatorPrincipalId,
        rows: [
          {
            variantId: 'v1',
            categoryId: null,
            productName: 'Plov',
            totalQuantity: 5,
            totalGrossSom: 250_000,
            totalNetSom: 250_000,
            deliveryQuantity: 5,
            deliveryNetSom: 250_000,
            pickupQuantity: null,
            pickupNetSom: null,
          },
        ],
        maybeMore: false,
        provenance: provenance(),
      }),
    };
    callCentreApi = { callStats: vi.fn().mockResolvedValue([] as readonly CallHourStat[]) };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [StaffReportPage],
      providers: [
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReportingApi, useValue: reportingApi },
        { provide: CallCentreApi, useValue: callCentreApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders a staff row labelled STAFF with a truncated subject, never a bare UUID', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Staff');
    expect(host.textContent).toContain('018f6f4e');
    expect(host.textContent).not.toContain('018f6f4e-1000-7000-8000-00000000aaaa');
  });

  it('types a machine principal as MACHINE with its channel, beside the staff rows', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Automated');
    expect(host.textContent).toContain('BOT');
  });

  it('shows the "no access" state when the operator has no location grant', async () => {
    await render(null);
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('No access to reports');
    expect(reportingApi.operatorLeaderboard).not.toHaveBeenCalled();
  });

  it('drills from a leaderboard row into that operator’s own product mix', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const button = Array.from(host.querySelectorAll('button.link')).find(
      (b) => b.textContent?.trim() === 'View products',
    ) as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reportingApi.operatorProducts).toHaveBeenCalledWith(
      't1',
      expect.objectContaining({ operatorPrincipalId: staffRow().operatorPrincipalId }),
    );
    expect(host.textContent).toContain('Plov');
  });

  it('lazily loads telephony only once the tab is first opened, and rolls up per operator across days', async () => {
    await render();
    expect(callCentreApi.callStats).not.toHaveBeenCalled();

    callCentreApi.callStats.mockResolvedValue([
      {
        hourOfDay: 10,
        operatorPrincipalId: 'staff-1',
        offeredCount: 3,
        answeredCount: 2,
        missedCount: 1,
        transferredCount: 0,
        talkDurationSeconds: 240,
      },
    ] as readonly CallHourStat[]);

    const host = fixture.nativeElement as HTMLElement;
    const telephonyTab = Array.from(host.querySelectorAll('[role="tab"]')).find(
      (el) => el.textContent?.trim() === 'Telephony',
    ) as HTMLButtonElement;
    telephonyTab.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(callCentreApi.callStats).toHaveBeenCalled();
    // The default filter period is "today": exactly one business date, so
    // exactly one call — a wider preset would fan out to one call per day.
    expect(callCentreApi.callStats).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('67%'); // 2 answered of 3 offered, rounded
  });
});
