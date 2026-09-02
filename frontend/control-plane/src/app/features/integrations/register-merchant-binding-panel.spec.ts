import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { RegisterBindingSubmission, RegisterMerchantBindingPanel } from './register-merchant-binding-panel';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

describe('RegisterMerchantBindingPanel', () => {
  let fixture: ComponentFixture<RegisterMerchantBindingPanel>;

  beforeEach(async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');

    await TestBed.configureTestingModule({
      imports: [RegisterMerchantBindingPanel],
      providers: [{ provide: APP_CONFIG, useValue: CONFIG }],
    }).compileComponents();

    fixture = TestBed.createComponent(RegisterMerchantBindingPanel);
    fixture.detectChanges();
  });

  function typeInto(id: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Register',
    ) as HTMLButtonElement;
  }

  function fillEveryFieldExcept(skip: string): void {
    const fields: Record<string, string> = {
      'rmb-legal-entity': 'legal-1',
      'rmb-installation': 'inst-1',
      'rmb-binding': 'binding-1',
      'rmb-account': 'svc-1',
      'rmb-callback': 'seg-12345678',
      'rmb-value': 'a-secret-key',
    };
    for (const [id, value] of Object.entries(fields)) {
      if (id !== skip) {
        typeInto(id, value);
      }
    }
  }

  it('disables submit while any required field is empty', () => {
    fillEveryFieldExcept('rmb-value');
    expect(submitButton().disabled).toBe(true);

    typeInto('rmb-value', 'a-secret-key');
    expect(submitButton().disabled).toBe(false);
  });

  it('requires the callback segment to be at least eight characters, mirroring the server pattern', () => {
    fillEveryFieldExcept('rmb-callback');
    typeInto('rmb-callback', 'short');
    expect(submitButton().disabled).toBe(true);

    typeInto('rmb-callback', 'seg-12345678');
    expect(submitButton().disabled).toBe(false);
  });

  it('emits the secret value alongside the rest of the form on submit', () => {
    const handler = vi.fn<(submission: RegisterBindingSubmission) => void>();
    fixture.componentInstance.register.subscribe(handler);

    fillEveryFieldExcept('');
    submitButton().click();

    expect(handler).toHaveBeenCalledWith({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-1',
      integrationBindingId: 'binding-1',
      merchantAccountReference: 'svc-1',
      callbackPathSegment: 'seg-12345678',
      secretValue: 'a-secret-key',
    });
  });

  it('the credential field is never rendered as plain text', () => {
    const input = fixture.nativeElement.querySelector('#rmb-value') as HTMLInputElement;
    expect(input.type).toBe('password');
  });
});
