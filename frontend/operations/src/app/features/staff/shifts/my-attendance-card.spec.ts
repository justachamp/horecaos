import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { AttendanceStatus } from './attendance-status';
import { MyAttendance, StaffShift, StaffShiftsApi } from './staff-shifts-api';
import { MyAttendanceCard } from './my-attendance-card';

const TENANT = '018fb100-6000-7000-8000-0000000000a1';

function shift(over: Partial<StaffShift> = {}): StaffShift {
  return {
    id: 's-1',
    locationId: 'loc-1',
    person: null,
    status: 'CLOSED',
    startedAt: '2026-10-07T04:00:00Z',
    endedAt: '2026-10-07T12:00:00Z',
    openSource: 'CONSOLE_SELF',
    closeSource: 'CONSOLE_SELF',
    breakSeconds: 0,
    workedSeconds: 28800,
    paidSeconds: 28800,
    plannedSeconds: null,
    varianceSeconds: null,
    varianceThresholdMinutes: null,
    businessDate: '2026-10-07',
    hadRosterEntry: false,
    editReasonCode: null,
    approvedAt: null,
    voidReasonCode: null,
    version: 2,
    ...over,
  };
}

const NOT_WORKING: MyAttendance = {
  open: null,
  upcoming: [
    {
      id: 'e-1',
      locationId: 'loc-planned',
      plannedStart: '2026-10-08T04:00:00Z',
      plannedEnd: '2026-10-08T12:00:00Z',
      plannedBreakMinutes: 30,
    },
  ],
  recent: [shift(), shift({ id: 's-2', status: 'AWAITING_APPROVAL', paidSeconds: null })],
};

describe('MyAttendanceCard (ADR 0156)', () => {
  let fixture: ComponentFixture<MyAttendanceCard>;
  let api: { mine: ReturnType<typeof vi.fn>; clockIn: ReturnType<typeof vi.fn>; clockOut: ReturnType<typeof vi.fn> };

  async function setUp(options: {
    on?: boolean;
    capabilities?: readonly Capability[];
    mine?: MyAttendance | Error;
  }): Promise<void> {
    const held = new Set<Capability>(options.capabilities ?? ['STAFF_SHIFT_CLOCK']);
    api = {
      mine: vi.fn(),
      clockIn: vi.fn().mockResolvedValue(shift({ status: 'OPEN', endedAt: null })),
      clockOut: vi.fn().mockResolvedValue(shift()),
    };
    const mine = options.mine ?? NOT_WORKING;
    if (mine instanceof Error) {
      api.mine.mockRejectedValue(mine);
    } else {
      api.mine.mockResolvedValue(mine);
    }
    await TestBed.configureTestingModule({
      imports: [MyAttendanceCard],
      providers: [
        { provide: StaffShiftsApi, useValue: api },
        {
          provide: AttendanceStatus,
          useValue: {
            ensureLoaded: () => Promise.resolve(),
            enabled: signal<boolean | null>(options.on ?? true),
          },
        },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => TENANT },
        },
        {
          provide: CurrentLocation,
          useValue: { scope: () => ({ tenantId: TENANT, brandId: 'b', locationId: 'loc-open' }) },
        },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.has(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MyAttendanceCard);
    fixture.detectChanges();
    await settle();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const q = <T extends HTMLElement>(testId: string): T | null =>
    root().querySelector<T>(`[data-testid="${testId}"]`);

  async function settle(): Promise<void> {
    for (let hop = 0; hop < 10; hop++) {
      await Promise.resolve();
    }
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('is absent when the tenant does not record attendance, and makes no read', async () => {
    await setUp({ on: false });

    expect(q('my-attendance-card')).toBeNull();
    expect(api.mine).not.toHaveBeenCalled();
  });

  it('is absent for a person who may not clock', async () => {
    await setUp({ capabilities: ['STAFF_SHIFT_READ'] });

    expect(q('my-attendance-card')).toBeNull();
    expect(api.mine).not.toHaveBeenCalled();
  });

  it('offers to start a shift, names the next planned one, and lists the last shifts', async () => {
    await setUp({});

    expect(q('my-attendance-closed')).not.toBeNull();
    expect(q('my-attendance-next')?.textContent).toContain('Next:');
    expect(q('my-attendance-clock-out')).toBeNull();
    expect(q('my-attendance-recent')?.querySelectorAll('li')).toHaveLength(2);
    expect(q('my-attendance-recent')?.textContent).toContain('8 h 00 min');
    expect(q('my-attendance-recent')?.textContent).toContain('Awaiting approval');
  });

  it('starts a shift at the branch the person is next rostered at, sending no time', async () => {
    await setUp({});

    q<HTMLButtonElement>('my-attendance-clock-in')?.click();
    await settle();

    expect(api.clockIn).toHaveBeenCalledTimes(1);
    expect(api.clockIn).toHaveBeenCalledWith(TENANT, 'loc-planned');
    // The call carries a tenant and a branch: the instant is the platform's.
    expect(api.clockIn.mock.calls[0]).toHaveLength(2);
  });

  it('falls back to the branch the console is open on when nothing is planned', async () => {
    await setUp({ mine: { open: null, upcoming: [], recent: [] } });

    q<HTMLButtonElement>('my-attendance-clock-in')?.click();
    await settle();

    expect(api.clockIn).toHaveBeenCalledWith(TENANT, 'loc-open');
  });

  it('shows an open shift with its start and offers only to end it', async () => {
    await setUp({
      mine: { open: shift({ status: 'OPEN', endedAt: null }), upcoming: [], recent: [] },
    });

    expect(q('my-attendance-open')?.textContent).toContain('On shift since');
    expect(q('my-attendance-clock-in')).toBeNull();

    q<HTMLButtonElement>('my-attendance-clock-out')?.click();
    await settle();
    expect(api.clockOut).toHaveBeenCalledWith(TENANT);
  });

  it('holds the button while a request is out, so a double tap is one request', async () => {
    await setUp({});
    let finish: (value: StaffShift) => void = () => undefined;
    api.clockIn.mockReturnValue(
      new Promise<StaffShift>((resolve) => {
        finish = resolve;
      }),
    );

    const button = q<HTMLButtonElement>('my-attendance-clock-in');
    button?.click();
    fixture.detectChanges();
    expect(q<HTMLButtonElement>('my-attendance-clock-in')?.disabled).toBe(true);
    button?.click();

    finish(shift());
    await settle();
    expect(api.clockIn).toHaveBeenCalledTimes(1);
  });

  it('puts the platform’s refusal under the button', async () => {
    await setUp({});
    api.clockIn.mockRejectedValue(new ApiError('STALE_VERSION', 409, { status: 409, detail: 'x' }, 'c-1'));

    q<HTMLButtonElement>('my-attendance-clock-in')?.click();
    await settle();

    expect(q('my-attendance-failure')).not.toBeNull();
  });

  it('says it could not load, with a retry, instead of an empty card', async () => {
    await setUp({ mine: new Error('down') });

    expect(q('my-attendance-error')).not.toBeNull();
  });
});
