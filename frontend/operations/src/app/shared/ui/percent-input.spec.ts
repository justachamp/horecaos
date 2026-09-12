import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { PercentInput } from './percent-input';

function render(): ReturnType<typeof TestBed.createComponent<PercentInput>> {
  const fixture = TestBed.createComponent(PercentInput);
  fixture.detectChanges();
  return fixture;
}

function field(
  fixture: ReturnType<typeof TestBed.createComponent<PercentInput>>,
): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    '[data-testid="q-percent-input-field"]',
  )!;
}

describe('PercentInput', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('displays basis points as a percent — 1000 basis points is 10', () => {
    const fixture = render();
    fixture.componentRef.setInput('basisPoints', 1000);
    fixture.detectChanges();

    expect(field(fixture).value).toBe('10');
  });

  it('round-trips the same arithmetic promo-codes-page does inline: percent × 100, rounded', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.basisPointsChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '12.5';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(Math.round(12.5 * 100));
    expect(emitted).toBe(1250);
  });

  it('renders 1250 basis points back as 12.5', () => {
    const fixture = render();
    fixture.componentRef.setInput('basisPoints', 1250);
    fixture.detectChanges();

    expect(field(fixture).value).toBe('12.5');
  });

  it('clamps to the given max — 100% is 10000 basis points by default', () => {
    const fixture = render();
    let emitted: number | undefined;
    fixture.componentInstance.basisPointsChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '250';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(10_000);
  });

  it('clamps to a caller-supplied max', () => {
    const fixture = render();
    fixture.componentRef.setInput('max', 5000);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.basisPointsChange.subscribe((v) => (emitted = v));
    const input = field(fixture);

    input.value = '90';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toBe(5000);
  });

  it('normalises the field on blur', () => {
    const fixture = render();
    const input = field(fixture);

    input.value = '012.50';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(input.value).toBe('12.5');
  });
});
