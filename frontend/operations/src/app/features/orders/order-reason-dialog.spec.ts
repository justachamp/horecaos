import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';

/**
 * Built and driven directly through `componentRef.setInput` rather than
 * through a wrapping host template. This app has no `NgZone` (ADR 0035's
 * console targets are zoneless throughout), so a change to a *plain* field on
 * a host test component never reaches a child's `input()` signal on a second
 * `detectChanges()` — nothing marks the host dirty for it. `setInput` is the
 * one API documented to mark the component for a check itself, and it is
 * what `withComponentInputBinding()` uses under the router in production, so
 * this is also the closer simulation of the real thing.
 */
function render(): { fixture: ReturnType<typeof TestBed.createComponent<OrderReasonDialog>> } {
  const fixture = TestBed.createComponent(OrderReasonDialog);
  fixture.componentRef.setInput('titleKey', 'orders.dialog.reject.title');
  fixture.componentRef.setInput('confirmLabelKey', 'orders.action.reject');
  fixture.detectChanges();
  return { fixture };
}

describe('OrderReasonDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('refuses to submit with a blank reason and shows why', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: OrderReasonSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-reason-dialog-required"]')).not.toBeNull();
    expect(submissions).toEqual([]);
  });

  it('emits the trimmed reason code on confirm', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: OrderReasonSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    const input = host.querySelector(
      '[data-testid="order-reason-dialog-code"]',
    ) as HTMLInputElement;
    input.value = '  NO_STOCK  ';
    input.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([{ reasonCode: 'NO_STOCK', note: undefined }]);
  });

  it('does not render a note field when noteEnabled is false', () => {
    const { fixture } = render();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-reason-dialog-note"]'),
    ).toBeNull();
  });

  it('includes the note when enabled and filled in', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('noteEnabled', true);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: OrderReasonSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'CUSTOMER_CHANGED_MIND';
    code.dispatchEvent(new Event('input'));

    const note = host.querySelector(
      '[data-testid="order-reason-dialog-note"]',
    ) as HTMLTextAreaElement;
    note.value = 'Called to cancel';
    note.dispatchEvent(new Event('input'));

    (
      host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([
      { reasonCode: 'CUSTOMER_CHANGED_MIND', note: 'Called to cancel' },
    ]);
  });

  it('emits dismiss and clears its own fields on close', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'SOMETHING';
    code.dispatchEvent(new Event('input'));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => !b.getAttribute('data-testid'),
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });

  it('disables both buttons while busy', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.every((b) => b.disabled)).toBe(true);
  });
});
