import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { MfaApi } from '../../core/auth/mfa-api';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { I18n } from '../../core/i18n/i18n';
import { EnrolSecondFactorPage } from './enrol-second-factor-page';

const SESSION = {
  accessToken: 'a',
  refreshToken: 'r',
  accessTokenExpiresAt: '2026-10-07T10:05:00Z',
  tokenType: 'Bearer',
};

describe('EnrolSecondFactorPage (ADR 0148)', () => {
  let fixture: ComponentFixture<EnrolSecondFactorPage>;
  let auth: { adoptSession: ReturnType<typeof vi.fn> };
  let api: { begin: ReturnType<typeof vi.fn>; confirm: ReturnType<typeof vi.fn> };
  let router: Router;

  async function setUp(ticket: string | null): Promise<void> {
    sessionStorage.clear();
    auth = { adoptSession: vi.fn() };
    api = { begin: vi.fn(), confirm: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EnrolSecondFactorPage],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: auth },
        { provide: MfaApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    if (ticket !== null) {
      TestBed.inject(MfaTicket).hold(ticket);
    }
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(EnrolSecondFactorPage);
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('says there is nothing to do without a ticket, and offers the way back, instead of a form that could only fail', async () => {
    await setUp(null);

    expect(fixture.nativeElement.querySelector('[data-testid="mfa-no-ticket"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('q-mfa-enrolment')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('opens the enrolment form, required, for the ticket held in memory', async () => {
    await setUp('a-ticket');

    expect(fixture.nativeElement.querySelector('q-mfa-enrolment')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('must use a code');
  });

  it('adopts the session the confirmation returns, drops the ticket and goes where the person was heading', async () => {
    await setUp('a-ticket');
    sessionStorage.setItem(RETURN_TO_KEY, '/orders/018f-late-one');
    const form = fixture.nativeElement.querySelector('q-mfa-enrolment') as HTMLElement;
    api.begin.mockResolvedValue({
      sealedSecret: 's',
      otpauthUri: 'otpauth://totp/x?secret=AAAA&issuer=HorecaOS',
      secret: 'AAAA',
      expiresAt: 'x',
    });
    api.confirm.mockResolvedValue(SESSION);
    const password = form.querySelector('#mfa-password') as HTMLInputElement;
    password.value = 'correct horse';
    password.dispatchEvent(new Event('input'));
    (form.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { cancelable: true }),
    );
    await fixture.whenStable();
    fixture.detectChanges();
    const cells = Array.from(
      form.querySelectorAll<HTMLInputElement>('[data-testid="q-otp-input-cell"]'),
    );
    [...'482913'].forEach((digit, index) => {
      cells[index].value = digit;
      cells[index].dispatchEvent(new Event('input'));
    });
    await fixture.whenStable();

    expect(api.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ enrolmentTicket: 'a-ticket' }),
    );
    expect(auth.adoptSession).toHaveBeenCalledWith(SESSION);
    expect(TestBed.inject(MfaTicket).ticket()).toBeNull();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/orders/018f-late-one');
  });
});
