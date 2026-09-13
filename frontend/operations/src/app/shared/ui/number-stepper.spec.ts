import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

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
});
