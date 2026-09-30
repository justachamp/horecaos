import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { NewOrderHeader } from './new-order-header';

interface Inputs {
  aggregatorMode: boolean;
  canSubmitAggregator: boolean;
  aggregatorSubmitting: boolean;
  canSubmit: boolean;
  submitting: boolean;
  outOfHoursConfirming: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const inputs: Inputs = {
    aggregatorMode: false,
    canSubmitAggregator: false,
    aggregatorSubmitting: false,
    canSubmit: true,
    submitting: false,
    outOfHoursConfirming: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(NewOrderHeader);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('NewOrderHeader', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('titles the screen and offers Cancel', () => {
    const { host } = render();

    expect(host.querySelector('h1')?.textContent?.trim()).toBe('New order');
    expect(host.querySelector('.new-order__cancel')?.textContent?.trim()).toBe('Cancel');
  });

  it('shows the ordinary submit button, worded for the state it is in', () => {
    expect(render().byTestId('new-order-submit')?.textContent?.trim()).toBe('Create');
    expect(render({ submitting: true }).byTestId('new-order-submit')?.textContent?.trim()).toBe(
      'Creating…',
    );
    expect(
      render({ outOfHoursConfirming: true }).byTestId('new-order-submit')?.textContent?.trim(),
    ).toBe('Place anyway');
    expect(
      render({ outOfHoursConfirming: true, submitting: true })
        .byTestId('new-order-submit')
        ?.textContent?.trim(),
    ).toBe('Creating…');
  });

  it('holds the submit button until the order can be placed', () => {
    expect(render({ canSubmit: false }).byTestId('new-order-submit')?.disabled).toBe(true);
    expect(render({ canSubmit: true }).byTestId('new-order-submit')?.disabled).toBe(false);
  });

  it('swaps in the aggregator submit button in aggregator mode, with its own rules', () => {
    const idle = render({ aggregatorMode: true, canSubmitAggregator: true, canSubmit: false });
    expect(idle.byTestId('new-order-submit')).toBeNull();
    expect(idle.byTestId('new-order-submit-aggregator')?.disabled).toBe(false);
    expect(idle.byTestId('new-order-submit-aggregator')?.textContent?.trim()).toBe('Create');

    const held = render({ aggregatorMode: true, canSubmitAggregator: false });
    expect(held.byTestId('new-order-submit-aggregator')?.disabled).toBe(true);

    const sending = render({ aggregatorMode: true, aggregatorSubmitting: true });
    expect(sending.byTestId('new-order-submit-aggregator')?.textContent?.trim()).toBe('Creating…');
  });

  it('reflects the aggregator switch', () => {
    const on = render({ aggregatorMode: true });
    expect(
      (on.byTestId('new-order-aggregator-toggle') as unknown as HTMLInputElement).checked,
    ).toBe(true);
    const off = render();
    expect(
      (off.byTestId('new-order-aggregator-toggle') as unknown as HTMLInputElement).checked,
    ).toBe(false);
  });

  it('reports the switch, Cancel and each submit button', () => {
    const plain = render();
    const seen: string[] = [];
    const instance = plain.fixture.componentInstance;
    instance.aggregatorToggled.subscribe(() => seen.push('toggle'));
    instance.cancelRequested.subscribe(() => seen.push('cancel'));
    instance.submitRequested.subscribe(() => seen.push('submit'));
    instance.aggregatorSubmitRequested.subscribe(() => seen.push('aggregator'));

    plain.byTestId('new-order-aggregator-toggle')?.dispatchEvent(new Event('change'));
    plain.host.querySelector<HTMLButtonElement>('.new-order__cancel')?.click();
    plain.byTestId('new-order-submit')?.click();

    const aggregator = render({ aggregatorMode: true, canSubmitAggregator: true });
    aggregator.fixture.componentInstance.aggregatorSubmitRequested.subscribe(() =>
      seen.push('aggregator'),
    );
    aggregator.byTestId('new-order-submit-aggregator')?.click();

    expect(seen).toEqual(['toggle', 'cancel', 'submit', 'aggregator']);
  });
});
