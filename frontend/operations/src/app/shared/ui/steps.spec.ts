import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Steps, StepItem } from './steps';

const TWO_STEP: readonly StepItem[] = [
  { id: 'connect', label: 'Connect', state: 'complete' },
  { id: 'bind', label: 'Bind to a brand', state: 'current' },
];

function render(steps: readonly StepItem[]): ReturnType<typeof TestBed.createComponent<Steps>> {
  const fixture = TestBed.createComponent(Steps);
  fixture.componentRef.setInput('steps', steps);
  fixture.detectChanges();
  return fixture;
}

function items(
  fixture: ReturnType<typeof TestBed.createComponent<Steps>>,
): NodeListOf<HTMLElement> {
  return (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="q-steps-item"]');
}

describe('Steps', () => {
  it('renders one item per step, in order', () => {
    const fixture = render(TWO_STEP);

    const rendered = items(fixture);
    expect(rendered).toHaveLength(2);
    expect(rendered[0].textContent).toContain('Connect');
    expect(rendered[1].textContent).toContain('Bind to a brand');
  });

  it('marks the current step for assistive technology', () => {
    const fixture = render(TWO_STEP);

    const rendered = items(fixture);
    expect(rendered[0].getAttribute('aria-current')).toBeNull();
    expect(rendered[1].getAttribute('aria-current')).toBe('step');
  });

  it('distinguishes complete, current and upcoming visually', () => {
    const fixture = render([
      { id: 'a', label: 'A', state: 'complete' },
      { id: 'b', label: 'B', state: 'current' },
      { id: 'c', label: 'C', state: 'upcoming' },
    ]);

    const rendered = items(fixture);
    expect(rendered[0].className).toContain('q-steps__item--complete');
    expect(rendered[1].className).toContain('q-steps__item--current');
    expect(rendered[2].className).toContain('q-steps__item--upcoming');
  });

  it('marks a step that stopped there — a rejection, a cancellation — with the danger tone', () => {
    const fixture = render([
      { id: 'RECEIVED', label: 'Received', state: 'complete' },
      { id: 'REJECTED', label: 'Rejected', state: 'current', tone: 'danger' },
    ]);

    const rendered = items(fixture);
    expect(rendered[1].className).toContain('q-steps__item--danger');
  });

  it('carries the caller’s accessible name for the whole rail', () => {
    const fixture = render(TWO_STEP);
    fixture.componentRef.setInput('ariaLabel', 'Connect a provider, step 2 of 2');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('[data-testid="q-steps"]')
        ?.getAttribute('aria-label'),
    ).toBe('Connect a provider, step 2 of 2');
  });
});
