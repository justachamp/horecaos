import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { AuthService } from '../../core/auth/auth.service';
import { RETURN_TO_KEY } from '../../core/auth/guards';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { SignInPage } from './sign-in-page';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

class FakeAuth {
  readonly signIn = vi.fn<(username: string, password: string, otp?: string) => Promise<boolean>>();
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
    sessionStorage.clear();

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
    auth.signIn.mockResolvedValue(false);
    typeInto('username', '  aziza  ');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(auth.signIn).toHaveBeenCalledWith('aziza', 'correct horse', undefined);
  });

  it('loads the session context and navigates to the console root when there was no remembered deep link', async () => {
    auth.signIn.mockResolvedValue(false);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(session.load).toHaveBeenCalledOnce();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('navigates back to the deep link authGuard remembered before sign-in, and clears it', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '/tenants/t-1/brands');
    auth.signIn.mockResolvedValue(false);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/tenants/t-1/brands');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBeNull();
  });

  it('ignores a stored value that is not a same-document path, to avoid becoming an open redirect', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '//evil.example.com/phish');
    auth.signIn.mockResolvedValue(false);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

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
  // ---------------------------------------------------------------------------------- ADR 0148

  function cells(): HTMLInputElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>(
        '[data-testid="q-otp-input-cell"]',
      ),
    );
  }

  function typeCode(code: string): void {
    const inputs = cells();
    [...code].forEach((digit, index) => {
      inputs[index].value = digit;
      inputs[index].dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();
  }

  function submit(): void {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
  }

  async function reachTheCodeStep(): Promise<void> {
    auth.signIn.mockRejectedValueOnce(new ApiError({ status: 401, code: 'MFA_REQUIRED' }));
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');
    submit();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('asks for the code in q-otp-input when the platform says the account holds a second factor, and shows no error for it', async () => {
    await reachTheCodeStep();

    expect(cells()).toHaveLength(6);
    expect(fixture.nativeElement.querySelector('#password')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[role="alert"]'),
      'MFA_REQUIRED is the next thing to ask for, not a failure',
    ).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Введите код');
  });

  it('submits by itself on the sixth digit, with the credentials from the first step and the code', async () => {
    await reachTheCodeStep();
    auth.signIn.mockResolvedValueOnce(false);

    typeCode('482913');
    await fixture.whenStable();
    // The page awaits the session context before it navigates: one more hop than the operations page.
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(auth.signIn).toHaveBeenLastCalledWith('aziza', 'correct horse', '482913');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('says the code is wrong, clears it and stays on the code step', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(new ApiError({ status: 401, code: 'MFA_CODE_INVALID' }));

    typeCode('000000');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Код не подошёл',
    );
    expect(cells().map((cell) => cell.value)).toEqual(['', '', '', '', '', '']);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('says how long to wait when the code budget is spent, in whole minutes', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(
      new ApiError({ status: 429, code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 720 }),
    );

    typeCode('111111');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('12 мин');
  });

  it('goes back to the password step when the password stops being accepted between the two steps', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(new ApiError({ status: 401, code: 'UNAUTHENTICATED' }));

    typeCode('222222');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#password')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Пароль не принят',
    );
  });

  it('hands the enrolment ticket to the in-memory holder and moves to enrolment when the account must hold a factor and has none', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError({
        status: 403,
        code: 'MFA_ENROLMENT_REQUIRED',
        enrolmentTicket: 'a-ticket',
        expiresAt: '2026-10-07T10:15:00Z',
      }),
    );
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(TestBed.inject(MfaTicket).ticket()).toBe('a-ticket');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/enrol-second-factor');
    expect(sessionStorage.length, 'a ticket is never persisted').toBe(0);
  });

  it('offers the way out for somebody who cannot remember their password (ADR 0098)', () => {
    const link = fixture.nativeElement.querySelector('a[href="/forgot-password"]');

    expect(
      link,
      'without this link the reset flow is unreachable from the only screen that needs it',
    ).not.toBeNull();
  });
});
