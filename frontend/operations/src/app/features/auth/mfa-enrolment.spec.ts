import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { MfaApi } from '../../core/auth/mfa-api';
import { I18n } from '../../core/i18n/i18n';
import { MfaEnrolmentForm } from './mfa-enrolment';

const ENROLMENT = {
  sealedSecret: 'sealed-token',
  otpauthUri:
    'otpauth://totp/HorecaOS:998901234567?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=HorecaOS',
  secret: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',
  expiresAt: '2026-10-07T10:10:00Z',
};

class FakeMfaApi {
  readonly begin = vi.fn<(password: string, ticket: string | null) => Promise<typeof ENROLMENT>>();
  readonly confirm = vi.fn();
}

function problem(code: string, status: number, extra: Record<string, unknown> = {}): ApiError {
  return new ApiError(code, status, { status, code, ...extra }, null);
}

describe('MfaEnrolmentForm (ADR 0148)', () => {
  let fixture: ComponentFixture<MfaEnrolmentForm>;
  let api: FakeMfaApi;
  let completed: unknown[];

  async function setUp(ticket: string | null = null, required = false): Promise<void> {
    api = new FakeMfaApi();
    completed = [];
    await TestBed.configureTestingModule({
      imports: [MfaEnrolmentForm],
      providers: [{ provide: MfaApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MfaEnrolmentForm);
    fixture.componentRef.setInput('ticket', ticket);
    fixture.componentRef.setInput('required', required);
    fixture.componentInstance.completed.subscribe((value) => completed.push(value));
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement;

  function typePassword(value: string): void {
    const input = root().querySelector('#mfa-password') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function submitPassword(): Promise<void> {
    (root().querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { cancelable: true }),
    );
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function cells(): HTMLInputElement[] {
    return Array.from(
      root().querySelectorAll<HTMLInputElement>('[data-testid="q-otp-input-cell"]'),
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

  beforeEach(() => TestBed.resetTestingModule());

  it('asks for the password first and shows no secret until the platform has handed one over', async () => {
    await setUp();

    expect(root().querySelector('[data-testid="mfa-password-step"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="mfa-secret"]')).toBeNull();
    expect(root().querySelector('q-qr-code')).toBeNull();
    expect((root().querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  it('says the account is required to do this when it came from a refused sign-in, and only offers it otherwise', async () => {
    await setUp('a-ticket', true);
    expect(root().textContent).toContain('must use a code');

    TestBed.resetTestingModule();
    await setUp(null, false);
    expect(root().textContent).not.toContain('must use a code');
  });

  it('begins with the password and the ticket, then shows the QR code, the typed key in groups of four and the code field', async () => {
    await setUp('a-ticket', true);
    api.begin.mockResolvedValue(ENROLMENT);
    typePassword('correct horse');

    await submitPassword();

    expect(api.begin).toHaveBeenCalledWith('correct horse', 'a-ticket');
    expect(root().querySelector('q-qr-code')).not.toBeNull();
    expect(root().querySelector('[data-testid="mfa-secret"]')?.textContent?.trim()).toBe(
      'ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567',
    );
    expect(cells()).toHaveLength(6);
  });

  it('confirms by itself on the sixth digit, with the sealed secret, the code, the password and the name typed', async () => {
    await setUp('a-ticket', true);
    api.begin.mockResolvedValue(ENROLMENT);
    api.confirm.mockResolvedValue({ accessToken: 'a' });
    typePassword('correct horse');
    await submitPassword();
    const label = root().querySelector('#mfa-label') as HTMLInputElement;
    label.value = 'my phone';
    label.dispatchEvent(new Event('input'));

    typeCode('482913');
    await fixture.whenStable();

    expect(api.confirm).toHaveBeenCalledWith({
      sealedSecret: 'sealed-token',
      code: '482913',
      password: 'correct horse',
      label: 'my phone',
      enrolmentTicket: 'a-ticket',
    });
    expect(completed).toEqual([{ accessToken: 'a' }]);
  });

  it('forgets the secret and the password the moment the enrolment is confirmed', async () => {
    await setUp();
    api.begin.mockResolvedValue(ENROLMENT);
    api.confirm.mockResolvedValue(null);
    typePassword('correct horse');
    await submitPassword();
    typeCode('482913');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(completed).toEqual([null]);
    expect(root().querySelector('[data-testid="mfa-secret"]')?.textContent ?? '').toBe('');
  });

  it('says nothing was set up on a wrong first code, clears the field and allows another try with the same secret', async () => {
    await setUp();
    api.begin.mockResolvedValue(ENROLMENT);
    api.confirm.mockRejectedValueOnce(problem('MFA_CONFIRMATION_CODE_INVALID', 422));
    typePassword('correct horse');
    await submitPassword();

    typeCode('000000');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('nothing was set up');
    expect(cells().map((cell) => cell.value)).toEqual(['', '', '', '', '', '']);
    expect(root().querySelector('[data-testid="mfa-scan-step"]')).not.toBeNull();
    expect(completed).toEqual([]);
  });

  it('sends the person back to the password step, not into a dead end, when the sealed secret has expired', async () => {
    await setUp();
    api.begin.mockResolvedValue(ENROLMENT);
    api.confirm.mockRejectedValueOnce(problem('INVALID_REQUEST', 400));
    typePassword('correct horse');
    await submitPassword();

    typeCode('111111');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="mfa-password-step"]')).not.toBeNull();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain('expired');
  });

  it('names the wrong current password, stays on the password step and shows no secret', async () => {
    await setUp();
    api.begin.mockRejectedValue(problem('CURRENT_PASSWORD_INVALID', 422));
    typePassword('nope');

    await submitPassword();

    expect(root().querySelector('[role="alert"]')?.textContent).toContain(
      'not your current password',
    );
    expect(root().querySelector('[data-testid="mfa-scan-step"]')).toBeNull();
  });

  it('tells a person who already holds two devices to remove one, and a spent budget how many minutes to wait', async () => {
    await setUp();
    api.begin.mockRejectedValueOnce(problem('RESOURCE_CONFLICT', 409));
    typePassword('pw');
    await submitPassword();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain('at most two');

    api.begin.mockRejectedValueOnce(
      problem('RATE_LIMIT_EXCEEDED', 429, { retryAfterSeconds: 150 }),
    );
    typePassword('pw2');
    await submitPassword();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain('3 min');
  });
});
