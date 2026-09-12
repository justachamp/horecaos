import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MoneyInput } from './money-input';

function render(): ReturnType<typeof TestBed.createComponent<MoneyInput>> {
  const fixture = TestBed.createComponent(MoneyInput);
  fixture.detectChanges();
  return fixture;
}

function field(fixture: ReturnType<typeof TestBed.createComponent<MoneyInput>>): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="q-money-input-field"]',
  )!;
}

describe('MoneyInput', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('ru');
  });

  it('groups the initial value with NBSP thousands separators', () => {
    const fixture = render();
    fixture.componentRef.setInput('valueMinor', 125_000);
    fixture.detectChanges();

    expect(field(fixture).value).toBe('125 000');
  });

  it('groups a value as it is typed', () => {
    const fixture = render();
    const input = field(fixture);

    input.value = '146000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(input.value).toBe('146 000');
  });

  it('tolerates a paste of "125 000" and emits the integer 125000', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.valueMinorChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '125 000';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(125_000);
  });

  it('rejects a decimal separator and still emits a plain integer', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.valueMinorChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '125.50';
    input.dispatchEvent(new Event('input'));

    expect(Number.isInteger(emitted)).toBe(true);
    expect(emitted).toBe(12_550);
  });

  it('never leaves the field non-numeric on empty input', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.valueMinorChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(0);
  });

  it('refuses a typed negative amount instead of silently flipping its sign', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.valueMinorChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '-500';
    input.dispatchEvent(new Event('input'));

    // Stripping non-digit characters (spaces, a dot) is deliberate — but a
    // leading minus is a sign, not noise. Silently dropping it would turn a
    // typed "-500" into +500, the opposite of what was typed. There is no
    // negative-amount call site today, so the honest outcome is the same one
    // an empty field already produces: reset to 0, not a flipped magnitude.
    expect(emitted).toBe(0);
  });

  it('shows the som suffix in Russian', () => {
    const fixture = render();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('сум');
  });

  it('does not reformat under the cursor when the parent echoes back the same value', () => {
    const fixture = render();
    const input = field(fixture);
    input.value = '146000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    // The parent stores the emitted value and reflects it straight back, the
    // way `[value]="x()" (valueMinorChange)="x.set($event)"` behaves.
    fixture.componentRef.setInput('valueMinor', 146_000);
    fixture.detectChanges();

    expect(input.value).toBe('146 000');
  });
});
