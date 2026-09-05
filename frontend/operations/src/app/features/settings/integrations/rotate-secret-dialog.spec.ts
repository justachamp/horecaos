import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { RotateSecretDialog, RotateSecretSubmission } from './rotate-secret-dialog';

/**
 * The rotate-through-the-door dialog underneath `IntegrationsPage` (ADR
 * 0065) — a credential rotation for either an installation or a merchant
 * binding. `integrations-page.spec.ts` already proves the *parent* wires a
 * confirmed rotation to the right endpoint, but it does so by calling
 * `.confirm.emit(...)` directly on the component instance, which never
 * exercises this dialog's own submit gating — a form that let an operator
 * rotate a credential to an empty value, or with no audit reason, would
 * still pass that suite. This file is the one that would catch that.
 */
describe('RotateSecretDialog', () => {
  let fixture: ComponentFixture<RotateSecretDialog>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RotateSecretDialog] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RotateSecretDialog);
    fixture.detectChanges();
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function valueInput(): HTMLInputElement {
    return host().querySelector('#rotate-value') as HTMLInputElement;
  }

  function reasonInput(): HTMLInputElement {
    return host().querySelector('#rotate-reason') as HTMLInputElement;
  }

  function submitButton(): HTMLButtonElement {
    return host().querySelector('.primary') as HTMLButtonElement;
  }

  function type(el: HTMLInputElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('starts with the submit button disabled — an empty form must not be submittable', () => {
    expect(submitButton().disabled).toBe(true);
  });

  it('never emits confirm while the new value is blank, even with a reason typed', () => {
    const confirm = vi.fn();
    fixture.componentRef.instance.confirm.subscribe(confirm);

    type(reasonInput(), 'Rotated by BotFather');
    expect(submitButton().disabled).toBe(true);

    submitButton().click();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('never emits confirm while the reason is blank, even with a value typed — no unaudited rotation', () => {
    const confirm = vi.fn();
    fixture.componentRef.instance.confirm.subscribe(confirm);

    type(valueInput(), 'a-new-secret-value');
    expect(submitButton().disabled).toBe(true);

    submitButton().click();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('emits confirm with exactly the typed value and trimmed reason once both are present', () => {
    const confirm = vi.fn();
    fixture.componentRef.instance.confirm.subscribe(confirm);

    type(valueInput(), 'a-new-secret-value');
    type(reasonInput(), '  Rotated by BotFather  ');

    expect(submitButton().disabled).toBe(false);
    submitButton().click();

    expect(confirm).toHaveBeenCalledWith({
      value: 'a-new-secret-value',
      reason: 'Rotated by BotFather',
    } satisfies RotateSecretSubmission);
  });

  it('disables the submit button while a submission is in flight, so a double-click cannot double-rotate', () => {
    fixture.componentRef.setInput('submitting', true);
    type(valueInput(), 'a-new-secret-value');
    type(reasonInput(), 'Rotated by BotFather');

    expect(submitButton().disabled).toBe(true);
  });

  it('shows the unverifiable notice only when the parent says this provider has no harmless check', () => {
    expect(host().querySelector('.notice')).toBeNull();

    fixture.componentRef.setInput('unverifiable', true);
    fixture.detectChanges();
    expect(host().querySelector('.notice')).not.toBeNull();
  });

  it('surfaces the parent’s error message honestly rather than staying silent', () => {
    fixture.componentRef.setInput('errorMessage', 'Something went wrong.');
    fixture.detectChanges();

    const message = host().querySelector('.error')?.textContent ?? '';
    expect(message).toContain('Something went wrong.');
  });

  it('emits cancel on the backdrop click, without touching the typed value', () => {
    const cancel = vi.fn();
    fixture.componentRef.instance.cancel.subscribe(cancel);

    (host().querySelector('.backdrop') as HTMLElement).click();
    expect(cancel).toHaveBeenCalledTimes(1);
  });

  it('does not close when the dialog body itself is clicked — only the backdrop and the cancel button do', () => {
    const cancel = vi.fn();
    fixture.componentRef.instance.cancel.subscribe(cancel);

    (host().querySelector('.dialog') as HTMLElement).click();
    expect(cancel).not.toHaveBeenCalled();
  });
});
