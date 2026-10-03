import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';

import { NumberStepper } from './number-stepper';

function render(): ReturnType<typeof TestBed.createComponent<NumberStepper>> {
  const fixture = TestBed.createComponent(NumberStepper);
  fixture.detectChanges();
  return fixture;
}

function button(
  fixture: ReturnType<typeof TestBed.createComponent<NumberStepper>>,
  name: 'increment' | 'decrement',
): HTMLButtonElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
    `[data-testid="q-number-stepper-${name}"]`,
  )!;
}

describe('NumberStepper', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('emits value + step on increment', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', 5);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

    button(fixture, 'increment').click();

    expect(emitted).toBe(6);
  });

  it('emits value − step on decrement', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', 5);
    fixture.componentRef.setInput('step', 5);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

    button(fixture, 'decrement').click();

    expect(emitted).toBe(0);
  });

  it('disables increment at the max and never emits past it', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', 10);
    fixture.componentRef.setInput('max', 10);
    fixture.detectChanges();
    let emitted = false;
    fixture.componentInstance.valueChange.subscribe(() => (emitted = true));

    const incrementButton = button(fixture, 'increment');
    expect(incrementButton.disabled).toBe(true);
    incrementButton.click();

    expect(emitted).toBe(false);
  });

  it('disables decrement at the min and never emits below it', () => {
    const fixture = render();
    fixture.componentRef.setInput('value', 0);
    fixture.componentRef.setInput('min', 0);
    fixture.detectChanges();
    let emitted = false;
    fixture.componentInstance.valueChange.subscribe(() => (emitted = true));

    const decrementButton = button(fixture, 'decrement');
    expect(decrementButton.disabled).toBe(true);
    decrementButton.click();

    expect(emitted).toBe(false);
  });

  it('shows a min–max hint when both bounds are set', () => {
    const fixture = render();
    fixture.componentRef.setInput('min', 1);
    fixture.componentRef.setInput('max', 20);
    fixture.detectChanges();

    const hint = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-number-stepper-hint"]',
    );
    expect(hint?.textContent).toBe('1–20');
  });

  it('shows no hint when neither bound is set', () => {
    const fixture = render();

    const hint = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-number-stepper-hint"]',
    );
    expect(hint).toBeNull();
  });

  it('clamps a typed value to the bounds before emitting', () => {
    const fixture = render();
    fixture.componentRef.setInput('min', 0);
    fixture.componentRef.setInput('max', 10);
    fixture.detectChanges();
    let emitted: number | undefined;
    fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-number-stepper-input"]',
    )!;

    input.value = '999';
    input.dispatchEvent(new Event('change'));

    expect(emitted).toBe(10);
  });

  describe('by the portion (ADR 0137)', () => {
    function typed(fixture: ReturnType<typeof render>, text: string): number | undefined {
      let emitted: number | undefined;
      fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));
      const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
        '[data-testid="q-number-stepper-input"]',
      )!;
      input.value = text;
      input.dispatchEvent(new Event('change'));
      return emitted;
    }

    it('moves by a fractional step and stops at a fractional minimum', () => {
      const fixture = render();
      fixture.componentRef.setInput('value', 1);
      fixture.componentRef.setInput('step', 0.5);
      fixture.componentRef.setInput('min', 0.5);
      fixture.detectChanges();
      const emitted: number[] = [];
      fixture.componentInstance.valueChange.subscribe((v) => emitted.push(v));

      button(fixture, 'decrement').click();
      fixture.componentRef.setInput('value', 0.5);
      fixture.detectChanges();
      button(fixture, 'increment').click();

      expect(emitted).toEqual([0.5, 1]);
      expect(button(fixture, 'decrement').disabled).toBe(true);
    });

    it('does not let floating-point noise reach the basket: 0.2 + 0.1 is 0.3', () => {
      const fixture = render();
      fixture.componentRef.setInput('value', 0.2);
      fixture.componentRef.setInput('step', 0.1);
      fixture.detectChanges();
      let emitted: number | undefined;
      fixture.componentInstance.valueChange.subscribe((v) => (emitted = v));

      button(fixture, 'increment').click();

      expect(emitted).toBe(0.3);
    });

    it('reads a typed fraction with a comma or a point', () => {
      const fixture = render();
      fixture.componentRef.setInput('step', 0.25);
      fixture.detectChanges();

      expect(typed(fixture, '0,75')).toBe(0.75);
      expect(typed(fixture, '1.5')).toBe(1.5);
    });

    it('snaps a typed quantity to the nearest portion, which is all the cart accepts', () => {
      const fixture = render();
      fixture.componentRef.setInput('step', 0.25);
      fixture.componentRef.setInput('min', 0.25);
      fixture.detectChanges();

      expect(typed(fixture, '0.6')).toBe(0.5);
      expect(typed(fixture, '0.1')).toBe(0.25);
    });

    it('keeps a whole step whole: a typed fraction is not honoured there, as before', () => {
      const fixture = render();
      fixture.detectChanges();

      expect(typed(fixture, '3.7')).toBe(3);
    });

    it('writes the value and the bounds the way the console writes a quantity', () => {
      TestBed.inject(I18n).setLocale('ru');
      const fixture = render();
      fixture.componentRef.setInput('value', 1.5);
      fixture.componentRef.setInput('step', 0.5);
      fixture.componentRef.setInput('min', 0.5);
      fixture.componentRef.setInput('max', 99);
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;

      expect(
        host.querySelector<HTMLInputElement>('[data-testid="q-number-stepper-input"]')!.value,
      ).toBe('1,5');
      expect(host.querySelector('[data-testid="q-number-stepper-hint"]')?.textContent).toBe(
        '0,5–99',
      );
      expect(
        host.querySelector('[data-testid="q-number-stepper-input"]')?.getAttribute('inputmode'),
      ).toBe('decimal');
    });
  });
});
