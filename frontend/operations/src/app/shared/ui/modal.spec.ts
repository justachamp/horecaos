import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Modal } from './modal';

/**
 * A host, unlike `order-reason-dialog.spec.ts`'s direct `createComponent`,
 * because everything this component promises is about *content*: the trap has
 * to have something to trap, and focus restore has to have somewhere to go
 * back to. The host renders an invoking button outside the modal and two
 * controls inside it, which is the smallest arrangement in which "focus cannot
 * leave" and "focus comes back" are distinguishable from "focus never moved".
 *
 * `open` is a signal read in the template rather than a plain field, because
 * this application is zoneless: a plain field changed in a test never marks
 * the host dirty and the `@if` never re-evaluates.
 */
@Component({
  selector: 'q-modal-host',
  imports: [Modal],
  template: `
    <button type="button" id="invoker" (click)="open.set(true)">Open</button>
    @if (open()) {
      <q-modal title="Cancel order" [dismissible]="dismissible()" (dismiss)="onDismiss()">
        <input id="first" type="text" />
        <button type="button" id="last">Confirm</button>
      </q-modal>
    }
  `,
})
class ModalHost {
  readonly open = signal(false);
  readonly dismissible = signal(true);
  dismissals = 0;

  onDismiss(): void {
    this.dismissals += 1;
    this.open.set(false);
  }
}

function escape(): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
}

function tab(options: { shift?: boolean } = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', {
    key: 'Tab',
    shiftKey: options.shift === true,
    bubbles: true,
    cancelable: true,
  });
}

describe('Modal', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<ModalHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(ModalHost);
    // Attached to the document on purpose: `document.activeElement` is `<body>`
    // for a detached tree, so a focus test on one proves nothing.
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

  it('moves focus to the first control inside it when it opens', () => {
    openFromInvoker();
    expect(document.activeElement?.id).toBe('first');
  });

  it('closes on Escape', () => {
    openFromInvoker();
    document.dispatchEvent(escape());
    fixture.detectChanges();

    expect(fixture.componentInstance.dismissals).toBe(1);
    expect(host.querySelector('[data-testid="q-modal"]')).toBeNull();
  });

  it('returns focus to the control that opened it', () => {
    const invoker = openFromInvoker();
    expect(document.activeElement).not.toBe(invoker);

    document.dispatchEvent(escape());
    fixture.detectChanges();

    expect(document.activeElement).toBe(invoker);
  });

  it('refuses Escape while it is not dismissible, so an in-flight mutation is not abandoned', () => {
    fixture.componentInstance.dismissible.set(false);
    openFromInvoker();

    document.dispatchEvent(escape());
    fixture.detectChanges();

    expect(fixture.componentInstance.dismissals).toBe(0);
    expect(host.querySelector('[data-testid="q-modal"]')).not.toBeNull();
  });

  it('wraps Tab from the last control back to the first rather than leaving for the page behind', () => {
    openFromInvoker();
    host.querySelector<HTMLButtonElement>('#last')!.focus();

    document.dispatchEvent(tab());

    expect(document.activeElement?.id).toBe('first');
  });

  it('wraps Shift+Tab from the first control to the last', () => {
    openFromInvoker();
    host.querySelector<HTMLInputElement>('#first')!.focus();

    document.dispatchEvent(tab({ shift: true }));

    expect(document.activeElement?.id).toBe('last');
  });

  it('pulls focus back in when Tab is pressed from outside the panel', () => {
    const invoker = openFromInvoker();
    // The browser, or a stray click, has left focus behind the modal.
    invoker.focus();

    document.dispatchEvent(tab());

    expect(document.activeElement?.id).toBe('first');
  });

  it('names itself with the heading it renders, rather than a second copy of the title', () => {
    openFromInvoker();
    const panel = host.querySelector('[data-testid="q-modal"]')!;
    const labelledBy = panel.getAttribute('aria-labelledby');

    expect(labelledBy).not.toBeNull();
    expect(panel.getAttribute('aria-label')).toBeNull();
    expect(host.querySelector(`#${labelledBy}`)?.textContent?.trim()).toBe('Cancel order');
  });

  it('dismisses on a press that starts and ends on the backdrop', () => {
    openFromInvoker();
    const backdrop = host.querySelector<HTMLElement>('[data-testid="q-modal-backdrop"]')!;

    backdrop.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.dismissals).toBe(1);
  });

  it('does not dismiss when the press began inside the panel and only the release landed outside', () => {
    openFromInvoker();
    const backdrop = host.querySelector<HTMLElement>('[data-testid="q-modal-backdrop"]')!;
    const field = host.querySelector<HTMLInputElement>('#first')!;

    // Selecting text in the field and releasing over the scrim: the DOM
    // delivers this as a click on the backdrop.
    field.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.dismissals).toBe(0);
  });
});
