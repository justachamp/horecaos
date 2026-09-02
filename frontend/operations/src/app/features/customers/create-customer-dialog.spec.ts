import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CreateCustomerDialog, CreateCustomerSubmission } from './create-customer-dialog';

function render(): { fixture: ReturnType<typeof TestBed.createComponent<CreateCustomerDialog>> } {
  const fixture = TestBed.createComponent(CreateCustomerDialog);
  fixture.detectChanges();
  return { fixture };
}

describe('CreateCustomerDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('refuses to submit with a blank phone and shows why', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: CreateCustomerSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    (host.querySelector('[data-testid="create-customer-confirm"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.textContent).toContain('A phone number is required.');
    expect(submissions).toEqual([]);
  });

  it('emits the trimmed phone and display name on confirm', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: CreateCustomerSubmission[] = [];
    fixture.componentInstance.confirm.subscribe((s) => submissions.push(s));

    const phone = host.querySelector('[data-testid="create-customer-phone"]') as HTMLInputElement;
    phone.value = '  +998901112233  ';
    phone.dispatchEvent(new Event('input'));

    const name = host.querySelector('[data-testid="create-customer-name"]') as HTMLInputElement;
    name.value = 'Dilnoza';
    name.dispatchEvent(new Event('input'));

    (host.querySelector('[data-testid="create-customer-confirm"]') as HTMLButtonElement).click();

    expect(submissions).toEqual([{ phone: '+998901112233', displayName: 'Dilnoza' }]);
  });

  it('shows the supplied error message', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('errorMessage', 'Something went wrong.');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Something went wrong.');
  });

  it('disables both buttons while busy', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    expect(buttons.every((b) => b.disabled)).toBe(true);
  });

  it('emits dismiss on close', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const phone = host.querySelector('[data-testid="create-customer-phone"]') as HTMLInputElement;
    phone.value = '+998901112233';
    phone.dispatchEvent(new Event('input'));

    (host.querySelector('.dialog__dismiss') as HTMLButtonElement).click();

    expect(dismissed).toBe(true);
  });
});
