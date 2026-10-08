import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { MemberMfa, StaffMembersApi } from './staff-members-api';
import { StaffMfaPanel } from './staff-mfa-panel';

const enrolled: MemberMfa = {
  enrolled: true,
  authenticators: [{ id: 'c1', label: 'phone', createdAt: '2026-10-01T10:00:00Z' }],
  requirement: 'REQUIRED',
};
const notEnrolled: MemberMfa = { enrolled: false, authenticators: [], requirement: 'NOT_REQUIRED' };

describe('StaffMfaPanel (ADR 0148)', () => {
  let fixture: ComponentFixture<StaffMfaPanel>;
  let api: { mfa: ReturnType<typeof vi.fn>; resetMfa: ReturnType<typeof vi.fn> };

  async function setUp(options: {
    mfa?: MemberMfa | Error;
    held?: readonly string[];
    own?: boolean;
  }): Promise<void> {
    api = { mfa: vi.fn(), resetMfa: vi.fn() };
    const mfa = options.mfa ?? enrolled;
    if (mfa instanceof Error) {
      api.mfa.mockRejectedValue(mfa);
    } else {
      api.mfa.mockResolvedValue(mfa);
    }
    const held = new Set(options.held ?? ['IAM_STAFF_MFA_READ', 'IAM_STAFF_MFA_RESET']);
    await TestBed.configureTestingModule({
      imports: [StaffMfaPanel],
      providers: [
        { provide: StaffMembersApi, useValue: api },
        { provide: SessionCapabilities, useValue: { has: (c: string) => held.has(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffMfaPanel);
    fixture.componentRef.setInput('tenantId', 't1');
    fixture.componentRef.setInput('memberId', 'm1');
    fixture.componentRef.setInput('memberVersion', 4);
    fixture.componentRef.setInput('isOwnAccount', options.own ?? false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const method = (): string =>
    root().querySelector('[data-testid="staff-mfa-method"]')?.textContent?.trim() ?? '';

  beforeEach(() => TestBed.resetTestingModule());

  it('says the person signs in with a code, with how many authenticators and whether it is required of them', async () => {
    await setUp({});

    expect(method()).toContain('Login, password and code');
    expect(method()).toContain('Authenticators: 1');
    expect(root().querySelector('[data-testid="staff-mfa-requirement"]')?.textContent).toContain(
      'Required',
    );
    expect(root().querySelector('[data-testid="staff-mfa-devices"]')?.textContent).toContain(
      'phone',
    );
  });

  it('says password only for a person with no authenticator, and offers no reset for nothing to reset', async () => {
    await setUp({ mfa: notEnrolled });

    expect(method()).toContain('Login and password');
    expect(root().querySelector('[data-testid="staff-mfa-reset"]')).toBeNull();
  });

  it('renders nothing at all without iam.staff.mfa.read, and never asks the platform', async () => {
    await setUp({ held: [] });

    expect(root().querySelector('[data-testid="staff-mfa-panel"]')).toBeNull();
    expect(api.mfa).not.toHaveBeenCalled();
  });

  it('says "not known right now" when Keycloak could not answer, not a confident password-only', async () => {
    await setUp({ mfa: new ApiError('INTERNAL_ERROR', 500, null, null) });

    expect(method()).toBe('Not known right now');
    expect(root().querySelector('[data-testid="staff-mfa-reset"]')).toBeNull();
  });

  it('says there is no account yet for an invitation not accepted', async () => {
    await setUp({ mfa: new ApiError('RESOURCE_NOT_FOUND', 404, { status: 404 }, null) });

    expect(method()).toBe('No account yet');
  });

  it('offers no reset to the person themselves, nor without the reset capability', async () => {
    await setUp({ own: true });
    expect(root().querySelector('[data-testid="staff-mfa-reset"]')).toBeNull();

    TestBed.resetTestingModule();
    await setUp({ held: ['IAM_STAFF_MFA_READ'] });
    expect(root().querySelector('[data-testid="staff-mfa-reset"]')).toBeNull();
  });

  async function openAndConfirm(reason: string): Promise<void> {
    (root().querySelector('[data-testid="staff-mfa-reset"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const field = root().querySelector('#mfa-reset-reason') as HTMLTextAreaElement;
    field.value = reason;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (root().querySelector('[data-testid="staff-mfa-reset-confirm"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('resets with the reason and the card’s version, says so, and reads the state again', async () => {
    await setUp({});
    api.resetMfa.mockResolvedValue({
      authenticatorsRemoved: 1,
      sessionsEnded: true,
      personNotified: true,
    });
    api.mfa.mockResolvedValue(notEnrolled);

    await openAndConfirm('Lost phone, confirmed in person');

    expect(api.resetMfa).toHaveBeenCalledWith('t1', 'm1', 'Lost phone, confirmed in person', 4);
    expect(root().querySelector('[data-testid="staff-mfa-notice"]')?.textContent).toContain(
      'was reset',
    );
    expect(method()).toContain('Login and password');
  });

  it('will not confirm without a reason', async () => {
    await setUp({});
    (root().querySelector('[data-testid="staff-mfa-reset"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(
      (root().querySelector('[data-testid="staff-mfa-reset-confirm"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('says in words why the platform refused: a stale card, an owner, a platform account, oneself', async () => {
    const refusals: [ApiError, string][] = [
      [new ApiError('STALE_VERSION', 409, { status: 409 }, null), 'changed since you opened it'],
      [
        new ApiError(
          'INSUFFICIENT_CAPABILITY',
          403,
          { status: 403, detail: 'A tenant owner’s second factor is reset by platform support' },
          null,
        ),
        'platform support',
      ],
      [
        new ApiError(
          'UNPROCESSABLE_STATE',
          422,
          { status: 422, detail: 'This is a platform account.' },
          null,
        ),
        'control plane',
      ],
      [
        new ApiError(
          'UNPROCESSABLE_STATE',
          422,
          { status: 422, detail: 'Nobody resets their own second factor.' },
          null,
        ),
        'another administrator',
      ],
    ];
    for (const [failure, words] of refusals) {
      TestBed.resetTestingModule();
      await setUp({});
      api.resetMfa.mockRejectedValue(failure);

      await openAndConfirm('Lost phone');

      expect(root().querySelector('[role="alert"]')?.textContent, words).toContain(words);
    }
  });
});
