import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Observable } from 'rxjs';

import { AuthLoginComponent } from './auth-login.component';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from '../../../core/session/customer-otp';
import {
  TelegramSignIn,
  TelegramSignInExpiredError,
  TelegramSignInRateLimitedError,
  TelegramSignInUnavailableError,
} from '../../../core/session/telegram-signin';
import { OrdersService } from '../../../services/orders.service';
import { TranslateService } from '../../../services/translate.service';
import { LangService } from '../../../services/lang.service';

class FakeCustomerOtp {
  requestCode = vi.fn();
}

class FakeTelegramSignIn {
  mintCode = vi.fn();
  pollOnce = vi.fn();
}

/**
 * {@code OrdersService.poll}'s own shape, minus the interval and the
 * `document.hidden`/`catchError` plumbing this component does not need to
 * re-prove: subscribing runs the source once, immediately, which is enough to
 * drive `pollTelegramOnce` deterministically from a test.
 */
class FakeOrdersService {
  poll<T>(_intervalMs: number, source: () => Observable<T>): Observable<T> {
    return source();
  }
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
  const telegram = new FakeTelegramSignIn();
  TestBed.configureTestingModule({
    imports: [AuthLoginComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerOtp, useValue: otp },
      { provide: TelegramSignIn, useValue: telegram },
      { provide: OrdersService, useClass: FakeOrdersService },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AuthLoginComponent);
  return { fixture, comp: fixture.componentInstance, otp, telegram, navigateSpy };
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

describe('AuthLoginComponent Continue with Telegram', () => {
  it('mints a code and holds it for the deep-link button', async () => {
    const { comp, telegram } = setUp();
    telegram.mintCode.mockResolvedValue({ code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' });
    telegram.pollOnce.mockResolvedValue(false);

    await comp.continueWithTelegram();

    expect(telegram.mintCode).toHaveBeenCalledTimes(1);
    expect(comp.telegramCode()).toEqual({ code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' });
    expect(comp.telegramMinting()).toBe(false);
  });

  it('does nothing while a mint is already in flight', async () => {
    const { comp, telegram } = setUp();
    let resolveMint!: (v: unknown) => void;
    telegram.mintCode.mockReturnValue(new Promise((resolve) => (resolveMint = resolve)));

    const first = comp.continueWithTelegram();
    const second = comp.continueWithTelegram();
    resolveMint({ code: 'x', deepLink: 'https://t.me/bot?start=auth_x' });
    telegram.pollOnce.mockResolvedValue(false);
    await Promise.all([first, second]);

    expect(telegram.mintCode).toHaveBeenCalledTimes(1);
  });

  it('a mint failure is shown without ever starting a poll', async () => {
    const { comp, telegram } = setUp();
    telegram.mintCode.mockRejectedValue(new TelegramSignInRateLimitedError(30));

    await comp.continueWithTelegram();

    expect(comp.telegramError()).toBe('auth.errors.rateLimited');
    expect(comp.telegramCode()).toBeNull();
    expect(telegram.pollOnce).not.toHaveBeenCalled();
  });

  it('once the poll reports signed in, it navigates to /locations and stops', async () => {
    const { comp, telegram, navigateSpy } = setUp();
    telegram.mintCode.mockResolvedValue({ code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' });
    telegram.pollOnce.mockResolvedValue(true);

    await comp.continueWithTelegram();
    // FakeOrdersService.poll runs the source synchronously-but-async on subscribe.
    await Promise.resolve();
    await Promise.resolve();

    expect(navigateSpy).toHaveBeenCalledWith(['/locations']);
  });

  it('an expired code clears the pending state and shows the message', async () => {
    const { comp, telegram } = setUp();
    telegram.mintCode.mockResolvedValue({ code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' });
    telegram.pollOnce.mockRejectedValue(new TelegramSignInExpiredError());

    await comp.continueWithTelegram();
    await Promise.resolve();
    await Promise.resolve();

    expect(comp.telegramCode()).toBeNull();
    expect(comp.telegramError()).toBe('auth.errors.telegramExpired');
  });

  it('an unavailable-platform failure at poll time is reported the same as at mint time', async () => {
    const { comp, telegram } = setUp();
    telegram.mintCode.mockRejectedValue(new TelegramSignInUnavailableError());

    await comp.continueWithTelegram();

    expect(comp.telegramError()).toBe('auth.errors.telegramUnavailable');
  });

  it('cancelling discards the code and any error, returning to the plain form', async () => {
    const { comp, telegram } = setUp();
    telegram.mintCode.mockResolvedValue({ code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' });
    telegram.pollOnce.mockResolvedValue(false);
    await comp.continueWithTelegram();

    comp.cancelTelegramSignIn();

    expect(comp.telegramCode()).toBeNull();
    expect(comp.telegramError()).toBeNull();
  });
});
