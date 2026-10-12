import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CallCentreApi, CallCoverage, CallHourStat } from '../orders/call-centre-api';
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

function coverage(overrides: Partial<CallCoverage> = {}): CallCoverage {
  return {
    connection: 'CONNECTED',
    activeInstallationCount: 1,
    firstCallEventAt: null,
    lastCallEventAt: null,
    closedDaysWithCalls: 0,
    nextCloseAt: '2026-09-13T20:00:00Z',
    ...overrides,
  };
}

/** One hour of one operator's calls, measured (ADR 0175) unless the overrides null the call-level fields. */
function callHour(overrides: Partial<CallHourStat> = {}): CallHourStat {
  return {
    hourOfDay: 10,
    operatorPrincipalId: 'staff-1',
    offeredCount: 0,
    answeredCount: 1,
    missedCount: 0,
    transferredCount: 0,
    talkDurationSeconds: 90,
    answeredCallCount: 1,
    ringSecondsTotal: 7,
    ringSampleCount: 1,
    ringSecondsMax: 7,
    convertedCount: 1,
    ...overrides,
  };
}

/** A pre-migration row: the old counts only, every ADR 0175 field null. */
function unmeasuredHour(overrides: Partial<CallHourStat> = {}): CallHourStat {
  return callHour({
    answeredCallCount: null,
    ringSecondsTotal: null,
    ringSampleCount: null,
    ringSecondsMax: null,
    convertedCount: null,
    ...overrides,
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
  let callCentreApi: {
    callStats: ReturnType<typeof vi.fn>;
    callCoverage: ReturnType<typeof vi.fn>;
  };

  async function render(
    scope: LocationScope | null = SCOPE,
    leaderboard: readonly OperatorLeaderboardRowResponse[] = [staffRow(), machineRow()],
    coverageResult: CallCoverage | Error = coverage(),
  ): Promise<void> {
    // ReportsFilterState restores its period and range from the URL on
    // construction, and the test worker's window.location outlives a spec
    // file: a sibling reports spec that left a ten-day custom range in the
    // query string made this page fan out to ten callStats calls instead of
    // one on the CI runner (2026-09-21), while passing wherever the files
    // happened to be scheduled apart. Every sibling reports spec already
    // resets the URL for this reason; this one never did.
    history.replaceState(null, '', '/statistics/staff');
    reportingApi = {
      operatorLeaderboard: vi
        .fn()
        .mockResolvedValue({ rows: leaderboard, provenance: provenance() }),
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
    callCentreApi = {
      callStats: vi.fn().mockResolvedValue([] as readonly CallHourStat[]),
      // Connected by default, so the telephony specs written before ADR 0175 still find their tab.
      callCoverage:
        coverageResult instanceof Error
          ? vi.fn().mockRejectedValue(coverageResult)
          : vi.fn().mockResolvedValue(coverageResult),
    };

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

  it('shows the name the tenant keeps for a staff row, and not the subject, once the platform sends one (ADR 0139)', async () => {
    await render(SCOPE, [staffRow({ displayName: 'Aziza Karimova' }), machineRow()]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="staff-report-name"]')?.textContent).toContain(
      'Aziza Karimova',
    );
    expect(host.textContent).not.toContain('018f6f4e');
  });

  it('keeps the short subject for a staff row the tenant has no name for', async () => {
    await render(SCOPE, [staffRow({ displayName: null })]);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="staff-report-name"]')).toBeNull();
    expect(host.textContent).toContain('018f6f4e');
  });

  it('names the operator on the product drill-down', async () => {
    await render(SCOPE, [staffRow({ displayName: 'Aziza Karimova' })]);
    const host = fixture.nativeElement as HTMLElement;
    (
      Array.from(host.querySelectorAll('button.link')).find(
        (b) => b.textContent?.trim() === 'View products',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('Aziza Karimova');
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

  async function openTelephony(): Promise<HTMLElement> {
    const host = fixture.nativeElement as HTMLElement;
    const tab = telephonyTab(host);
    expect(tab).toBeDefined();
    tab!.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return host;
  }

  function telephonyTab(host: HTMLElement): HTMLButtonElement | undefined {
    return Array.from(host.querySelectorAll<HTMLButtonElement>('[role="tab"]')).find(
      (el) => el.textContent?.trim() === 'Telephony',
    );
  }

  function text(host: HTMLElement, testId: string): string {
    return (host.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();
  }

  it("names a telephony row's operator from the leaderboard when the same person is on it", async () => {
    await render(SCOPE, [
      staffRow({
        displayName: 'Aziza Karimova',
        subject: 'staff-1',
        operatorPrincipalId: 'staff-1',
      }),
    ]);
    callCentreApi.callStats.mockResolvedValue([
      callHour({ operatorPrincipalId: 'staff-1', offeredCount: 3, answeredCount: 2 }),
      callHour({
        hourOfDay: 11,
        operatorPrincipalId: 'staff-2-with-a-long-subject',
        offeredCount: 1,
        answeredCount: 1,
      }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    const operators = Array.from(
      host.querySelectorAll('[data-testid="staff-report-telephony-operator"]'),
    ).map((cell) => cell.textContent?.trim());
    expect(operators).toEqual(['Aziza Karimova', 'staff-2-']);
  });

  it('lazily loads telephony only once the tab is first opened, and puts the answer rate on the branch total', async () => {
    await render();
    expect(callCentreApi.callStats).not.toHaveBeenCalled();

    callCentreApi.callStats.mockResolvedValue([
      callHour({ offeredCount: 3, answeredCount: 2 }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(callCentreApi.callStats).toHaveBeenCalled();
    // The default filter period is "today": exactly one business date, so
    // exactly one call — a wider preset would fan out to one call per day.
    expect(callCentreApi.callStats).toHaveBeenCalledTimes(1);
    // 2 answered of 3 offered, rounded: where both counts exist, on the branch line.
    expect(text(host, 'staff-report-telephony-answer-rate')).toBe('67%');
  });

  // ------------------------------------------------------------ ADR 0175 states

  it('does not offer the Telephony tab to a branch with no provider and no closed call history', async () => {
    await render(
      SCOPE,
      undefined,
      coverage({ connection: 'NOT_CONNECTED', activeInstallationCount: 0 }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(telephonyTab(host)).toBeUndefined();
    const labels = Array.from(host.querySelectorAll('[role="tab"]')).map((t) =>
      t.textContent?.trim(),
    );
    expect(labels).toEqual(['Leaderboard', 'Products']);
  });

  it('still offers the tab to a branch whose provider is gone but whose closed days hold calls', async () => {
    await render(
      SCOPE,
      undefined,
      coverage({ connection: 'NOT_CONNECTED', activeInstallationCount: 0, closedDaysWithCalls: 3 }),
    );

    expect(telephonyTab(fixture.nativeElement as HTMLElement)).toBeDefined();
  });

  it('keeps the tab when the coverage read fails, because it cannot tell', async () => {
    await render(SCOPE, undefined, new Error('boom'));

    expect(telephonyTab(fixture.nativeElement as HTMLElement)).toBeDefined();
  });

  it('lands a deep link to the telephony tab on the leaderboard when the tab is not offered', async () => {
    await render(
      SCOPE,
      undefined,
      coverage({ connection: 'NOT_CONNECTED', activeInstallationCount: 0 }),
    );
    // `render` resets the URL before it builds the page (the sibling specs' isolation), so the deep
    // link is set after it and the page rebuilt here.
    history.replaceState(null, '', '/statistics/staff?rp_tab=telephony');
    fixture = TestBed.createComponent(StaffReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(telephonyTab(host)).toBeUndefined();
    expect(callCentreApi.callStats).not.toHaveBeenCalled();
    const active = host.querySelector('[role="tab"][aria-selected="true"]');
    expect(active?.textContent?.trim()).toBe('Leaderboard');
    expect(host.textContent).toContain('Avg. handling time');
  });

  it('opens the telephony tab straight from a deep link when it is offered', async () => {
    await render();
    history.replaceState(null, '', '/statistics/staff?rp_tab=telephony');
    callCentreApi.callStats.mockResolvedValue([callHour()] as readonly CallHourStat[]);
    fixture = TestBed.createComponent(StaffReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(callCentreApi.callStats).toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="staff-report-telephony-speed"]',
      ),
    ).not.toBeNull();
  });

  it('renders connected-but-nothing-closed with the empty sentence and when the next close runs, and no figures', async () => {
    await render();
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-empty')).toBe('No calls closed in this period yet.');
    expect(text(host, 'staff-report-telephony-next-close')).toContain(
      'appear after the business day closes',
    );
    expect(host.querySelector('table')).toBeNull();
    expect(host.querySelector('[data-testid="staff-report-telephony-total"]')).toBeNull();
  });

  it('shows measured answer speed and conversion with their coverage', async () => {
    await render(SCOPE, undefined, coverage({ closedDaysWithCalls: 1 }));
    callCentreApi.callStats.mockResolvedValue([
      callHour({ ringSecondsTotal: 7, ringSampleCount: 1, ringSecondsMax: 7 }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-speed')).toBe(
      '7 s (max 7 s) measured on 1 of 1 answered calls',
    );
    expect(text(host, 'staff-report-telephony-conversion')).toBe('100% 1 of 1 answered calls');
  });

  it('rolls ratios up across hours from sums, not from per-row percentages', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([
      // 3 calls, ring total 30 s (avg 10), 1 converted
      callHour({
        hourOfDay: 10,
        answeredCount: 3,
        answeredCallCount: 3,
        ringSecondsTotal: 30,
        ringSampleCount: 3,
        ringSecondsMax: 20,
        convertedCount: 1,
      }),
      // 1 call, ring 50 s, 1 converted. Averaging the two rows' averages would say 30 s; the sums say 80 / 4 = 20 s.
      callHour({
        hourOfDay: 11,
        answeredCount: 1,
        answeredCallCount: 1,
        ringSecondsTotal: 50,
        ringSampleCount: 1,
        ringSecondsMax: 50,
        convertedCount: 1,
      }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-speed')).toBe(
      '20 s (max 50 s) measured on 4 of 4 answered calls',
    );
    // 2 of 4 = 50 %, not the mean of 33 % and 100 %.
    expect(text(host, 'staff-report-telephony-conversion')).toBe('50% 2 of 4 answered calls');
  });

  it('shows a dash and "not measured" for a pre-migration row, never 0 s or 0%', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([unmeasuredHour()] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-speed')).toBe('— not measured');
    expect(text(host, 'staff-report-telephony-conversion')).toBe('— not measured');
    const table = host.querySelector('table')?.textContent ?? '';
    expect(table).not.toContain('0 s');
    expect(table).not.toContain('0%');
  });

  it('counts only the measured days toward a ratio when measured and unmeasured days are mixed', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([
      unmeasuredHour({ hourOfDay: 9, answeredCount: 5 }),
      callHour({ hourOfDay: 10, answeredCount: 1 }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    // The five unmeasured answers are in the Answered column but not in the denominator.
    expect(text(host, 'staff-report-telephony-conversion')).toBe('100% 1 of 1 answered calls');
  });

  it('shows a dash, not zero, for a measured row that held no ring sample', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([
      callHour({
        answeredCallCount: 1,
        ringSecondsTotal: 0,
        ringSampleCount: 0,
        ringSecondsMax: null,
        convertedCount: 0,
      }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-speed')).toBe('— not measured');
    // Conversion is measured here: nobody ordered, which is a real 0 %.
    expect(text(host, 'staff-report-telephony-conversion')).toBe('0% 0 of 1 answered calls');
  });

  it('renders the unassigned sentinel as "Not attributed" and not as a truncated subject', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([
      callHour({ operatorPrincipalId: '(unassigned)', offeredCount: 2, answeredCount: 0 }),
    ] as readonly CallHourStat[]);
    const host = await openTelephony();

    expect(text(host, 'staff-report-telephony-operator')).toBe('Not attributed');
    expect(host.textContent).not.toContain('(unassig');
  });

  it('labels the length column "Call length" and no longer apologises for the two KPIs', async () => {
    await render();
    callCentreApi.callStats.mockResolvedValue([callHour()] as readonly CallHourStat[]);
    const host = await openTelephony();

    const headers = Array.from(host.querySelectorAll('th')).map((th) => th.textContent?.trim());
    expect(headers).toContain('Call length');
    expect(headers).not.toContain('Talk time');
    expect(headers).not.toContain('Answer rate');
    expect(host.textContent).not.toContain('is not shown');
    expect(host.textContent).not.toContain('not built');
    expect(text(host, 'staff-report-telephony-help')).toContain('lower bound');
  });
});
