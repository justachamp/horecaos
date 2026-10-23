import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { TimeInput } from './time-input';

function render(): ReturnType<typeof TestBed.createComponent<TimeInput>> {
  const fixture = TestBed.createComponent(TimeInput);
  fixture.detectChanges();
  return fixture;
}

function field(fixture: ReturnType<typeof TestBed.createComponent<TimeInput>>): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="q-time-input"]',
  )!;
}

describe('TimeInput', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('shows the given value', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '09:30');
    fixture.detectChanges();

    expect(field(fixture).value).toBe('09:30');
  });

  it('emits a fully-typed valid time', () => {
    const fixture = render();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '14:45';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe('14:45');
  });

  it('does not emit while the typed text is not a complete HH:mm value', () => {
    const fixture = render();
    let emitted = false;
    fixture.componentInstance.valueChange.subscribe(() => (emitted = true));
    const input = field(fixture);

    input.value = '14:';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(false);
  });

  it('steps forward one minute on ArrowUp', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '09:59');
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

    field(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));

    expect(emitted).toBe('10:00');
  });

  it('steps backward and wraps across midnight on ArrowDown', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '00:00');
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

    field(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));

    expect(emitted).toBe('23:59');
  });

  it('steps by the given minute step', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '09:00');
    fixture.componentRef.setInput('step', 15);
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

    field(fixture).dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));

    expect(emitted).toBe('09:15');
  });

  it('reverts an incomplete value to the last good one on blur', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '09:00');
    fixture.detectChanges();
    const input = field(fixture);

    input.value = '9';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(input.value).toBe('09:00');
  });

  // ---- brands-and-locations.md §4.7: «Typing 1000 in a time field means 10:00»

  it('takes four typed digits as HHmm: 1000 is 10:00, and the field shows it', () => {
    const fixture = render();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '1000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(emitted).toBe('10:00');
    expect(input.value).toBe('10:00');
  });

  it('refuses four digits that are not a time, and says nothing until they are', () => {
    const fixture = render();
    const emitted: string[] = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    const input = field(fixture);

    for (const typed of ['2460', '1075', '9999']) {
      input.value = typed;
      input.dispatchEvent(new Event('input'));
    }

    expect(emitted).toEqual([]);
  });

  it('completes three digits on leaving the field: 930 is 09:30 -- not before, because 100 may be on its way to 1000', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '08:00');
    fixture.detectChanges();
    const emitted: string[] = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    const input = field(fixture);

    input.value = '930';
    input.dispatchEvent(new Event('input'));
    expect(emitted, 'still typing').toEqual([]);
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(emitted).toEqual(['09:30']);
    expect(input.value).toBe('09:30');
  });

  it('completes one or two digits on leaving the field to the whole hour: 9 is 09:00, 21 is 21:00', () => {
    const fixture = render();
    const emitted: string[] = [];
    fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));
    const input = field(fixture);

    input.value = '9';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    input.value = '21';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));

    expect(emitted).toEqual(['09:00', '21:00']);
  });

  it('puts back the last good value for digits that make no time, as it does for any other text', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', '08:15');
    fixture.detectChanges();
    const input = field(fixture);

    input.value = '99';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(input.value).toBe('08:15');
  });
});
