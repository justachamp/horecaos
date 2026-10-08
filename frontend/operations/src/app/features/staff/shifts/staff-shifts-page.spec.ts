import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../../settings/configuration-api';
import { AttendanceStatus } from './attendance-status';
import {
  RosterEntry,
  RosterWeek,
  StaffShift,
  StaffShiftsApi,
  TodayList,
} from './staff-shifts-api';
import { StaffShiftsPage } from './staff-shifts-page';

const TENANT = '018fb100-6000-7000-8000-0000000000a1';
const SCOPE = { tenantId: TENANT, brandId: 'brand-1', locationId: 'loc-1' };
const ZONE = 'Asia/Tashkent';

const aziza = { memberId: 'm-1', displayReference: 'S-0001', name: null } as const;
const bekzod = { memberId: 'm-2', displayReference: 'S-0002', name: null } as const;

function entry(over: Partial<RosterEntry> = {}): RosterEntry {
  return {
    id: 'e-1',
    locationId: 'loc-1',
    person: aziza,
    status: 'DRAFT',
    // Thursday 8 October, 09:00-18:00 in Tashkent.
    plannedStart: '2026-10-08T04:00:00Z',
    plannedEnd: '2026-10-08T13:00:00Z',
    plannedBreakMinutes: 60,
    publishedAt: null,
    version: 1,
    ...over,
  };
}

function shift(over: Partial<StaffShift> = {}): StaffShift {
  return {
    id: 's-1',
    locationId: 'loc-1',
    person: aziza,
    status: 'CLOSED',
    startedAt: '2026-10-08T04:00:00Z',
    endedAt: '2026-10-08T13:30:00Z',
    openSource: 'CONSOLE_SELF',
    closeSource: 'CONSOLE_SELF',
    breakSeconds: 3600,
    workedSeconds: 30600,
    paidSeconds: 27000,
    plannedSeconds: 25200,
    varianceSeconds: 1800,
    varianceThresholdMinutes: 15,
    businessDate: '2026-10-08',
    hadRosterEntry: true,
    editReasonCode: null,
    approvedAt: null,
    voidReasonCode: null,
    version: 3,
    ...over,
  };
}

const WEEK: RosterWeek = {
  timezone: ZONE,
  from: '2026-10-04T19:00:00Z',
  to: '2026-10-11T19:00:00Z',
  entries: [entry()],
};

const TODAY: TodayList = {
  businessDate: '2026-10-08',
  from: '2026-10-07T19:00:00Z',
  to: '2026-10-08T19:00:00Z',
  asOf: '2026-10-08T06:00:00Z',
  ordersOnly: true,
  rows: [
    {
      person: aziza,
      status: 'WORKING',
      plannedStart: '2026-10-08T04:00:00Z',
      plannedEnd: '2026-10-08T13:00:00Z',
      shiftId: 's-open',
      startedAt: '2026-10-08T04:05:00Z',
      endedAt: null,
      shiftStatus: 'OPEN',
      shiftVersion: 2,
    },
    {
      person: bekzod,
      status: 'LATE',
      plannedStart: '2026-10-08T04:00:00Z',
      plannedEnd: '2026-10-08T13:00:00Z',
      shiftId: null,
      startedAt: null,
      endedAt: null,
      shiftStatus: null,
      shiftVersion: null,
    },
    {
      person: aziza,
      status: 'CLOSED',
      plannedStart: null,
      plannedEnd: null,
      shiftId: 's-wait',
      startedAt: '2026-10-08T01:00:00Z',
      endedAt: '2026-10-08T05:00:00Z',
      shiftStatus: 'AWAITING_APPROVAL',
      shiftVersion: 4,
    },
  ],
};

