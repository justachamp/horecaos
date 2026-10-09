import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ShiftActionDialog, ShiftActionKind, ShiftActionResult } from './shift-action-dialog';
import { StaffShift } from './staff-shifts-api';

const ZONE = 'Asia/Tashkent';

const SHIFT: StaffShift = {
  id: 's-1',
  locationId: 'l',
  person: null,
  status: 'CLOSED',
  startedAt: '2026-10-08T04:00:00Z',
  endedAt: '2026-10-08T12:00:00Z',
  openSource: 'CONSOLE_SELF',
  closeSource: 'CONSOLE_SELF',
  breakSeconds: 1800,
  workedSeconds: 28800,
  paidSeconds: 27000,
  plannedSeconds: null,
  varianceSeconds: null,
  varianceThresholdMinutes: null,
  businessDate: '2026-10-08',
  hadRosterEntry: false,
  editReasonCode: null,
  approvedAt: null,
  voidReasonCode: null,
  version: 3,
};

describe('ShiftActionDialog (ADR 0156)', () => {
  let fixture: ComponentFixture<ShiftActionDialog>;
  let results: ShiftActionResult[];

  async function open(kind: ShiftActionKind, shift: StaffShift | null = null): Promise<void> {
    await TestBed.configureTestingModule({ imports: [ShiftActionDialog] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ShiftActionDialog);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('zone', ZONE);
    fixture.componentRef.setInput('shift', shift);
    fixture.componentRef.setInput('people', [
      { memberId: 'm-1', displayReference: 'S-0001', name: null },
    ]);
    results = [];
    fixture.componentInstance.result.subscribe((value) => results.push(value));
    fixture.detectChanges();
  }

  const q = <T extends HTMLElement>(testId: string): T | null =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testId}"]`);

  function set(testId: string, value: string, event = 'input'): void {
    const element = q<HTMLInputElement | HTMLSelectElement>(testId);
    expect(element).not.toBeNull();
    if (element) {
      element.value = value;
      element.dispatchEvent(new Event(event));
    }
    fixture.detectChanges();
  }

  const confirm = (): void => {
    q<HTMLButtonElement>('shift-action-confirm')?.click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    TestBed.resetTestingModule();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-08T06:00:00Z'));
  });

  afterEach(() => vi.useRealTimers());

  it('records an end for now without a reason, since a time for now needs none', async () => {
    await open('close', { ...SHIFT, status: 'OPEN', endedAt: null });

    confirm();

    expect(results).toHaveLength(1);
    const result = results[0];
    expect(result.kind).toBe('close');
    if (result.kind === 'close') {
      expect(result.reason).toBeNull();
      expect(result.endedAt).toBe('2026-10-08T06:00:00.000Z');
      expect(result.breakSeconds).toBeNull();
    }
  });

  it('refuses an end in the past until a reason is picked from the list', async () => {
    await open('close', { ...SHIFT, status: 'OPEN', endedAt: null });
    set('shift-action-end', '2026-10-08T09:00');

    confirm();
    expect(results).toHaveLength(0);
    expect(q('shift-action-dialog')?.textContent).toContain('Pick a reason');

    set('shift-action-reason', 'FORGOT_CLOCK_OUT', 'change');
    confirm();
    expect(results).toHaveLength(1);
    expect(results[0]).toMatchObject({
      kind: 'close',
      reason: 'FORGOT_CLOCK_OUT',
      endedAt: '2026-10-08T04:00:00.000Z',
    });
  });

  it('reads a typed time as the branch’s local time, not the device’s', async () => {
    await open('open');
    set('shift-action-member', 'm-1', 'change');
    set('shift-action-start', '2026-10-08T11:00');

    confirm();

    // 11:00 in Tashkent (UTC+5) is 06:00Z, i.e. now: no reason is needed.
    expect(results[0]).toMatchObject({
      kind: 'open',
      memberId: 'm-1',
      startedAt: '2026-10-08T06:00:00.000Z',
      reason: null,
    });
  });

  it('will not record a start for nobody', async () => {
    await open('open');

    confirm();

    expect(results).toHaveLength(0);
  });

  it('shows a correction the recorded times and always needs a reason, offering only the manager’s reasons', async () => {
    await open('correct', SHIFT);

    expect(q<HTMLInputElement>('shift-action-start')?.value).toBe('2026-10-08T09:00');
    expect(q<HTMLInputElement>('shift-action-end')?.value).toBe('2026-10-08T17:00');
    expect(q<HTMLInputElement>('shift-action-break')?.value).toBe('30');
    const reasons = Array.from(q<HTMLSelectElement>('shift-action-reason')?.options ?? []).map(
      (o) => o.value,
    );
    expect(reasons).toContain('FORGOT_CLOCK_OUT');
    expect(reasons).not.toContain('DUPLICATE');

    confirm();
    expect(results).toHaveLength(0);
  });

  it('sends a correction with the break in seconds and the picked reason', async () => {
    await open('correct', SHIFT);
    set('shift-action-break', '45');
    set('shift-action-reason', 'SYSTEM_PROBLEM', 'change');

    confirm();

    expect(results[0]).toMatchObject({
      kind: 'correct',
      breakSeconds: 2700,
      reason: 'SYSTEM_PROBLEM',
    });
  });

  it('voids only for a reason from the void list, and approves only from the approval list', async () => {
    await open('void', SHIFT);
    expect(
      Array.from(q<HTMLSelectElement>('shift-action-reason')?.options ?? []).map((o) => o.value),
    ).toEqual(['', 'ENTERED_IN_ERROR', 'DUPLICATE', 'DID_NOT_WORK']);
    confirm();
    expect(results).toHaveLength(0);
    set('shift-action-reason', 'DUPLICATE', 'change');
    confirm();
    expect(results[0]).toEqual({ kind: 'void', reason: 'DUPLICATE' });
  });

  it('approves with a reason, one shift at a time', async () => {
    await open('approve', { ...SHIFT, status: 'AWAITING_APPROVAL' });
    set('shift-action-reason', 'WORKED_AS_RECORDED', 'change');

    confirm();

    expect(results).toEqual([{ kind: 'approve', reason: 'WORKED_AS_RECORDED' }]);
  });
});
