import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ConfirmDialog } from './confirm-dialog';

@Component({
  selector: 'q-confirm-host',
  imports: [ConfirmDialog],
  template: `
    <button type="button" id="invoker" (click)="open.set(true)">Cancel order</button>
    @if (open()) {
      <q-confirm-dialog
        title="Cancel order 0138?"
        body="The customer is told immediately."
        confirmLabel="Cancel order"
        cancelLabel="Keep it"
        tone="destructive"
        [busy]="busy()"
        (confirm)="confirmed = confirmed + 1"
        (cancel)="onCancel()"
      />
    }
  `,
})
class ConfirmHost {
  readonly open = signal(false);
  readonly busy = signal(false);
  confirmed = 0;
  cancelled = 0;

  onCancel(): void {
    this.cancelled += 1;
    this.open.set(false);
  }
}

describe('ConfirmDialog', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ConfirmHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(ConfirmHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function openFromInvoker(): HTMLButtonElement {
    const invoker = host.querySelector<HTMLButtonElement>('#invoker')!;
    invoker.focus();
    invoker.click();
    fixture.detectChanges();
    return invoker;
  }

  it('starts with the caret on the cancelling control, so Enter by reflex does not destroy anything', () => {
    openFromInvoker();
    expect(document.activeElement?.getAttribute('data-testid')).toBe('q-confirm-cancel');
  });

  it('treats Escape as Cancel and restores focus to the invoker', () => {
    const invoker = openFromInvoker();

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.cancelled).toBe(1);
    expect(fixture.componentInstance.confirmed).toBe(0);
    expect(document.activeElement).toBe(invoker);
  });

  it('refuses Escape and disables both controls while the confirmed request is in flight', () => {
    fixture.componentInstance.busy.set(true);
    openFromInvoker();

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.cancelled).toBe(0);
    const buttons = [
      ...host.querySelectorAll<HTMLButtonElement>(
        '[data-testid="q-confirm-cancel"], [data-testid="q-confirm-confirm"]',
      ),
    ];
    expect(buttons).toHaveLength(2);
    expect(buttons.every((b) => b.disabled)).toBe(true);
  });

  it('is an alertdialog described by its own body sentence', () => {
    openFromInvoker();
    const panel = host.querySelector('[data-testid="q-confirm-dialog"]')!;

    expect(panel.getAttribute('role')).toBe('alertdialog');
    const describedBy = panel.getAttribute('aria-describedby')!;
    expect(host.querySelector(`#${describedBy}`)?.textContent?.trim()).toBe(
      'The customer is told immediately.',
    );
  });

  it('emits confirm exactly once when the confirming control is used', () => {
    openFromInvoker();
    host.querySelector<HTMLButtonElement>('[data-testid="q-confirm-confirm"]')!.click();

    expect(fixture.componentInstance.confirmed).toBe(1);
    expect(fixture.componentInstance.cancelled).toBe(0);
  });
});
