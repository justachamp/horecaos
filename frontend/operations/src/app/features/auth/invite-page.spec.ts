import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { I18n } from '../../core/i18n/i18n';
import { messagesEn } from '../../core/i18n/messages.en';
import { InvitationAccepted, InvitationInspection, InvitationsApi } from './invitations-api';
import { InvitePage } from './invite-page';

const TOKEN = 'k8Qm2v1Xo9-pL3sW7yZ0aB4cD6eF8gH1iJ2kL3mN4oP';

const INVITATION: InvitationInspection = {
  tenantName: 'Qoida',
  emailMasked: 'd***a@example.uz',
  expiresAt: '2026-09-14T09:00:00Z',
  locale: 'en',
};

class FakeInvitations {
  readonly inspect = vi.fn<(token: string) => Promise<InvitationInspection>>();
  readonly accept =
    vi.fn<
      (token: string, first: string, last: string, password: string) => Promise<InvitationAccepted>
    >();
}

class FakeAuth {
  readonly signIn = vi.fn<(username: string, password: string) => Promise<void>>();
}

describe('InvitePage', () => {
  let fixture: ComponentFixture<InvitePage>;
  let invitations: FakeInvitations;
  let auth: FakeAuth;
  let router: Router;

  async function open(fragment: string | null): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [InvitePage],
      providers: [
        provideRouter([]),
        { provide: InvitationsApi, useValue: invitations },
        { provide: Auth, useValue: auth },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(InvitePage);
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
    type('firstName', ' Dilnoza ');
    type('lastName', 'Karimova');
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
    invitations = new FakeInvitations();
    auth = new FakeAuth();
  });

  it('reads the token from the fragment, shows whose invitation it is, in its language', async () => {
    invitations.inspect.mockResolvedValue(INVITATION);
    await open(`token=${TOKEN}`);

    expect(invitations.inspect).toHaveBeenCalledWith(TOKEN);
    expect(TestBed.inject(I18n).locale()).toBe('en');
    expect(text()).toContain('Qoida uses HorecaOS');
    expect(text()).toContain('d***a@example.uz');
  });

  it('will not submit a short password or two that differ', async () => {
    invitations.inspect.mockResolvedValue(INVITATION);
    await open(`token=${TOKEN}`);

    fill('short');
    expect(submitButton().disabled).toBe(true);
    fill('a-long-enough-passphrase', 'a-long-enough-passphrasX');
    expect(submitButton().disabled).toBe(true);
    expect(text()).toContain(messagesEn['invite.mismatch']);
    fill();
    expect(submitButton().disabled).toBe(false);
  });

  it('sets up the account, then signs the owner in with what they chose', async () => {
    invitations.inspect.mockResolvedValue(INVITATION);
    invitations.accept.mockResolvedValue({ signInName: 'dilnoza.karimova@example.uz' });
    auth.signIn.mockResolvedValue(undefined);
    await open(`token=${TOKEN}`);

    fill();
    await submit();

    expect(invitations.accept).toHaveBeenCalledWith(
      TOKEN,
      'Dilnoza',
      'Karimova',
      'a-long-enough-passphrase',
    );
    expect(auth.signIn).toHaveBeenCalledWith(
      'dilnoza.karimova@example.uz',
      'a-long-enough-passphrase',
    );
    expect(router.navigateByUrl).toHaveBeenCalledWith('/today');
  });

  it('names the password rule the identity provider refused', async () => {
    invitations.inspect.mockResolvedValue(INVITATION);
    invitations.accept.mockRejectedValue(
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

    expect(text()).toContain(messagesEn['invite.policy.notEmail']);
    expect(auth.signIn).not.toHaveBeenCalled();
  });

  it('says an expired link has expired, and an unusable one cannot be used', async () => {
    invitations.inspect.mockRejectedValue(
      new ApiError('RESOURCE_NOT_FOUND', 404, { status: 404, reason: 'EXPIRED' }, 'c-2'),
    );
    await open(`token=${TOKEN}`);
    expect(text()).toContain('Срок действия ссылки истёк');

    TestBed.resetTestingModule();
    invitations = new FakeInvitations();
    await open(null);
    expect(invitations.inspect).not.toHaveBeenCalled();
    expect(text()).toContain('Эта ссылка недействительна');
  });
});
