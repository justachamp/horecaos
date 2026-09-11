import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { messagesEn } from '../../core/i18n/messages.en';
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

describe('ResetPasswordPage', () => {
  let fixture: ComponentFixture<ResetPasswordPage>;
  let resets: FakeResets;
  let router: Router;

  async function open(fragment: string | null): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ResetPasswordPage],
      providers: [
        provideRouter([]),
        { provide: PasswordResetsApi, useValue: resets },
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
  it('sends only the token and the password, then hands the operator to sign-in', async () => {
    resets.inspect.mockResolvedValue(INSPECTION);
    resets.accept.mockResolvedValue(undefined);
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(resets.accept).toHaveBeenCalledWith(TOKEN, 'a-long-enough-passphrase');
    expect(resets.accept.mock.calls[0]).toHaveLength(2);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
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
