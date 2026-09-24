import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderChangeContactDialog } from './order-change-contact-dialog';

function render(
  initialName = '',
  initialPhone = '',
): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderChangeContactDialog>>;
} {
  const fixture = TestBed.createComponent(OrderChangeContactDialog);
  fixture.componentRef.setInput('initialName', initialName);
  fixture.componentRef.setInput('initialPhone', initialPhone);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangeContactDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('seeds both fields from the revealed name and phone', () => {
    const { fixture } = render('Азиз Каримов', '+998901234567');
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (host.querySelector('[data-testid="order-change-contact-dialog-name"]') as HTMLInputElement)
        .value,
    ).toBe('Азиз Каримов');
    expect(
      (host.querySelector('[data-testid="order-change-contact-dialog-phone"]') as HTMLInputElement)
        .value,
    ).toBe('+998901234567');
  });

  it('disables confirm until both fields are non-blank', () => {
    const { fixture } = render('', '');
    const host: HTMLElement = fixture.nativeElement;
    const confirmButton = host.querySelector(
      '[data-testid="order-change-contact-dialog-confirm"]',
    ) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);

    const nameInput = host.querySelector(
      '[data-testid="order-change-contact-dialog-name"]',
    ) as HTMLInputElement;
    nameInput.value = 'Азиз Каримов';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(true);

    const phoneInput = host.querySelector(
      '[data-testid="order-change-contact-dialog-phone"]',
    ) as HTMLInputElement;
    phoneInput.value = '+998901234567';
    phoneInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(false);
  });

  it('emits confirm with the trimmed name and phone', () => {
    const { fixture } = render('  Азиз Каримов  ', '  +998901234567  ');
    const host: HTMLElement = fixture.nativeElement;
    let emitted: { recipientName: string; recipientPhone: string } | null = null;
    fixture.componentInstance.confirm.subscribe((submission) => (emitted = submission));

    (
      host.querySelector('[data-testid="order-change-contact-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(emitted).toEqual({ recipientName: 'Азиз Каримов', recipientPhone: '+998901234567' });
  });

  it('emits dismiss', () => {
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
