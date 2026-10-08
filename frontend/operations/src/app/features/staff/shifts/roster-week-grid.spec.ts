import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { GridDay, RosterWeekGrid } from './roster-week-grid';
import { RosterEntry } from './staff-shifts-api';

const DAYS: GridDay[] = ['05', '06', '07', '08', '09', '10', '11'].map((day) => ({
  iso: `2026-10-${day}`,
  label: `Day ${day}`,
  isToday: day === '08',
}));

const aziza = { memberId: 'm-1', displayReference: 'S-0001', name: null };
const named = { memberId: 'm-2', displayReference: 'S-0002', name: 'Bekzod Aliyev' };

function entry(over: Partial<RosterEntry> = {}): RosterEntry {
  return {
    id: 'e-1',
    locationId: 'l',
    person: aziza,
    status: 'PUBLISHED',
    plannedStart: '2026-10-08T04:00:00Z',
    plannedEnd: '2026-10-08T13:00:00Z',
    plannedBreakMinutes: 0,
    publishedAt: null,
    version: 1,
    ...over,
  };
}

describe('RosterWeekGrid (ADR 0156)', () => {
  let fixture: ComponentFixture<RosterWeekGrid>;

  async function setUp(
    entries: RosterEntry[],
    options: { canManage?: boolean; extra?: (typeof aziza)[] } = {},
  ): Promise<{ added: unknown[]; opened: RosterEntry[] }> {
    await TestBed.configureTestingModule({ imports: [RosterWeekGrid] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RosterWeekGrid);
    fixture.componentRef.setInput('days', DAYS);
    fixture.componentRef.setInput('entries', entries);
    fixture.componentRef.setInput('extraPeople', options.extra ?? []);
    fixture.componentRef.setInput('zone', 'Asia/Tashkent');
    fixture.componentRef.setInput('canManage', options.canManage ?? true);
    const added: unknown[] = [];
    const opened: RosterEntry[] = [];
    fixture.componentInstance.add.subscribe((value) => added.push(value));
    fixture.componentInstance.open.subscribe((value) => opened.push(value));
    fixture.detectChanges();
    return { added, opened };
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const all = (testId: string): HTMLElement[] =>
    Array.from(root().querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`));

  beforeEach(() => TestBed.resetTestingModule());

  it('puts each planned shift under its person and day, in the branch’s local time', async () => {
    await setUp([entry()]);

    expect(all('roster-entry')).toHaveLength(1);
    const cell = all('roster-entry')[0].closest('td');
    const cells = Array.from(cell?.parentElement?.querySelectorAll('td') ?? []);
    expect(cells.indexOf(cell as HTMLTableCellElement)).toBe(3);
    expect(all('roster-entry')[0].textContent).toContain('09:00–18:00');
  });

  it('shows a name beside the reference only when the platform sent one', async () => {
    await setUp([entry(), entry({ id: 'e-2', person: named })]);

    const people = all('roster-row-person').map((node) =>
      node.textContent?.replace(/\s+/g, ' ').trim(),
    );
    // Rows sort by what is shown: a name sorts before a reference.
    expect(people).toEqual(['Bekzod Aliyev S-0002', 'S-0001']);
  });

  it('marks today’s column as the current date and no other', async () => {
    await setUp([entry()]);

    const marked = Array.from(root().querySelectorAll('[aria-current="date"]'));
    expect(marked).toHaveLength(1);
    expect(marked[0].textContent).toContain('Day 08');
  });

  it('draws an empty cell as a button that adds a shift for that person and day', async () => {
    const { added } = await setUp([entry()]);

    const empties = all('roster-empty-cell');
    expect(empties).toHaveLength(6);
    empties[0].click();

    expect(added).toEqual([{ date: '2026-10-05', memberId: 'm-1' }]);
  });

  it('lets a reader see the plan but not change it', async () => {
    const { added, opened } = await setUp([entry()], { canManage: false });

    for (const empty of all('roster-empty-cell')) {
      expect((empty as HTMLButtonElement).disabled).toBe(true);
    }
    all('roster-empty-cell')[0].click();
    all('roster-entry')[0].click();
    expect(added).toEqual([]);
    expect(opened).toEqual([]);
  });

  it('opens an entry for change, and marks a draft apart from a published one', async () => {
    const { opened } = await setUp([
      entry({ status: 'DRAFT' }),
      entry({
        id: 'e-2',
        person: { memberId: 'm-3', displayReference: 'S-0003', name: null },
        status: 'PUBLISHED',
      }),
    ]);

    expect(all('roster-entry')[0].className).toContain('grid__entry--draft');
    expect(all('roster-entry')[1].className).not.toContain('grid__entry--draft');
    all('roster-entry')[0].click();
    expect(opened.map((e) => e.id)).toEqual(['e-1']);
  });

  it('lists people the manager added by hand even before they have a shift', async () => {
    await setUp([], { extra: [aziza] });

    expect(all('roster-row-person')).toHaveLength(1);
    expect(all('roster-none')).toHaveLength(0);
  });

  it('says so when nobody is on the rota', async () => {
    await setUp([]);

    expect(all('roster-none')).toHaveLength(1);
  });

  it('moves between cells with the arrow keys', async () => {
    await setUp([
      entry(),
      entry({
        id: 'e-2',
        person: named,
        plannedStart: '2026-10-09T04:00:00Z',
        plannedEnd: '2026-10-09T13:00:00Z',
      }),
    ]);

    const first = root().querySelector<HTMLElement>('[data-cell="0:0"]');
    first?.focus();
    first?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    expect(document.activeElement).toBe(root().querySelector('[data-cell="0:1"]'));

    (document.activeElement as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }),
    );
    expect(document.activeElement).toBe(root().querySelector('[data-cell="1:1"]'));
  });
});
