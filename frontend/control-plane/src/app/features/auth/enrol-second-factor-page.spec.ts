import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../core/auth/auth.service';
import { RETURN_TO_KEY } from '../../core/auth/guards';
import { MfaApi } from '../../core/auth/mfa-api';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG } from '../../core/config/app-config';
import { EnrolSecondFactorPage } from './enrol-second-factor-page';

const SESSION = {
  accessToken: 'a',
  refreshToken: 'r',
  accessTokenExpiresAt: '2026-10-07T10:05:00Z',
  tokenType: 'Bearer',
};

describe('EnrolSecondFactorPage (control plane, ADR 0148)', () => {
  let fixture: ComponentFixture<EnrolSecondFactorPage>;
  let auth: { adoptSession: ReturnType<typeof vi.fn> };
  let session: { load: ReturnType<typeof vi.fn> };
  let api: { begin: ReturnType<typeof vi.fn>; confirm: ReturnType<typeof vi.fn> };
  let router: Router;

  async function setUp(ticket: string | null): Promise<void> {
    localStorage.clear();
    sessionStorage.clear();
    auth = { adoptSession: vi.fn() };
    session = { load: vi.fn().mockResolvedValue(undefined) };
    api = { begin: vi.fn(), confirm: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [EnrolSecondFactorPage],
      providers: [
        provideRouter([]),
        {
          provide: APP_CONFIG,
          useValue: { apiBaseUrl: 'https://api.test', displayTimeZone: 'UTC' },
        },
        { provide: AuthService, useValue: auth },
        { provide: SessionContextService, useValue: session },
        { provide: MfaApi, useValue: api },
      ],
    }).compileComponents();
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
    expect(fixture.nativeElement.querySelector('app-mfa-enrolment')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('adopts the session the confirmation returns, drops the ticket, loads the session context and goes where the person was heading', async () => {
    await setUp('a-ticket');
    sessionStorage.setItem(RETURN_TO_KEY, '/tenants/t-1/brands');
    const form = fixture.nativeElement.querySelector('app-mfa-enrolment') as HTMLElement;
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
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(api.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ enrolmentTicket: 'a-ticket' }),
    );
    expect(auth.adoptSession).toHaveBeenCalledWith(SESSION);
    expect(session.load).toHaveBeenCalledOnce();
    expect(TestBed.inject(MfaTicket).ticket()).toBeNull();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tenants/t-1/brands');
  });
});
