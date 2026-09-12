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
});
