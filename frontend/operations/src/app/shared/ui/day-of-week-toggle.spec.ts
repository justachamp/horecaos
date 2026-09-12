import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DayOfWeekToggle } from './day-of-week-toggle';

function render(): ReturnType<typeof TestBed.createComponent<DayOfWeekToggle>> {
  const fixture = TestBed.createComponent(DayOfWeekToggle);
  fixture.detectChanges();
  return fixture;
}

function day(
  fixture: ReturnType<typeof TestBed.createComponent<DayOfWeekToggle>>,
  n: number,
): HTMLButtonElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
    `[data-testid="q-day-of-week-toggle-day-${n}"]`,
  )!;
}

describe('DayOfWeekToggle', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders all seven days, Monday first', () => {
    const fixture = render();

    expect(day(fixture, 1).textContent?.trim()).toBe('Mon');
    expect(day(fixture, 7).textContent?.trim()).toBe('Sun');
  });

  it('marks the given days selected', () => {
    const fixture = render();
    fixture.componentRef.setInput('selected', new Set([2, 4]));
    fixture.detectChanges();

    expect(day(fixture, 2).getAttribute('aria-pressed')).toBe('true');
    expect(day(fixture, 4).getAttribute('aria-pressed')).toBe('true');
    expect(day(fixture, 1).getAttribute('aria-pressed')).toBe('false');
  });

  it('toggles a day on and off in multiple mode', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'multiple');
    fixture.componentRef.setInput('selected', new Set([3]));
    fixture.detectChanges();
    let emitted: ReadonlySet<number> | undefined;
    fixture.componentInstance.selectedChange.subscribe((s) => (emitted = s));

    day(fixture, 5).click();
    expect([...emitted!].sort()).toEqual([3, 5]);

    fixture.componentRef.setInput('selected', emitted);
    fixture.detectChanges();
    day(fixture, 3).click();
    expect([...emitted!].sort()).toEqual([5]);
  });

  it('replaces the whole selection with one day in single mode, never emitting empty', () => {
    const fixture = render();
    fixture.componentRef.setInput('mode', 'single');
    fixture.componentRef.setInput('selected', new Set([1]));
    fixture.detectChanges();
    let emitted: ReadonlySet<number> | undefined;
    fixture.componentInstance.selectedChange.subscribe((s) => (emitted = s));

    day(fixture, 3).click();
    expect([...emitted!]).toEqual([3]);

    fixture.componentRef.setInput('selected', emitted);
    fixture.detectChanges();
    day(fixture, 3).click();
    expect([...emitted!]).toEqual([3]);
  });

  it('gives every day a full-name accessible label', () => {
    const fixture = render();

    expect(day(fixture, 1).getAttribute('aria-label')).toBe('Monday');
  });
});
