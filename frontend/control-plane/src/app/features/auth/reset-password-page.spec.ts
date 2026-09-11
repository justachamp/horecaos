import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { AuthService } from '../../core/auth/auth.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { I18nService } from '../../core/i18n/i18n.service';
import { en } from '../../core/i18n/messages.en';
import { PasswordResetAcceptance, PasswordResetsApi, ResetInspection } from './password-resets-api';
import { ResetPasswordPage } from './reset-password-page';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const TOKEN = 'k8Qm2v1Xo9-pL3sW7yZ0aB4cD6eF8gH1iJ2kL3mN4oP';

const INSPECTION: ResetInspection = {
  console: 'CONTROL_PLANE',
  maskedLogin: 'o***r@horecaos.uz',
  expiresAt: '2026-09-11T10:00:00Z',
  locale: 'en',
};

class FakeResets {
  readonly request = vi.fn();
  readonly inspect = vi.fn<(token: string) => Promise<ResetInspection>>();
  readonly accept = vi.fn<(token: string, password: string) => Promise<PasswordResetAcceptance>>();
}

/** Only the one method the page may call: signing out at the platform would end somebody else's session. */
class FakeAuth {
  readonly forgetLocalSession = vi.fn();
}

describe('ResetPasswordPage', () => {
  let fixture: ComponentFixture<ResetPasswordPage>;
  let resets: FakeResets;
  let auth: FakeAuth;
  let router: Router;

  async function open(fragment: string | null): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ResetPasswordPage],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: PasswordResetsApi, useValue: resets },
        { provide: AuthService, useValue: auth },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(ResetPasswordPage);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function type(id: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function fill(password = 'a-long-enough-passphrase', confirm = password): void {
    type('password', password);
    type('confirm', confirm);
  }

  function submitButton(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('button[type="submit"]');
  }

  async function submit(): Promise<void> {
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  beforeEach(() => {
    resets = new FakeResets();
    auth = new FakeAuth();
    localStorage.clear();
  });

  // The locale is persisted to localStorage by I18nService, and jsdom shares
  // that storage between spec files in a worker. Leaving 'en' behind here made
  // an unrelated spec that asserts Russian fail roughly one run in three, so
  // this puts the console back on its default for whatever runs next.
  afterEach(() => {
    localStorage.clear();
  });

  it('reads the token from the fragment and shows the masked account, in its language', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    await open(`token=${TOKEN}`);

    expect(resets.inspect).toHaveBeenCalledWith(TOKEN);
    expect(TestBed.inject(I18nService).locale()).toBe('en');
    expect(text()).toContain('o***r@horecaos.uz');
  });

  it('will not submit a short password or two that differ', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    await open(`token=${TOKEN}`);

    fill('short');
    expect(submitButton().disabled).toBe(true);
    fill('a-long-enough-passphrase', 'a-long-enough-passphrasX');
    expect(submitButton().disabled).toBe(true);
    expect(text()).toContain(en['resetPassword.mismatch']);
    fill();
    expect(submitButton().disabled).toBe(false);
  });

  /**
   * A reset sends the password and nothing else. A name field here would
   * overwrite something the person set themselves and mark an address verified
   * because somebody opened a link — the two things ADR 0098 keeps
   * `setPassword` apart from `completeSetup` to avoid.
   */
  it('sends only the token and the password, then ends this tab and confirms', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockResolvedValue({ sessionsEnded: true });
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(resets.accept).toHaveBeenCalledWith(TOKEN, 'a-long-enough-passphrase');
    expect(resets.accept.mock.calls[0]).toHaveLength(2);
    // The tab may have restored a session at boot, and the access token it
    // already holds outlives the revocation the accept performed.
    expect(auth.forgetLocalSession).toHaveBeenCalled();
    // The card the operator is actually shown, rather than a stage the next
    // statement discards: this is the only place they are told that every
    // other device has been signed out.
    expect(text()).toContain(en['resetPassword.done.body']);
    expect(text()).toContain(en['resetPassword.toSignIn']);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  /**
   * The reset succeeded and the revocation did not.
   *
   * Keycloak can take the password and refuse the logout, and the platform
   * answers 200 either way because the password really did change — telling
   * the operator it failed would send them to retry a link that no longer
   * exists. What must not happen is this card asserting that every other
   * session has ended: the person reading it is the only one present who can
   * sign those devices out or raise the alarm, and the
   * `sessions_not_ended` audit fact reaches somebody else days later.
   */
  it('says so when the password was set and the other sessions could not be ended', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockResolvedValue({ sessionsEnded: false });
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(text()).toContain(en['resetPassword.doneSessionsNotEnded.body']);
    expect(text()).not.toContain(en['resetPassword.done.body']);
    // Still a success: the password is set, so the form is gone and the way
    // back to sign in is offered rather than a retry of a spent link.
    expect(submitButton()).toBeNull();
    expect(text()).toContain(en['resetPassword.toSignIn']);
    expect(auth.forgetLocalSession).toHaveBeenCalled();
  });

  it('names the password rule the identity provider refused, and keeps the form', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockRejectedValue(
      new ApiError({
        status: 400,
        code: 'VALIDATION_FAILED',
        policy: 'invalidPasswordNotEmailMessage',
      }),
    );
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(text()).toContain(en['resetPassword.policy.notEmail']);
    expect(submitButton()).not.toBeNull();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('says an expired link has expired, and one with no token cannot be used', async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');
    resets.inspect.mockRejectedValue(
      new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', reason: 'EXPIRED' }),
    );
    await open(`token=${TOKEN}`);
    expect(text()).toContain(en['resetPassword.expired.title']);

    TestBed.resetTestingModule();
    resets = new FakeResets();
    await open(null);
    expect(resets.inspect).not.toHaveBeenCalled();
    expect(text()).toContain(en['resetPassword.invalid.title']);
  });

  /**
   * A blip is not a dead link. Saying "this link cannot be used" sends the
   * operator to ask for another one, and that request clears the stored hash
   * of the perfectly good link they are holding — so a moment of bad wifi
   * costs them the recovery they were in the middle of.
   */
  it('offers a retry when the platform cannot be reached, and keeps the token', async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');
    resets.inspect.mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_UNREACHABLE' }));
    await open(`token=${TOKEN}`);

    expect(text()).not.toContain(en['resetPassword.invalid.title']);
    expect(text()).toContain(en['resetPassword.retry.title']);
    expect(text()).toContain(en['error.NETWORK_UNREACHABLE']);

    resets.inspect.mockResolvedValue(INSPECTION);
    fixture.nativeElement.querySelector('button[type="button"]').click();
    await settle();

    expect(resets.inspect).toHaveBeenCalledTimes(2);
    expect(resets.inspect).toHaveBeenLastCalledWith(TOKEN);
    expect(submitButton()).not.toBeNull();
  });

  it('names a refused rate limit rather than calling a live link dead', async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');
    resets.inspect.mockRejectedValue(
      new ApiError({ status: 429, code: 'RATE_LIMIT_EXCEEDED', retryAfterSeconds: 30 }),
    );
    await open(`token=${TOKEN}`);

    expect(text()).toContain(en['error.RATE_LIMIT_EXCEEDED']);
    expect(text()).not.toContain(en['resetPassword.invalid.title']);
  });

  it('falls back to the invalid state when a link is spent between opening and submitting', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockRejectedValue(
      new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', reason: 'INVALID' }),
    );
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(text()).toContain(en['resetPassword.invalid.title']);
  });
});
