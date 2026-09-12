import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ScheduleException, ScheduleGrid, ScheduleRule } from './schedule-grid';

function render(): ReturnType<typeof TestBed.createComponent<ScheduleGrid>> {
  const fixture = TestBed.createComponent(ScheduleGrid);
  fixture.detectChanges();
  return fixture;
}

describe('ScheduleGrid', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders a weekly rule on its own day row', () => {
    const rule: ScheduleRule = { dayOfWeek: 3, opensAt: '09:00', closesAt: '18:00' };
    const fixture = render();
    fixture.componentRef.setInput('rules', [rule]);
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-schedule-grid-row-3"]',
    )!;
    expect(row.querySelector('[data-testid="q-schedule-grid-window"]')).not.toBeNull();
    expect(row.textContent).toContain('Wednesday');
  });

  it('adds a window to a day, re-emitting the whole rules array', () => {
    const fixture = render();
    fixture.componentRef.setInput('rules', []);
    fixture.detectChanges();
    let emitted: readonly ScheduleRule[] | undefined;
    fixture.componentInstance.rulesChange.subscribe((r) => (emitted = r));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-schedule-grid-add-window-2"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toHaveLength(1);
    expect(emitted?.[0].dayOfWeek).toBe(2);
  });

  it('edits a rule window through its q-time-input, without touching other days', () => {
    const monday: ScheduleRule = { dayOfWeek: 1, opensAt: '09:00', closesAt: '18:00' };
    const friday: ScheduleRule = { dayOfWeek: 5, opensAt: '10:00', closesAt: '22:00' };
    const fixture = render();
    fixture.componentRef.setInput('rules', [monday, friday]);
    fixture.detectChanges();
    let emitted: readonly ScheduleRule[] | undefined;
    fixture.componentInstance.rulesChange.subscribe((r) => (emitted = r));
    const mondayRow = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-schedule-grid-row-1"]',
    )!;
    const opensField = mondayRow.querySelectorAll(
      '[data-testid="q-time-input"]',
    )[0] as HTMLInputElement;

    opensField.value = '08:00';
    opensField.dispatchEvent(new Event('input'));

    expect(emitted).toEqual([{ dayOfWeek: 1, opensAt: '08:00', closesAt: '18:00' }, friday]);
  });

  it('removes a window and re-emits the remaining rules', () => {
    const rule: ScheduleRule = { dayOfWeek: 4, opensAt: '09:00', closesAt: '18:00' };
    const fixture = render();
    fixture.componentRef.setInput('rules', [rule]);
    fixture.detectChanges();
    let emitted: readonly ScheduleRule[] | undefined;
    fixture.componentInstance.rulesChange.subscribe((r) => (emitted = r));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-schedule-grid-remove-window"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toEqual([]);
  });

  it('binds to a dated exception, closed all day, alongside the weekly rules', () => {
    const rule: ScheduleRule = { dayOfWeek: 1, opensAt: '09:00', closesAt: '18:00' };
    const exception: ScheduleException = {
      date: '2026-12-31',
      closedAllDay: true,
      opensAt: null,
      closesAt: null,
    };
    const fixture = render();
    fixture.componentRef.setInput('rules', [rule]);
    fixture.componentRef.setInput('exceptions', [exception]);
    fixture.detectChanges();

    const exceptionRow = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-schedule-grid-exception"]',
    )!;
    expect(exceptionRow.textContent).toContain('2026-12-31');
    expect(
      exceptionRow.querySelector<HTMLInputElement>(
        '[data-testid="q-schedule-grid-exception-closed"]',
      )!.checked,
    ).toBe(true);
    // The weekly grid is untouched by the exception existing.
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-schedule-grid-row-1"]'),
    ).not.toBeNull();
  });

  it('reopening a closed exception gives it a default time window', () => {
    const exception: ScheduleException = {
      date: '2026-12-31',
      closedAllDay: true,
      opensAt: null,
      closesAt: null,
    };
    const fixture = render();
    fixture.componentRef.setInput('exceptions', [exception]);
    fixture.detectChanges();
    let emitted: readonly ScheduleException[] | undefined;
    fixture.componentInstance.exceptionsChange.subscribe((e) => (emitted = e));
    const checkbox = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-schedule-grid-exception-closed"]',
    )!;

    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));

    expect(emitted?.[0].closedAllDay).toBe(false);
    expect(emitted?.[0].opensAt).not.toBeNull();
  });

  it('adds a new dated exception from the date field', () => {
    const fixture = render();
    fixture.componentRef.setInput('exceptions', []);
    fixture.detectChanges();
    let emitted: readonly ScheduleException[] | undefined;
    fixture.componentInstance.exceptionsChange.subscribe((e) => (emitted = e));
    const dateField = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-schedule-grid-new-exception-date"]',
    )!;
    dateField.value = '2027-01-01';

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-schedule-grid-add-exception"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toEqual([
      { date: '2027-01-01', closedAllDay: true, opensAt: null, closesAt: null },
    ]);
  });
});
