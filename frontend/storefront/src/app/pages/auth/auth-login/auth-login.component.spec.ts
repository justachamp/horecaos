import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthLoginComponent } from './auth-login.component';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from '../../../core/session/customer-otp';
import { TranslateService } from '../../../services/translate.service';
import { LangService } from '../../../services/lang.service';

class FakeCustomerOtp {
  requestCode = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
  loadTranslations(): void {}
  setLang(): void {}
}

class FakeLangService {
  langId = () => 'uz';
  load(): Promise<void> {
    return Promise.resolve();
  }
  setLang(): void {}
}

function setUp() {
  const otp = new FakeCustomerOtp();
  TestBed.configureTestingModule({
    imports: [AuthLoginComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerOtp, useValue: otp },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AuthLoginComponent);
  return { fixture, comp: fixture.componentInstance, otp, navigateSpy };
}

describe('AuthLoginComponent phone formatting', () => {
  it('phoneValid is false under 9 digits and true at exactly 9', () => {
    const { comp } = setUp();

    comp.phone = '901234';
    expect(comp.phoneValid).toBe(false);

    comp.phone = '901234567';
    expect(comp.phoneValid).toBe(true);
  });

  it.each([
    ['', ''],
    ['9', '9'],
    ['90', '90'],
    ['901', '90 1'],
    ['90123', '90 123'],
    ['901234', '90 123 4'],
    ['9012345', '90 123 45'],
    ['90123456', '90 123 45 6'],
    ['901234567', '90 123 45 67'],
  ])('formattedNational for input digits "%s" is "%s"', (digits, expected) => {
    const { comp } = setUp();
    comp.phone = digits;
    expect(comp.formattedNational).toBe(expected);
  });

  it.each([
    ['', ''],
    ['901234567', '+998 90 123 45 67'],
    ['9', '+998 9'],
  ])('formattedPhone for input digits "%s" is "%s"', (digits, expected) => {
    const { comp } = setUp();
    comp.phone = digits;
    expect(comp.formattedPhone).toBe(expected);
  });

  it('onPhoneInput strips non-digits and caps at 9 digits, clearing any error', () => {
    // The country code is a static "+998" beside the input, never part of its
    // own value (see the template), so what reaches here is national digits
    // only -- possibly with extra formatting characters or overtyped digits,
    // which is what this exercises.
    const { comp } = setUp();
    comp.error.set('stale error');

    comp.onPhoneInput({ target: { value: '90 123-45-67 890' } } as unknown as Event);

    expect(comp.phone).toBe('901234567');
    expect(comp.error()).toBeNull();
  });
});

describe('AuthLoginComponent.continue', () => {
  it('does nothing when the phone is not a complete number', async () => {
    const { comp, otp } = setUp();
    comp.phone = '123';

    await comp.continue();

    expect(otp.requestCode).not.toHaveBeenCalled();
  });

  it('does nothing while a request is already in flight', async () => {
    const { comp, otp } = setUp();
    comp.phone = '901234567';
    let resolveRequest!: (v: unknown) => void;
    otp.requestCode.mockReturnValue(new Promise((resolve) => (resolveRequest = resolve)));

    const first = comp.continue();
    const second = comp.continue();
    resolveRequest({ challengeId: 'c1', expiresAt: '', attemptsAllowed: 3, codeLength: 6 });
    await Promise.all([first, second]);

    expect(otp.requestCode).toHaveBeenCalledTimes(1);
  });

  it('on success, navigates to /auth/code with the challenge in state', async () => {
    const { comp, otp, navigateSpy } = setUp();
    comp.phone = '901234567';
    otp.requestCode.mockResolvedValue({
      challengeId: 'chal-1',
      expiresAt: '2026-01-01T00:05:00Z',
      attemptsAllowed: 3,
      codeLength: 6,
    });

    await comp.continue();

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/code'], {
      state: {
        phone: '+998901234567',
        challengeId: 'chal-1',
        codeLength: 6,
        attemptsAllowed: 3,
        expiresAt: '2026-01-01T00:05:00Z',
      },
    });
    expect(comp.loading()).toBe(false);
  });

  it.each([
    [new OtpNumberRejectedError(), 'auth.errors.numberRejected'],
    [new OtpRateLimitedError(30), 'auth.errors.rateLimited'],
    [new OtpUndeliverableError('SMS_RECEIVER_BLACKLISTED'), 'auth.errors.undeliverablePermanent'],
    [new OtpUndeliverableError('SOME_TRANSIENT_REASON'), 'auth.errors.undeliverable'],
    [new CustomerSignInUnavailableError(), 'auth.errors.unavailable'],
    [new Error('anything else'), 'errors.generic'],
  ] as const)('maps %j to the error key "%s"', async (failure, key) => {
    const { comp, otp } = setUp();
    comp.phone = '901234567';
    otp.requestCode.mockRejectedValue(failure);

    await comp.continue();

    expect(comp.error()).toBe(key);
    expect(comp.loading()).toBe(false);
  });
});
