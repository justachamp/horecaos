import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { I18n } from '../../core/i18n/i18n';
import { SignInPage } from './sign-in-page';

class FakeAuth {
  readonly signIn = vi.fn<(username: string, password: string, otp?: string) => Promise<boolean>>();
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
    auth.signIn.mockResolvedValue(false);
    typeInto('username', '  aziza  ');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(auth.signIn).toHaveBeenCalledWith('aziza', 'correct horse', undefined);
  });

  it('navigates to Today when there was no remembered deep link', async () => {
    auth.signIn.mockResolvedValue(false);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/today');
  });

  it('navigates back to the deep link the guard remembered before sign-in', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '/orders/018f-late-one');
    auth.signIn.mockResolvedValue(false);
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

  async function reachTheCodeStep(): Promise<void> {
    auth.signIn.mockRejectedValueOnce(
      new ApiError('MFA_REQUIRED', 401, { status: 401, code: 'MFA_REQUIRED' }, null),
    );
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
    expect(fixture.nativeElement.textContent).toContain('Enter your code');
  });

  it('submits by itself on the sixth digit, with the credentials from the first step and the code', async () => {
    await reachTheCodeStep();
    auth.signIn.mockResolvedValueOnce(false);

    typeCode('482913');
    await fixture.whenStable();

    expect(auth.signIn).toHaveBeenLastCalledWith('aziza', 'correct horse', '482913');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/today');
  });

  it('says the code is wrong, clears it and stays on the code step', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(
      new ApiError('MFA_CODE_INVALID', 401, { status: 401, code: 'MFA_CODE_INVALID' }, null),
    );

    typeCode('000000');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'That code is not right',
    );
    expect(cells().map((cell) => cell.value)).toEqual(['', '', '', '', '', '']);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('says how long to wait when the code budget is spent, in whole minutes', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(
      new ApiError(
        'RATE_LIMIT_EXCEEDED',
        429,
        { status: 429, code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 720 },
        null,
      ),
    );

    typeCode('111111');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('12 min');
  });

  it('goes back to the password step when the password stops being accepted between the two steps', async () => {
    await reachTheCodeStep();
    auth.signIn.mockRejectedValueOnce(
      new ApiError('UNAUTHENTICATED', 401, { status: 401, code: 'UNAUTHENTICATED' }, null),
    );

    typeCode('222222');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#password')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'password was not accepted',
    );
  });

  it('lets the person use a different account from the code step, forgetting the password', async () => {
    await reachTheCodeStep();

    (fixture.nativeElement.querySelector('button.link') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#password')).not.toBeNull();
    expect((fixture.nativeElement.querySelector('#password') as HTMLInputElement).value).toBe('');
  });

  it('hands the enrolment ticket to the in-memory holder and moves to enrolment when the account must hold a factor and has none', async () => {
    auth.signIn.mockRejectedValue(
      new ApiError(
        'MFA_ENROLMENT_REQUIRED',
        403,
        {
          status: 403,
          code: 'MFA_ENROLMENT_REQUIRED',
          enrolmentTicket: 'a-ticket',
          expiresAt: '2026-10-07T10:15:00Z',
        },
        null,
      ),
    );
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(TestBed.inject(MfaTicket).ticket()).toBe('a-ticket');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/enrol-second-factor');
    expect(sessionStorage.length, 'a ticket is never persisted').toBe(0);
  });

  it('lands on the profile security card, once, when the platform only offers a second factor', async () => {
    auth.signIn.mockResolvedValue(true);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/my-profile#second-factor');
  });

  it('prefers the remembered deep link over the offer', async () => {
    sessionStorage.setItem(RETURN_TO_KEY, '/orders/018f-late-one');
    auth.signIn.mockResolvedValue(true);
    typeInto('username', 'aziza');
    typeInto('password', 'correct horse');

    submit();
    await fixture.whenStable();

    expect(router.navigateByUrl).toHaveBeenCalledWith('/orders/018f-late-one');
  });

  it('offers the way out for somebody who cannot remember their password (ADR 0098)', () => {
    const link = fixture.nativeElement.querySelector('a[href="/forgot-password"]');

    expect(
      link,
      'without this link the reset flow is unreachable from the only screen that needs it',
    ).not.toBeNull();
    expect(link.textContent.trim()).toBe('Forgot password?');
  });
});
