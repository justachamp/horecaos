import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';

@Component({
  selector: 'q-host',
  imports: [OrderReasonDialog],
  template: `
    <q-order-reason-dialog
      [titleKey]="'orders.dialog.reject.title'"
      [confirmLabelKey]="'orders.action.reject'"
      [noteEnabled]="noteEnabled"
      [busy]="busy"
      (confirm)="submissions.push($event)"
      (dismiss)="dismissed = true"
    />
  `,
})
class HostComponent {
  noteEnabled = false;
  busy = false;
  dismissed = false;
  submissions: OrderReasonSubmission[] = [];
}

function render(): { fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>; host: HTMLElement } {
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('OrderReasonDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('refuses to submit with a blank reason and shows why', () => {
    const { fixture, host } = render();

    (host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-reason-dialog-required"]')).not.toBeNull();
    expect(fixture.componentInstance.submissions).toEqual([]);
  });

  it('emits the trimmed reason code on confirm', () => {
    const { fixture, host } = render();

    const input = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    input.value = '  NO_STOCK  ';
    input.dispatchEvent(new Event('input'));
    (host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement).click();

    expect(fixture.componentInstance.submissions).toEqual([{ reasonCode: 'NO_STOCK', note: undefined }]);
  });

  it('does not render a note field when noteEnabled is false', () => {
    const { host } = render();
    expect(host.querySelector('[data-testid="order-reason-dialog-note"]')).toBeNull();
  });

  it('includes the note when enabled and filled in', () => {
    const { fixture, host } = render();
    fixture.componentInstance.noteEnabled = true;
    fixture.detectChanges();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'CUSTOMER_CHANGED_MIND';
    code.dispatchEvent(new Event('input'));

    const note = host.querySelector('[data-testid="order-reason-dialog-note"]') as HTMLTextAreaElement;
    note.value = 'Called to cancel';
    note.dispatchEvent(new Event('input'));

    (host.querySelector('[data-testid="order-reason-dialog-confirm"]') as HTMLButtonElement).click();

    expect(fixture.componentInstance.submissions).toEqual([
      { reasonCode: 'CUSTOMER_CHANGED_MIND', note: 'Called to cancel' },
    ]);
  });

  it('emits dismiss and clears its own fields on close', () => {
    const { fixture, host } = render();

    const code = host.querySelector('[data-testid="order-reason-dialog-code"]') as HTMLInputElement;
    code.value = 'SOMETHING';
    code.dispatchEvent(new Event('input'));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => !b.getAttribute('data-testid'),
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(fixture.componentInstance.dismissed).toBe(true);
  });

  it('disables both buttons while busy', () => {
    const { fixture, host } = render();
    fixture.componentInstance.busy = true;
    fixture.detectChanges();

    const buttons = [...host.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.every((b) => b.disabled)).toBe(true);
  });
});
