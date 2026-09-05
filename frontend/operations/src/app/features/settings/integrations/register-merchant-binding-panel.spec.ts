import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import {
  RegisterBindingSubmission,
  RegisterMerchantBindingPanel,
} from './register-merchant-binding-panel';

/**
 * As with the other two integration panels, `integrations-page.spec.ts`
 * exercises the *wiring* from a `register` event to `writeSecret`-then-
 * `registerMerchantBinding` by emitting directly on the component instance —
 * it never types into this panel's own fields, so it cannot catch a broken
 * `canSubmit()` gate. This file drives the real inputs.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RegisterMerchantBindingPanel', () => {
  let fixture: ComponentFixture<RegisterMerchantBindingPanel>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RegisterMerchantBindingPanel],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RegisterMerchantBindingPanel);
    fixture.detectChanges();
    await flushMicrotasks();
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function submitButton(): HTMLButtonElement {
    return host().querySelector('.primary') as HTMLButtonElement;
  }

  function type(id: string, value: string): void {
    const el = host().querySelector(id) as HTMLInputElement;
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  /** Fills every field to a value that satisfies canSubmit(), then lets a test corrupt one. */
  function fillCompleteForm(): void {
    type('#rmb-legal-entity', 'legal-1');
    type('#rmb-installation', 'inst-2');
    type('#rmb-binding', 'integration-binding-1');
    type('#rmb-account', 'svc-2');
    type('#rmb-callback', 'seg-9876543'); // 11 chars, well over the 8-char floor
    type('#rmb-value', 'a-click-secret');
  }

  it('starts with the submit button disabled — nothing is filled in yet', () => {
    expect(submitButton().disabled).toBe(true);
  });

  it('defaults the provider to CLICK', () => {
    expect((host().querySelector('#rmb-provider') as HTMLSelectElement).value).toBe('CLICK');
  });

  it('emits register with exactly the typed fields once the form is complete', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();

    expect(submitButton().disabled).toBe(false);
    submitButton().click();

    expect(register).toHaveBeenCalledWith({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-2',
      integrationBindingId: 'integration-binding-1',
      merchantAccountReference: 'svc-2',
      callbackPathSegment: 'seg-9876543',
      secretValue: 'a-click-secret',
    } satisfies RegisterBindingSubmission);
  });

  it('switches to PAYME and carries that provider type through to the emitted submission', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();

    const select = host().querySelector('#rmb-provider') as HTMLSelectElement;
    select.value = 'PAYME';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    submitButton().click();
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({ providerType: 'PAYME' }),
    );
  });

  const requiredTextFields: ReadonlyArray<readonly [string, string]> = [
    ['#rmb-legal-entity', 'legal-1'],
    ['#rmb-installation', 'inst-2'],
    ['#rmb-binding', 'integration-binding-1'],
    ['#rmb-account', 'svc-2'],
    ['#rmb-value', 'a-click-secret'],
  ];

  for (const [selector] of requiredTextFields) {
    it(`never emits register while ${selector} is left blank, even with everything else filled`, () => {
      const register = vi.fn();
      fixture.componentRef.instance.register.subscribe(register);
      fillCompleteForm();
      type(selector, '');

      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      expect(register).not.toHaveBeenCalled();
    });
  }

  it('never emits register while the callback path segment is under the 8-character floor', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();
    type('#rmb-callback', 'short'); // 5 chars — one of the platform's real callback-collision guards

    expect(submitButton().disabled).toBe(true);
    submitButton().click();
    expect(register).not.toHaveBeenCalled();
  });

  it('accepts a callback path segment at exactly the 8-character floor — the boundary itself is valid', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();
    type('#rmb-callback', '12345678'); // exactly 8

    expect(submitButton().disabled).toBe(false);
    submitButton().click();
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({ callbackPathSegment: '12345678' }),
    );
  });

  it('trims whitespace from every text field before emitting', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    type('#rmb-legal-entity', '  legal-1  ');
    type('#rmb-installation', '  inst-2  ');
    type('#rmb-binding', '  integration-binding-1  ');
    type('#rmb-account', '  svc-2  ');
    type('#rmb-callback', '  seg-9876543  ');
    type('#rmb-value', 'a-click-secret');

    submitButton().click();
    expect(register).toHaveBeenCalledWith({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-2',
      integrationBindingId: 'integration-binding-1',
      merchantAccountReference: 'svc-2',
      callbackPathSegment: 'seg-9876543',
      secretValue: 'a-click-secret',
    } satisfies RegisterBindingSubmission);
  });

  it('disables the whole form while submitting, so a double-click cannot double-register', () => {
    fillCompleteForm();
    fixture.componentRef.setInput('submitting', true);
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(true);
    expect((host().querySelector('#rmb-legal-entity') as HTMLInputElement).disabled).toBe(true);
  });

  it('surfaces the parent’s error message honestly rather than staying silent', () => {
    fixture.componentRef.setInput('errorMessage', 'Something went wrong.');
    fixture.detectChanges();

    expect(host().querySelector('.error')?.textContent).toContain('Something went wrong.');
  });

  it('emits cancel on the backdrop click and the close button, never on the drawer body', () => {
    const cancel = vi.fn();
    fixture.componentRef.instance.cancel.subscribe(cancel);

    (host().querySelector('.drawer') as HTMLElement).click();
    expect(cancel).not.toHaveBeenCalled();

    (host().querySelector('.close') as HTMLButtonElement).click();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
