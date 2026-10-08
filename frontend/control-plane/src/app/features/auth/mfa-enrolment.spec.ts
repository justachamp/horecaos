import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { MfaApi } from '../../core/auth/mfa-api';
import { APP_CONFIG } from '../../core/config/app-config';
import { MfaEnrolmentForm } from './mfa-enrolment';

const ENROLMENT = {
  sealedSecret: 'sealed-token',
  otpauthUri:
    'otpauth://totp/HorecaOS:998901234567?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=HorecaOS',
  secret: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567',
  expiresAt: '2026-10-07T10:10:00Z',
};

class FakeMfaApi {
  readonly begin = vi.fn<(password: string, ticket: string) => Promise<typeof ENROLMENT>>();
  readonly confirm = vi.fn();
}

describe('MfaEnrolmentForm (control plane, ADR 0148)', () => {
  let fixture: ComponentFixture<MfaEnrolmentForm>;
  let api: FakeMfaApi;
  let completed: unknown[];

  beforeEach(async () => {
    localStorage.clear();
    api = new FakeMfaApi();
    completed = [];
    await TestBed.configureTestingModule({
      imports: [MfaEnrolmentForm],
      providers: [
        { provide: MfaApi, useValue: api },
        {
          provide: APP_CONFIG,
          useValue: { apiBaseUrl: 'https://api.test', displayTimeZone: 'UTC' },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MfaEnrolmentForm);
    fixture.componentRef.setInput('ticket', 'a-ticket');
    fixture.componentInstance.completed.subscribe((value) => completed.push(value));
    fixture.detectChanges();
  });

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

  it('asks for the password first and shows no secret until the platform has handed one over', () => {
    expect(root().querySelector('[data-testid="mfa-password-step"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="mfa-secret"]')).toBeNull();
    expect(root().querySelector('q-qr-code')).toBeNull();
  });

  it('begins with the password and the ticket, then shows the QR code, the typed key in groups of four and the code field', async () => {
    api.begin.mockResolvedValue(ENROLMENT);
    typePassword('correct horse');

    await submitPassword();

    expect(api.begin).toHaveBeenCalledWith('correct horse', 'a-ticket');
    expect(root().querySelector('[data-testid="qr-code-svg"]')).not.toBeNull();
    expect(root().querySelector('[data-testid="mfa-secret"]')?.textContent?.trim()).toBe(
      'ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567',
    );
    expect(cells()).toHaveLength(6);
  });

  it('confirms by itself on the sixth digit and emits the new session', async () => {
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

  it('says nothing was set up on a wrong first code, clears the field and keeps the scan step', async () => {
    api.begin.mockResolvedValue(ENROLMENT);
    api.confirm.mockRejectedValueOnce(
      new ApiError({ status: 422, code: 'MFA_CONFIRMATION_CODE_INVALID' }),
    );
    typePassword('correct horse');
    await submitPassword();

    typeCode('000000');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('ничего не настроено');
    expect(cells().map((cell) => cell.value)).toEqual(['', '', '', '', '', '']);
    expect(root().querySelector('[data-testid="mfa-scan-step"]')).not.toBeNull();
    expect(completed).toEqual([]);
  });

  it('sends the person back to the password step when the sealed secret has expired, and names the wrong password', async () => {
    api.begin.mockResolvedValueOnce(ENROLMENT);
    api.confirm.mockRejectedValueOnce(new ApiError({ status: 400, code: 'INVALID_REQUEST' }));
    typePassword('correct horse');
    await submitPassword();
    typeCode('111111');
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root().querySelector('[data-testid="mfa-password-step"]')).not.toBeNull();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain('устарела');

    api.begin.mockRejectedValueOnce(
      new ApiError({ status: 422, code: 'CURRENT_PASSWORD_INVALID' }),
    );
    typePassword('nope');
    await submitPassword();
    expect(root().querySelector('[role="alert"]')?.textContent).toContain('не ваш текущий пароль');
  });
});
