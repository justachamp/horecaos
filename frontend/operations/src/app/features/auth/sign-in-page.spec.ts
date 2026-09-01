import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { I18n } from '../../core/i18n/i18n';
import { SignInPage } from './sign-in-page';

class FakeAuth {
  readonly signIn = vi.fn<(username: string, password: string) => Promise<void>>();
}

describe('SignInPage', () => {
  let fixture: ComponentFixture<SignInPage>;
  let auth: FakeAuth;
  let router: Router;

  beforeEach(async () => {
    auth = new FakeAuth();
    sessionStorage.clear();

    await TestBed.configureTestingModule({
      imports: [SignInPage],
      providers: [provideRouter([]), { provide: Auth, useValue: auth }],
    }).compileComponents();

    // Russian is this console's default locale; pinning it to English keeps
    // this spec's assertions independent of I18n's own default-locale test.
    TestBed.inject(I18n).setLocale('en');

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    fixture = TestBed.createComponent(SignInPage);
    fixture.detectChanges();
  });

  function typeInto(id: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button[type="submit"]');
  }

  function submit(): void {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('disables the submit button until both fields are filled', () => {
    expect(submitButton().disabled).toBe(true);
    typeInto('username', 'aziza');
    expect(submitButton().disabled).toBe(true);
    typeInto('password', 'correct horse');
    expect(submitButton().disabled).toBe(false);
  });

  it('submits the trimmed username and the password as typed', async () => {
    auth.signIn.mockResolvedValue(undefined);
    typeInto('username', '  aziza  ');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(auth.signIn).toHaveBeenCalledWith('aziza', 'correct horse');
  });

  it('navigates to Today when there was no remembered deep link', async () => {
    auth.signIn.mockResolvedValue(undefined);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/today');
  });

  it('navigates back to the deep link the guard remembered before sign-in', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '/orders/018f-late-one');
    auth.signIn.mockResolvedValue(undefined);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/orders/018f-late-one');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBeNull();
  });

  it('shows a uniform message for a wrong password, never a server-authored session-expired sentence', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError('UNAUTHENTICATED', 401, { status: 401, detail: 'Invalid credentials.' }, null),
    );
    typeInto('username', 'aziza');
    typeInto('password', 'wrong');

    submit();
    await fixture.whenStable();
    fixture.detectChanges();

    const message = fixture.nativeElement.querySelector('[role="alert"]').textContent.trim();
    expect(message).toBe('Incorrect username or password.');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows the account-action-required message distinctly', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError('ACCOUNT_ACTION_REQUIRED', 401, { status: 401, detail: 'needs a step' }, null),
    );
    typeInto('username', 'newstaff');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();
    fixture.detectChanges();

    const message = fixture.nativeElement.querySelector('[role="alert"]').textContent.trim();
    expect(message).toContain('one more step');
  });

  it('re-enables the form after a failure', async () => {
    auth.signIn.mockRejectedValue(new ApiError('UNAUTHENTICATED', 401, null, null));
    typeInto('username', 'aziza');
    typeInto('password', 'wrong');

    submit();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(false);
    expect(submitButton().textContent?.trim()).toBe('Sign in');
  });
});
