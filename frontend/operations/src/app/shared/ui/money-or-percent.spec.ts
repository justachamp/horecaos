import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MoneyOrPercent } from './money-or-percent';

function render(): ReturnType<typeof TestBed.createComponent<MoneyOrPercent>> {
  const fixture = TestBed.createComponent(MoneyOrPercent);
  fixture.detectChanges();
  return fixture;
}

describe('MoneyOrPercent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders the money input when kind is AMOUNT', () => {
    const fixture = render();
    fixture.componentRef.setInput('kind', 'AMOUNT');
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('q-money-input')).not.toBeNull();
    expect(el.querySelector('q-percent-input')).toBeNull();
  });

  it('renders the percent input when kind is PERCENT', () => {
    const fixture = render();
    fixture.componentRef.setInput('kind', 'PERCENT');
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('q-percent-input')).not.toBeNull();
    expect(el.querySelector('q-money-input')).toBeNull();
  });

  it('emits kindChange when the other kind is selected, never both live at once', () => {
    const fixture = render();
    fixture.componentRef.setInput('kind', 'AMOUNT');
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.kindChange.subscribe((k) => (emitted = k));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-money-or-percent-percent-toggle"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toBe('PERCENT');
  });

  it('does not re-emit the kind that is already active', () => {
    const fixture = render();
    fixture.componentRef.setInput('kind', 'AMOUNT');
    fixture.detectChanges();
    let emissions = 0;
    fixture.componentInstance.kindChange.subscribe(() => (emissions += 1));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-money-or-percent-amount-toggle"]',
      ) as HTMLButtonElement
    ).click();

    expect(emissions).toBe(0);
  });

  it('forwards the percent input change through basisPointsChange', () => {
    const fixture = render();
    fixture.componentRef.setInput('kind', 'PERCENT');
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.basisPointsChange.subscribe((v) => (emitted = v));
    const percentField = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-percent-input-field"]',
    )!;

    percentField.value = '15';
    percentField.dispatchEvent(new Event('input'));

    expect(emitted).toBe(1500);
  });
});
