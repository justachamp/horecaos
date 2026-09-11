import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { InlineAlert } from './inline-alert';

function render(): ReturnType<typeof TestBed.createComponent<InlineAlert>> {
  const fixture = TestBed.createComponent(InlineAlert);
  fixture.detectChanges();
  return fixture;
}

function band(fixture: ReturnType<typeof TestBed.createComponent<InlineAlert>>): HTMLElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    '[data-testid="q-inline-alert"]',
  )!;
}

describe('InlineAlert', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('interrupts a screen reader for an error, because the operator is about to move on', () => {
    const fixture = render();
    fixture.componentRef.setInput('severity', 'error');
    fixture.componentRef.setInput('text', 'Could not save.');
    fixture.detectChanges();

    expect(band(fixture).getAttribute('role')).toBe('alert');
  });

  it('waits its turn for every other severity, so a notice never cuts across a row being read', () => {
    for (const severity of ['info', 'success', 'warning'] as const) {
      const fixture = render();
      fixture.componentRef.setInput('severity', severity);
      fixture.componentRef.setInput('text', 'Export finished.');
      fixture.detectChanges();

      expect(band(fixture).getAttribute('role')).toBe('status');
    }
  });

  it('defaults to the polite role, so a component wired up wrong is the quiet kind', () => {
    const fixture = render();
    fixture.componentRef.setInput('text', 'Anything');
    fixture.detectChanges();

    expect(band(fixture).getAttribute('role')).toBe('status');
  });

  it('translates a message key', () => {
    const fixture = render();
    fixture.componentRef.setInput('messageKey', 'customers.create.phoneRequired');
    fixture.detectChanges();

    expect(band(fixture).textContent).toContain('A phone number is required.');
  });

  it('renders already-translated text unchanged', () => {
    const fixture = render();
    fixture.componentRef.setInput('text', 'Заказ изменился');
    fixture.detectChanges();

    expect(band(fixture).textContent).toContain('Заказ изменился');
  });

  it('shows a support reference beside the message when one is given', () => {
    const fixture = render();
    fixture.componentRef.setInput('severity', 'error');
    fixture.componentRef.setInput('text', 'Could not save.');
    fixture.componentRef.setInput('reference', 'STALE_VERSION · 018f-ab');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-inline-alert-reference"]',
      )?.textContent,
    ).toContain('STALE_VERSION');
  });

  it('offers no dismissal by default — a validation error is not the operator’s to hide', () => {
    const fixture = render();
    fixture.componentRef.setInput('text', 'Anything');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-inline-alert-dismiss"]',
      ),
    ).toBeNull();
  });

  it('emits dismiss from its own control when it is dismissible', () => {
    const fixture = render();
    fixture.componentRef.setInput('text', 'Export finished.');
    fixture.componentRef.setInput('dismissible', true);
    fixture.detectChanges();
    let dismissed = 0;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed += 1));

    (
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-inline-alert-dismiss"]',
      ) as HTMLButtonElement
    ).click();

    expect(dismissed).toBe(1);
  });
});
