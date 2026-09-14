import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderNoteDialog } from './order-note-dialog';

/** `setInput`, not a host template — see `order-reason-dialog.spec.ts`'s identical note (zoneless, ADR 0035). */
function render(initialValue = ''): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderNoteDialog>>;
} {
  const fixture = TestBed.createComponent(OrderNoteDialog);
  fixture.componentRef.setInput('titleKey', 'orders.detail.comments.courier');
  fixture.componentRef.setInput('initialValue', initialValue);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderNoteDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('starts with the initial value and emits it trimmed on confirm', () => {
    const { fixture } = render('  Позвонить у ворот  ');
    const host: HTMLElement = fixture.nativeElement;
    const submissions: string[] = [];
    fixture.componentInstance.confirm.subscribe((note) => submissions.push(note));

    const textarea = host.querySelector(
      '[data-testid="order-note-dialog-textarea"]',
    ) as HTMLTextAreaElement;
    expect(textarea.value).toContain('Позвонить у ворот');

    (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement).click();

    expect(submissions).toEqual(['Позвонить у ворот']);
  });

  it('emits an edited value, trimmed, and updates the character count', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: string[] = [];
    fixture.componentInstance.confirm.subscribe((note) => submissions.push(note));

    const textarea = host.querySelector(
      '[data-testid="order-note-dialog-textarea"]',
    ) as HTMLTextAreaElement;
    textarea.value = '  VIP клиент  ';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement).click();

    expect(submissions).toEqual(['VIP клиент']);
  });

  it('disables both buttons while busy', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (host.querySelector('[data-testid="order-note-dialog-confirm"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('emits dismiss when the operator clicks away from the dialog', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });
});
