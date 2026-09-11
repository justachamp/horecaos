import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { en } from '../../core/i18n/messages.en';
import { ForgotPasswordPage } from './forgot-password-page';
import { PasswordResetsApi } from './password-resets-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

class FakeResets {
  readonly request = vi.fn<(login: string, locale: string) => Promise<void>>();
  readonly inspect = vi.fn();
  readonly accept = vi.fn();
}

describe('ForgotPasswordPage', () => {
  let fixture: ComponentFixture<ForgotPasswordPage>;
  let resets: FakeResets;

  async function open(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ForgotPasswordPage],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: PasswordResetsApi, useValue: resets },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ForgotPasswordPage);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    resets = new FakeResets();
    // English, so the assertions below read against the canonical catalogue
    // rather than this console's Russian default.
    localStorage.clear();
    localStorage.setItem('horecaos.control-plane.locale', 'en');
    await open();
  });

  // The locale is persisted to localStorage by I18nService, and jsdom shares
  // that storage between spec files in a worker. Leaving 'en' behind here made
  // an unrelated spec that asserts Russian fail roughly one run in three, so
  // this puts the console back on its default for whatever runs next.
  afterEach(() => {
    localStorage.clear();
  });

  function type(value: string): void {
    const input = fixture.nativeElement.querySelector('#login') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button[type="submit"]');
  }

  async function submit(): Promise<void> {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  it('will not submit an empty login', () => {
    expect(submitButton().disabled).toBe(true);
    type('  ');
    expect(submitButton().disabled).toBe(true);
    type('operator');
    expect(submitButton().disabled).toBe(false);
  });

  it('sends the trimmed login and the locale the operator is reading', async () => {
    resets.request.mockResolvedValue(undefined);

    type('  operator  ');
    await submit();

    expect(resets.request).toHaveBeenCalledWith('operator', 'en');
  });

  /**
   * The property ADR 0098 exists to protect, asserted where it can be broken.
   * The platform answers 202 for an account that exists and one that does not,
   * so this screen must never grow a second sentence for either.
   */
  it('says the same thing whether or not the account exists', async () => {
    resets.request.mockResolvedValue(undefined);
    type('operator');
    await submit();
    const forARealAccount = text();

    TestBed.resetTestingModule();
    await open();
    type('nobody-at-all');
    await submit();

    expect(text()).toBe(forARealAccount);
    expect(text()).toContain(en['forgotPassword.sent.body']);
    expect(text()).not.toContain('nobody-at-all');
  });

  it('names a refusal of the request itself, and offers the form again', async () => {
    resets.request.mockRejectedValue(
      new ApiError({ status: 429, code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 30 }),
    );

    type('operator');
    await submit();

    expect(text()).toContain(en['error.RATE_LIMIT_EXCEEDED']);
    expect(text()).not.toContain(en['forgotPassword.sent.body']);
    expect(submitButton().disabled).toBe(false);
  });
});
