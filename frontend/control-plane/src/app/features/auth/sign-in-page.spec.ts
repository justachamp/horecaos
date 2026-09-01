import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { AuthService } from '../../core/auth/auth.service';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { SignInPage } from './sign-in-page';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

class FakeAuth {
  readonly signIn = vi.fn<(username: string, password: string) => Promise<void>>();
}

class FakeSession {
  readonly load = vi.fn().mockResolvedValue(undefined);
}

describe('SignInPage', () => {
  let fixture: ComponentFixture<SignInPage>;
  let auth: FakeAuth;
  let session: FakeSession;
  let router: Router;

  beforeEach(async () => {
    auth = new FakeAuth();
    session = new FakeSession();

    // Russian is this console's default locale (I18nService.DEFAULT_LOCALE);
    // clearing storage keeps every assertion below against that default
    // rather than whatever a previous test file's locale switch left behind.
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [SignInPage],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AuthService, useValue: auth },
        { provide: SessionContextService, useValue: session },
      ],
    }).compileComponents();

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

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(auth.signIn).toHaveBeenCalledWith('aziza', 'correct horse');
  });

  it('loads the session context and navigates to the console root on success', async () => {
    auth.signIn.mockResolvedValue(undefined);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(session.load).toHaveBeenCalledOnce();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('shows a uniform message for a wrong password, never a server-authored session-expired sentence', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'Invalid credentials.' }),
    );
    typeInto('username', 'aziza');
    typeInto('password', 'wrong');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    const message = fixture.nativeElement.querySelector('[role="alert"]').textContent.trim();
    expect(message).toBe('Неверное имя пользователя или пароль.');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows the account-action-required message distinctly', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError({
        status: 401,
        code: 'ACCOUNT_ACTION_REQUIRED',
        detail:
          'This account needs one more step before it can sign in. Contact a platform administrator.',
      }),
    );
    typeInto('username', 'newstaff');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    const message = fixture.nativeElement.querySelector('[role="alert"]').textContent.trim();
    // The catalogue text, not the server's own `detail` — this screen
    // localises by error.code and never shows detail text written for a
    // developer reading a response (ADR 0031).
    expect(message).toContain('ещё один шаг');
  });

  it('re-enables the form and clears loading after a failure', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'nope' }),
    );
    typeInto('username', 'aziza');
    typeInto('password', 'wrong');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(false);
    expect(submitButton().textContent?.trim()).toBe('Войти');
  });
});
