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

/**
 * `create-customer-dialog` is one of `q-modal`'s two migrated call sites
 * (ADR 0101, row `X.8`) — `order-reason-dialog` is the other, and its own spec
 * carries the matching pair of assertions.
 *
 * The point of the migration is not that the markup moved. It is that this
 * dialog now answers Escape and gives the caret back, which the hand-written
 * copy it replaced never did, and that a failure inside it is now announced to
 * a screen reader (`q-inline-alert`) instead of rendered as a bare paragraph.
 */
describe('CreateCustomerDialog: migrated to shared/ui', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('wears the shared modal chrome and keeps every field it had', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;

    const panel = host.querySelector('[data-testid="q-modal"]')!;
    expect(panel.getAttribute('aria-modal')).toBe('true');
    expect(panel.getAttribute('role')).toBe('dialog');
    // Named by the heading the shared chrome renders, not by a second copy of it.
    const labelledBy = panel.getAttribute('aria-labelledby')!;
    expect(host.querySelector(`#${labelledBy}`)?.textContent).toContain('Create a customer');

    expect(panel.querySelector('[data-testid="create-customer-phone"]')).not.toBeNull();
    expect(panel.querySelector('[data-testid="create-customer-confirm"]')).not.toBeNull();
  });

  it('closes on Escape, which the dialog it replaced did not', () => {
    const { fixture } = render();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(dismissed).toBe(true);
  });

  it('refuses Escape while the create is in flight, so a half-sent form is not lost', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(dismissed).toBe(false);
  });

  it('reports a failure through the shared alert, in the interrupting role', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('errorMessage', 'That phone is already taken.');
    fixture.detectChanges();

    const alert = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-inline-alert"]',
    )!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('That phone is already taken.');
  });
});
