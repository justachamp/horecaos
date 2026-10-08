import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { EntryDialogResult, RosterEntryDialog } from './roster-entry-dialog';
import { RosterEntry } from './staff-shifts-api';

const PEOPLE = [
  { memberId: 'm-1', displayReference: 'S-0001', name: null },
  { memberId: 'm-2', displayReference: 'S-0002', name: 'Bekzod Aliyev' },
];

const EXISTING: RosterEntry = {
  id: 'e-1',
  locationId: 'l',
  person: PEOPLE[0],
  status: 'PUBLISHED',
  plannedStart: '2026-10-08T04:00:00Z',
  plannedEnd: '2026-10-08T13:00:00Z',
  plannedBreakMinutes: 60,
  publishedAt: null,
  version: 4,
};

describe('RosterEntryDialog (ADR 0156)', () => {
  let fixture: ComponentFixture<RosterEntryDialog>;
  let results: EntryDialogResult[];

  async function open(inputs: { entry?: RosterEntry; date?: string; memberId?: string }): Promise<void> {
    await TestBed.configureTestingModule({ imports: [RosterEntryDialog] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RosterEntryDialog);
    fixture.componentRef.setInput('people', PEOPLE);
    fixture.componentRef.setInput('zone', 'Asia/Tashkent');
    if (inputs.entry) {
      fixture.componentRef.setInput('entry', inputs.entry);
    }
    if (inputs.date) {
      fixture.componentRef.setInput('date', inputs.date);
    }
    if (inputs.memberId) {
      fixture.componentRef.setInput('memberId', inputs.memberId);
    }
    results = [];
    fixture.componentInstance.result.subscribe((value) => results.push(value));
    fixture.detectChanges();
  }

  const q = <T extends HTMLElement>(testId: string): T | null =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testId}"]`);

  function set(testId: string, value: string, event = 'input'): void {
    const element = q<HTMLInputElement | HTMLSelectElement>(testId);
    if (element) {
      element.value = value;
      element.dispatchEvent(new Event(event));
    }
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('starts a new shift on the clicked day and person, 09:00 to 18:00 in the branch’s time', async () => {
    await open({ date: '2026-10-09', memberId: 'm-2' });

    expect(q<HTMLInputElement>('roster-entry-start')?.value).toBe('2026-10-09T09:00');
    expect(q<HTMLInputElement>('roster-entry-end')?.value).toBe('2026-10-09T18:00');
    q<HTMLButtonElement>('roster-entry-save')?.click();

    expect(results).toEqual([
      {
        kind: 'save',
        draft: {
          memberId: 'm-2',
          plannedStart: '2026-10-09T04:00:00.000Z',
          plannedEnd: '2026-10-09T13:00:00.000Z',
          plannedBreakMinutes: 0,
        },
      },
    ]);
  });

  it('refuses a shift with no person or an end before its start', async () => {
    await open({ date: '2026-10-09' });
    q<HTMLButtonElement>('roster-entry-save')?.click();
    expect(results).toHaveLength(0);

    set('roster-entry-member', 'm-1', 'change');
    set('roster-entry-end', '2026-10-09T08:00');
    q<HTMLButtonElement>('roster-entry-save')?.click();

    expect(results).toHaveLength(0);
    expect(q('roster-entry-dialog')?.textContent).toContain('The end must be after the start');
  });

  it('keeps the person of an existing entry and shows its times, and says a plan pays nothing', async () => {
    await open({ entry: EXISTING });

    expect(q<HTMLSelectElement>('roster-entry-member')?.value).toBe('m-1');
    expect(q<HTMLInputElement>('roster-entry-start')?.value).toBe('2026-10-08T09:00');
    expect(q<HTMLInputElement>('roster-entry-break')?.value).toBe('60');
    expect(q('roster-entry-dialog')?.textContent).toContain('does not start anyone’s shift');
  });

  it('cancels an entry only with a reason from the list', async () => {
    await open({ entry: EXISTING });

    q<HTMLButtonElement>('roster-entry-cancel')?.click();
    fixture.detectChanges();
    q<HTMLButtonElement>('roster-entry-cancel-confirm')?.click();
    expect(results).toHaveLength(0);

    set('roster-entry-cancel-reason', 'SCHEDULE_CHANGED', 'change');
    q<HTMLButtonElement>('roster-entry-cancel-confirm')?.click();

    expect(results).toEqual([{ kind: 'cancel', reason: 'SCHEDULE_CHANGED' }]);
  });
});