describe('StaffShiftsPage (ADR 0156)', () => {
  let fixture: ComponentFixture<StaffShiftsPage>;
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let enabled: ReturnType<typeof signal<boolean | null>>;
  let held: Set<Capability>;
  let configuration: { setValue: ReturnType<typeof vi.fn> };
  let markEnabled: ReturnType<typeof vi.fn>;

  async function setUp(options: {
    on?: boolean;
    capabilities?: readonly Capability[];
    week?: RosterWeek | Error;
  }): Promise<void> {
    enabled = signal<boolean | null>(options.on ?? true);
    held = new Set(options.capabilities ?? ['STAFF_SHIFT_READ', 'STAFF_SHIFT_MANAGE', 'STAFF_SHIFT_APPROVE']);
    api = {
      week: vi.fn(),
      rosterable: vi.fn().mockResolvedValue([aziza, bekzod]),
      today: vi.fn().mockResolvedValue(TODAY),
      shifts: vi.fn().mockResolvedValue({ items: [shift()], nextCursor: null }),
      publish: vi.fn().mockResolvedValue({ published: 1, people: ['m-1'] }),
      copyWeek: vi.fn().mockResolvedValue({ created: 2, skipped: 1 }),
      createEntry: vi.fn().mockResolvedValue(entry()),
      updateEntry: vi.fn().mockResolvedValue(entry()),
      cancelEntry: vi.fn().mockResolvedValue(undefined),
      openFor: vi.fn().mockResolvedValue(shift()),
      closeFor: vi.fn().mockResolvedValue(shift()),
      correct: vi.fn().mockResolvedValue(shift()),
      voidShift: vi.fn().mockResolvedValue(shift()),
      approve: vi.fn().mockResolvedValue(shift()),
    };
    const week = options.week ?? WEEK;
    if (week instanceof Error) {
      api['week'].mockRejectedValue(week);
    } else {
      api['week'].mockResolvedValue(week);
    }
    configuration = { setValue: vi.fn().mockResolvedValue({}) };
    markEnabled = vi.fn(() => enabled.set(true));
    await TestBed.configureTestingModule({
      imports: [StaffShiftsPage],
      providers: [
        { provide: StaffShiftsApi, useValue: api },
        { provide: ConfigurationApi, useValue: configuration },
        {
          provide: AttendanceStatus,
          useValue: { ensureLoaded: () => Promise.resolve(), enabled, markEnabled },
        },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => TENANT, denied: () => false },
        },
        {
          provide: CurrentLocation,
          useValue: { ensureLoaded: () => Promise.resolve(), scope: () => SCOPE, denied: () => false },
        },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.has(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffShiftsPage);
    fixture.detectChanges();
    await settle();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const text = (): string => root().textContent ?? '';
  const q = <T extends HTMLElement>(testId: string): T | null =>
    root().querySelector<T>(`[data-testid="${testId}"]`);
  const all = (testId: string): HTMLElement[] =>
    Array.from(root().querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`));

  async function settle(): Promise<void> {
    for (let hop = 0; hop < 12; hop++) {
      await Promise.resolve();
    }
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function click(element: HTMLElement | null): Promise<void> {
    expect(element).not.toBeNull();
    element?.click();
    fixture.detectChanges();
    await settle();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    // Thursday 8 October 2026, 11:00 in Tashkent: the week is 5-11 October.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-08T06:00:00Z'));
  });

  afterEach(() => vi.useRealTimers());

  it('says nothing is recorded when attendance is off, and offers the switch only to a person who can change configuration', async () => {
    await setUp({ on: false, capabilities: ['STAFF_SHIFT_READ'] });

    expect(q('shifts-off')).not.toBeNull();
    expect(q('shifts-enable')).toBeNull();
    expect(q('shifts-ask-owner')).not.toBeNull();
    expect(api['week']).not.toHaveBeenCalled();
  });

  it('switches attendance on only after the notice is acknowledged and a reason is given, then shows the rota', async () => {
    enabled = signal<boolean | null>(false);
    await setUp({ on: false, capabilities: ['TENANT_CONFIGURATION_WRITE', 'STAFF_SHIFT_READ', 'STAFF_SHIFT_MANAGE'] });

    await click(q('shifts-enable'));
    const confirm = q<HTMLButtonElement>('attendance-notice-confirm');
    expect(confirm?.disabled).toBe(true);
    expect(q('attendance-notice-legal')?.textContent).toContain('default wording');

    const ack = q<HTMLInputElement>('attendance-notice-ack');
    ack?.click();
    const reason = q<HTMLInputElement>('attendance-notice-reason');
    if (reason) {
      reason.value = 'Rota for the new branch';
      reason.dispatchEvent(new Event('input'));
    }
    fixture.detectChanges();
    expect(q<HTMLButtonElement>('attendance-notice-confirm')?.disabled).toBe(false);
    await click(q('attendance-notice-confirm'));

    expect(configuration.setValue).toHaveBeenCalledWith(TENANT, 'workforce.attendance.enabled', {
      scopeType: 'TENANT',
      explicitNull: false,
      booleanValue: true,
      reason: 'Rota for the new branch',
    });
    expect(markEnabled).toHaveBeenCalled();
    expect(api['week']).toHaveBeenCalled();
  });

  it('draws the week for the branch day: people as references, today marked, and only the manager can add', async () => {
    await setUp({});

    expect(api['week']).toHaveBeenCalledWith(SCOPE, '2026-10-05', '2026-10-11');
    expect(all('roster-row-person').map((n) => n.textContent?.trim())).toEqual(['S-0001', 'S-0002']);
    expect(q('roster-today-column')?.getAttribute('aria-current')).toBe('date');
    expect(q('roster-today-column')?.textContent).toContain('08.10');
    expect(all('roster-entry')).toHaveLength(1);
    expect(all('roster-entry')[0].textContent).toContain('09:00–18:00');
    expect(all('roster-empty-cell').length).toBeGreaterThan(0);
    expect(text()).toContain('Paid hours come only from recorded shifts');
  });

  it('gives a reader the grid without a way to add, publish or copy', async () => {
    await setUp({ capabilities: ['STAFF_SHIFT_READ'] });

    expect(all('roster-entry')).toHaveLength(1);
    expect((all('roster-entry')[0] as HTMLButtonElement).disabled).toBe(true);
    expect(q('shifts-publish')).toBeNull();
    expect(q('shifts-copy')).toBeNull();
    expect(api['rosterable']).not.toHaveBeenCalled();
  });

  it('publishes the whole week, and only while there are drafts', async () => {
    await setUp({});
    expect(q<HTMLButtonElement>('shifts-publish')?.disabled).toBe(false);

    await click(q('shifts-publish'));

    expect(api['publish']).toHaveBeenCalledWith(SCOPE, '2026-10-05', '2026-10-11');
    expect(q('shifts-notice')?.textContent).toContain('Published shifts: 1');
  });

  it('keeps publish off when nothing is a draft', async () => {
    await setUp({ week: { ...WEEK, entries: [entry({ status: 'PUBLISHED' })] } });

    expect(q<HTMLButtonElement>('shifts-publish')?.disabled).toBe(true);
  });

  it('copies the previous week onto this one and says how many it skipped', async () => {
    await setUp({});

    await click(q('shifts-copy'));

    expect(api['copyWeek']).toHaveBeenCalledWith(SCOPE, '2026-09-28', '2026-10-05');
    expect(q('shifts-notice')?.textContent).toContain('Copied shifts: 2, skipped: 1');
  });

  it('moves a week at a time and reads that week', async () => {
    await setUp({});

    await click(q('shifts-next'));

    expect(api['week']).toHaveBeenLastCalledWith(SCOPE, '2026-10-12', '2026-10-18');
    expect(q('shifts-range')?.textContent).toContain('12.10');
  });

  it('shows today with the problems marked, says it counts order-takers only, and offers the right act per row', async () => {
    await setUp({});

    await click(q('shifts-tab-today'));

    expect(api['today']).toHaveBeenCalledWith(SCOPE);
    const rows = all('shifts-today-row');
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('Working');
    expect(rows[0].textContent).toContain('Record end');
    expect(rows[1].textContent).toContain('Late');
    expect(rows[1].textContent).toContain('Record start');
    expect(rows[2].textContent).toContain('Awaiting approval');
    expect(rows[2].textContent).toContain('Approve');
    expect(q('shifts-asof')?.textContent).toContain('11:00');
    expect(q('shifts-asof')?.textContent).toContain('only people who take orders');
  });

  it('records the end of an open shift against the version it was read at', async () => {
    await setUp({});
    await click(q('shifts-tab-today'));

    const end = all('shifts-today-row')[0].querySelector<HTMLButtonElement>('button');
    await click(end);
    expect(q('shift-action-dialog')).not.toBeNull();
    await click(q('shift-action-confirm'));

    expect(api['closeFor']).toHaveBeenCalledTimes(1);
    const [scope, ref, , breakSeconds, reason] = api['closeFor'].mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(ref).toEqual({ id: 's-open', version: 2 });
    expect(breakSeconds).toBeNull();
    expect(reason).toBeNull();
    expect(q('shift-action-dialog')).toBeNull();
  });

  it('shows no approve button to a manager who may not approve', async () => {
    await setUp({ capabilities: ['STAFF_SHIFT_READ', 'STAFF_SHIFT_MANAGE'] });
    await click(q('shifts-tab-today'));

    expect(all('shifts-today-row')[2].textContent).not.toContain('Approve');
  });

  it('lists the timesheet with paid hours and the variance from the plan', async () => {
    await setUp({});

    await click(q('shifts-tab-timesheet'));

    expect(api['shifts']).toHaveBeenCalledWith(SCOPE, {
      from: '2026-10-05',
      to: '2026-10-11',
      cursor: null,
    });
    expect(q('shifts-paid')?.textContent?.trim()).toBe('7 h 30 min');
    expect(q('shifts-variance')?.textContent?.trim()).toBe('+30 min');
  });

  it('asks for a reason before a correction, picked from a list, and sends the version', async () => {
    await setUp({});
    await click(q('shifts-tab-timesheet'));

    await click(q('shifts-correct'));
    await click(q('shift-action-confirm'));
    expect(api['correct']).not.toHaveBeenCalled();

    const select = q<HTMLSelectElement>('shift-action-reason');
    if (select) {
      select.value = 'FORGOT_CLOCK_OUT';
      select.dispatchEvent(new Event('change'));
    }
    fixture.detectChanges();
    await click(q('shift-action-confirm'));

    expect(api['correct']).toHaveBeenCalledTimes(1);
    expect(api['correct'].mock.calls[0][1]).toMatchObject({ id: 's-1', version: 3 });
    expect(api['correct'].mock.calls[0][3]).toBe('FORGOT_CLOCK_OUT');
  });

  it('offers approval of a shift awaiting it, and voiding only to the manager', async () => {
    await setUp({});
    api['shifts'].mockResolvedValue({
      items: [shift({ status: 'AWAITING_APPROVAL' }), shift({ id: 's-2', status: 'VOIDED' })],
      nextCursor: null,
    });
    await click(q('shifts-tab-timesheet'));

    expect(all('shifts-approve')).toHaveLength(1);
    expect(all('shifts-void')).toHaveLength(1);
    expect(all('shifts-row')[1].className).toContain('shifts__row--void');
  });

  it('puts the platform’s refusal in the dialog instead of closing it', async () => {
    await setUp({});
    await click(q('shifts-tab-today'));
    api['closeFor'].mockRejectedValue(
      new ApiError('STALE_VERSION', 409, { status: 409, detail: 'changed' }, 'corr-1'),
    );

    await click(all('shifts-today-row')[0].querySelector<HTMLButtonElement>('button'));
    await click(q('shift-action-confirm'));

    expect(q('shift-action-dialog')).not.toBeNull();
    expect(q('shift-action-failure')).not.toBeNull();
  });

  it('shows the denied state when the platform refuses the rota read', async () => {
    await setUp({ week: new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null) });

    expect(q('shifts-denied')).not.toBeNull();
    expect(q('roster-week-grid')).toBeNull();
  });
});
