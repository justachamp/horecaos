import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { I18n } from '../../core/i18n/i18n';
import { messagesEn } from '../../core/i18n/messages.en';
import { messagesRu } from '../../core/i18n/messages.ru';
import { PasswordResetsApi, ResetInspection } from './password-resets-api';
import { ResetPasswordPage } from './reset-password-page';

const TOKEN = 'k8Qm2v1Xo9-pL3sW7yZ0aB4cD6eF8gH1iJ2kL3mN4oP';

const INSPECTION: ResetInspection = {
  console: 'OPERATIONS',
  maskedLogin: 'd***a@example.uz',
  expiresAt: '2026-09-11T10:00:00Z',
  locale: 'en',
};

class FakeResets {
  readonly request = vi.fn();
  readonly inspect = vi.fn<(token: string) => Promise<ResetInspection>>();
  readonly accept = vi.fn<(token: string, password: string) => Promise<void>>();
}

/** Only the one method the page may call: `logout()` would revoke whoever else's session this till holds. */
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
        { provide: PasswordResetsApi, useValue: resets },
        { provide: Auth, useValue: auth },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
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
  });

  // I18n persists the locale to localStorage, and jsdom shares that storage
  // between spec files in a worker, so a spec that leaves a non-default locale
  // behind can fail an unrelated one that asserts Russian. Put it back.
  afterEach(() => {
    localStorage.clear();
  });

  it('reads the token from the fragment and shows the masked account, in its language', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    await open(`token=${TOKEN}`);

    expect(resets.inspect).toHaveBeenCalledWith(TOKEN);
    expect(TestBed.inject(I18n).locale()).toBe('en');
    expect(text()).toContain('d***a@example.uz');
  });

  it('will not submit a short password or two that differ', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    await open(`token=${TOKEN}`);

    fill('short');
    expect(submitButton().disabled).toBe(true);
    fill('a-long-enough-passphrase', 'a-long-enough-passphrasX');
    expect(submitButton().disabled).toBe(true);
    expect(text()).toContain(messagesEn['resetPassword.mismatch']);
    fill();
    expect(submitButton().disabled).toBe(false);
  });

  /**
   * A reset sends the password and nothing else. If this page ever grows a
   * name field it would be writing over something the person set themselves,
   * and marking an address verified because somebody opened a link — the two
   * things ADR 0098 separates `setPassword` from `completeSetup` to avoid.
   */
  it('sends only the token and the password, then ends this tab and confirms', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockResolvedValue(undefined);
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(resets.accept).toHaveBeenCalledWith(TOKEN, 'a-long-enough-passphrase');
    expect(resets.accept.mock.calls[0]).toHaveLength(2);
    // A till the operator stayed signed in on is exactly the case the reset
    // exists for; the tab's own access token outlives the revocation.
    expect(auth.forgetLocalSession).toHaveBeenCalled();
    // And the card is shown rather than set and immediately navigated away
    // from: it is the only place the operator learns their other devices were
    // signed out.
    expect(text()).toContain(messagesEn['resetPassword.done.body']);
    expect(text()).toContain(messagesEn['resetPassword.toSignIn']);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('names the password rule the identity provider refused, and keeps the form', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockRejectedValue(
      new ApiError(
        'VALIDATION_FAILED',
        400,
        { status: 400, policy: 'invalidPasswordNotEmailMessage' },
        'c-1',
      ),
    );
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(text()).toContain(messagesEn['resetPassword.policy.notEmail']);
    expect(submitButton()).not.toBeNull();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('says an expired link has expired, and one with no token cannot be used', async () => {
    resets.inspect.mockRejectedValue(
      new ApiError('RESOURCE_NOT_FOUND', 404, { status: 404, reason: 'EXPIRED' }, 'c-2'),
    );
    await open(`token=${TOKEN}`);
    expect(text()).toContain('Срок действия ссылки истёк');

    TestBed.resetTestingModule();
    resets = new FakeResets();
    await open(null);
    expect(resets.inspect).not.toHaveBeenCalled();
    expect(text()).toContain('Эта ссылка недействительна');
  });

  /**
   * A venue behind one address can trip the per-caller limit on inspect, and a
   * till's link drops for a round trip often enough. Neither says anything
   * about the link — and telling the operator it "cannot be used" sends them
   * to ask for another, which clears the stored hash of the one they hold.
   */
  it('offers a retry when the platform cannot be reached, and keeps the token', async () => {
    resets.inspect.mockRejectedValueOnce(
      new ApiError('NETWORK_UNREACHABLE', 0, { status: 0 }, 'c-4'),
    );
    await open(`token=${TOKEN}`);

    expect(text()).not.toContain(messagesRu['resetPassword.invalid.title']);
    expect(text()).toContain(messagesRu['resetPassword.retry.title']);
    expect(text()).toContain(messagesRu['error.NETWORK_UNREACHABLE']);

    resets.inspect.mockResolvedValue(INSPECTION);
    fixture.nativeElement.querySelector('button[type="button"]').click();
    await settle();

    expect(resets.inspect).toHaveBeenCalledTimes(2);
    expect(resets.inspect).toHaveBeenLastCalledWith(TOKEN);
    expect(submitButton()).not.toBeNull();
  });

  it('names a refused rate limit rather than calling a live link dead', async () => {
    resets.inspect.mockRejectedValue(
      new ApiError('RATE_LIMIT_EXCEEDED', 429, { status: 429, retryAfterSeconds: 30 }, 'c-5'),
    );
    await open(`token=${TOKEN}`);

    expect(text()).toContain(messagesRu['error.RATE_LIMIT_EXCEEDED']);
    expect(text()).not.toContain(messagesRu['resetPassword.invalid.title']);
  });

  it('falls back to the invalid state when a link is spent between opening and submitting', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockRejectedValue(
      new ApiError('RESOURCE_NOT_FOUND', 404, { status: 404, reason: 'INVALID' }, 'c-3'),
    );
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(text()).toContain(messagesEn['resetPassword.invalid.title']);
  });
});
