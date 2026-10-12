import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { PersonShiftsTab } from './person-shifts-tab';
import { StaffShift, StaffShiftsApi } from './staff-shifts-api';

const SCOPE = { tenantId: 't', brandId: 'b', locationId: 'l' };

function shift(over: Partial<StaffShift> = {}): StaffShift {
  return {
    id: 's-1',
    locationId: 'l',
    person: null,
    status: 'CLOSED',
    startedAt: '2026-10-07T04:00:00Z',
    endedAt: '2026-10-07T12:00:00Z',
    openSource: 'CONSOLE_SELF',
    closeSource: 'CONSOLE_SELF',
    breakSeconds: 0,
    workedSeconds: 28800,
    paidSeconds: 27000,
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

describe('PersonShiftsTab (ADR 0156)', () => {
  let fixture: ComponentFixture<PersonShiftsTab>;
  let api: { shiftsOf: ReturnType<typeof vi.fn> };

  async function setUp(
    result: StaffShift[] | Error,
    scope: typeof SCOPE | null = SCOPE,
  ): Promise<void> {
    api = { shiftsOf: vi.fn() };
    if (result instanceof Error) {
      api.shiftsOf.mockRejectedValue(result);
    } else {
      api.shiftsOf.mockResolvedValue({ items: result, nextCursor: null });
    }
    await TestBed.configureTestingModule({
      imports: [PersonShiftsTab],
      providers: [
        { provide: StaffShiftsApi, useValue: api },
        {
          provide: CurrentLocation,
          useValue: { ensureLoaded: () => Promise.resolve(), scope: () => scope },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PersonShiftsTab);
    fixture.componentRef.setInput('memberId', 'm-1');
    fixture.detectChanges();
    for (let hop = 0; hop < 10; hop++) {
      await Promise.resolve();
    }
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (testId: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);

  beforeEach(() => TestBed.resetTestingModule());

  it('lists the person’s shifts at the branch in view, asking for that person only', async () => {
    await setUp([shift(), shift({ id: 's-2', status: 'AWAITING_APPROVAL' })]);

    expect(api.shiftsOf).toHaveBeenCalledWith(SCOPE, 'm-1');
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="person-shifts-row"]',
    );
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('7 h 30 min');
    expect(rows[1].textContent).toContain('Awaiting approval');
  });

  it('says there are none rather than showing an empty table', async () => {
    await setUp([]);

    expect(q('person-shifts-none')).not.toBeNull();
    expect(q('person-shifts-table')).toBeNull();
  });

  it('shows the denied sentence when the platform refuses the read', async () => {
    await setUp(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));

    expect(q('person-shifts-denied')).not.toBeNull();
  });

  it('shows the denied sentence when the console has no branch in scope, and reads nothing', async () => {
    await setUp([], null);

    expect(q('person-shifts-denied')).not.toBeNull();
    expect(api.shiftsOf).not.toHaveBeenCalled();
  });
});
