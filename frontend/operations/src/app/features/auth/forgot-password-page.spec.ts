import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { messagesEn } from '../../core/i18n/messages.en';
import { ForgotPasswordPage } from './forgot-password-page';
import { PasswordResetsApi } from './password-resets-api';

class FakeResets {
  readonly request = vi.fn<(login: string, locale: string) => Promise<void>>();
  readonly inspect = vi.fn();
  readonly accept = vi.fn();
}

describe('ForgotPasswordPage', () => {
  let fixture: ComponentFixture<ForgotPasswordPage>;
  let resets: FakeResets;

  beforeEach(async () => {
    resets = new FakeResets();

    await TestBed.configureTestingModule({
      imports: [ForgotPasswordPage],
      providers: [provideRouter([]), { provide: PasswordResetsApi, useValue: resets }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');

    fixture = TestBed.createComponent(ForgotPasswordPage);
    fixture.detectChanges();
  });

  // I18n persists the locale to localStorage, and jsdom shares that storage
  // between spec files in a worker, so a spec that leaves a non-default locale
  // behind can fail an unrelated one that asserts Russian. Put it back.
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
    type('aziza');
    expect(submitButton().disabled).toBe(false);
  });

  it('sends the trimmed login and the locale the operator is reading', async () => {
    resets.request.mockResolvedValue(undefined);

    type('  aziza  ');
    await submit();

    expect(resets.request).toHaveBeenCalledWith('aziza', 'en');
  });

  // ADR 0149, Decision 5: the console used to turn `uz-Latn` into a bare `uz` and every other tag into
  // `ru` before asking for the email, so a reader of a language the platform sends in would have got
  // Russian. It sends the tag it reads; the API reads an alias and decides what the platform sends in.
  it.each(['uz-Latn', 'ru', 'en'] as const)(
    'sends the tag %s as it is, not a three-value code',
    async (tag) => {
      resets.request.mockResolvedValue(undefined);
      TestBed.inject(I18n).setLocale(tag);

      type('aziza');
      await submit();

      expect(resets.request).toHaveBeenCalledWith('aziza', tag);
    },
  );

  /**
   * The property ADR 0098 exists to protect, asserted where it can actually be
   * broken. The platform answers 202 for an account that exists and one that
   * does not, so this screen must not develop a second sentence for either.
   */
  it('says the same thing whether or not the account exists', async () => {
    resets.request.mockResolvedValue(undefined);
    type('aziza');
    await submit();
    const forARealAccount = text();

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ForgotPasswordPage],
      providers: [provideRouter([]), { provide: PasswordResetsApi, useValue: resets }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ForgotPasswordPage);
    fixture.detectChanges();
    type('nobody-at-all');
    await submit();

    expect(text()).toBe(forARealAccount);
    expect(text()).toContain(messagesEn['forgotPassword.sent.body']);
    expect(text()).not.toContain('nobody-at-all');
  });

  it('names a refusal of the request itself, and offers the form again', async () => {
    resets.request.mockRejectedValue(
      new ApiError('RATE_LIMIT_EXCEEDED', 429, { status: 429, retryAfterSeconds: 30 }, 'c-1'),
    );

    type('aziza');
    await submit();

    expect(text()).toContain(messagesEn['error.RATE_LIMIT_EXCEEDED']);
    expect(text()).not.toContain(messagesEn['forgotPassword.sent.body']);
    expect(submitButton().disabled).toBe(false);
  });
});
