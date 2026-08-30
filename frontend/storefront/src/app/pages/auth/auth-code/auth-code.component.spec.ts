import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthCodeComponent } from './auth-code.component';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpRateLimitedError,
} from '../../../core/session/customer-otp';
import { TranslateService } from '../../../services/translate.service';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { TelegramWebappService } from '../../../services/telegram-webapp.service';

class FakeCustomerOtp {
  requestCode = vi.fn();
  submitCode = vi.fn();
  signIn = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, unknown>): string {
    return params ? `${key}:${JSON.stringify(params)}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeDeliverySelectionService {
  rememberSignInPhone = vi.fn();
}

class FakeTelegramWebappService {
  isTelegram = false;
  readTextFromClipboard = vi.fn();
}

interface HistoryState {
  phone?: string;
  challengeId?: string;
  codeLength?: number;
  attemptsAllowed?: number;
  expiresAt?: string;
}

function setUp(state: HistoryState | null) {
  window.history.replaceState(state, '');

  const otp = new FakeCustomerOtp();
  const delivery = new FakeDeliverySelectionService();
  TestBed.configureTestingModule({
    imports: [AuthCodeComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerOtp, useValue: otp },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: TelegramWebappService, useClass: FakeTelegramWebappService },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AuthCodeComponent);
  return { fixture, comp: fixture.componentInstance, otp, delivery, navigateSpy };
}

const VALID_STATE: HistoryState = {
  phone: '+998901234567',
  challengeId: 'chal-1',
  codeLength: 6,
  attemptsAllowed: 3,
  expiresAt: '2026-01-01T00:05:00Z',
};

describe('AuthCodeComponent.ngOnInit (missing challenge redirects)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('redirects to /auth/login when there is no navigation state at all', () => {
    const { fixture, navigateSpy } = setUp(null);

    fixture.detectChanges();

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
  });

  it('redirects when the challengeId is missing, even with a phone present', () => {
    const { fixture, navigateSpy } = setUp({ phone: '+998901234567' });

    fixture.detectChanges();

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
  });

  it('redirects when the phone is missing, even with a challengeId present', () => {
    const { fixture, navigateSpy } = setUp({ challengeId: 'chal-1' });

    fixture.detectChanges();

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
  });

  it('does not redirect, and starts the countdown, when both are present', () => {
    const { fixture, comp, navigateSpy } = setUp(VALID_STATE);

    fixture.detectChanges();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(comp.phone).toBe('+998901234567');
    expect(comp.challengeId).toBe('chal-1');
    expect(comp.countdown()).toBe(44);
  });
});

describe('AuthCodeComponent.resend (supersedes challengeId)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('does nothing while the countdown has not reached zero', async () => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    expect(comp.countdown()).toBeGreaterThan(0);

    await comp.resend();

    expect(otp.requestCode).not.toHaveBeenCalled();
  });

  it('once the countdown reaches zero, requests a fresh challenge and replaces the old id', async () => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    vi.advanceTimersByTime(44_000);
    expect(comp.countdown()).toBeLessThanOrEqual(0);
    comp.code.set('123456');
    otp.requestCode.mockResolvedValue({
      challengeId: 'chal-2',
      expiresAt: '2026-01-01T00:10:00Z',
      attemptsAllowed: 3,
      codeLength: 6,
    });

    await comp.resend();

    expect(otp.requestCode).toHaveBeenCalledWith('+998901234567');
    // The new challenge supersedes the old one -- the id is replaced, and any
    // code the customer had typed against the superseded challenge is
    // cleared, because it can never be answered again.
    expect(comp.challengeId).toBe('chal-2');
    expect(comp.code()).toBe('');
    expect(comp.countdown()).toBe(44);
  });

  it('a code entered before resend cannot go on to be submitted against the new challenge without re-entry', async () => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    vi.advanceTimersByTime(44_000);
    const firstChallengeId = comp.challengeId;
    comp.code.set('999999');
    otp.requestCode.mockResolvedValue({
      challengeId: 'chal-2',
      expiresAt: '',
      attemptsAllowed: 3,
      codeLength: 6,
    });

    await comp.resend();

    expect(comp.challengeId).not.toBe(firstChallengeId);
    expect(comp.code()).not.toBe('999999');
  });
});

describe('AuthCodeComponent.submit', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('does nothing until 6 digits have been entered', async () => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    comp.code.set('123');

    await comp.submit();

    expect(otp.submitCode).not.toHaveBeenCalled();
  });

  it('on success: verifies the code, exchanges the grant for a session, remembers the phone, then navigates to /locations -- in that order', async () => {
    const { fixture, comp, otp, delivery, navigateSpy } = setUp(VALID_STATE);
    fixture.detectChanges();
    comp.code.set('123456');
    otp.submitCode.mockResolvedValue({ grant: 'grant-xyz', expiresAt: '2026-01-01T00:02:00Z' });
    otp.signIn.mockResolvedValue({ created: true, accountId: 'acc-1' });

    await comp.submit();

    expect(otp.submitCode).toHaveBeenCalledWith({ challengeId: 'chal-1', code: '123456' });
    expect(otp.signIn).toHaveBeenCalledWith('grant-xyz');
    expect(delivery.rememberSignInPhone).toHaveBeenCalledWith('+998901234567');
    expect(navigateSpy).toHaveBeenCalledWith(['/locations']);

    // The session install (signIn) happens, and completes, strictly before
    // the navigation that follows it -- not merely "both were called".
    const signInOrder = otp.signIn.mock.invocationCallOrder[0];
    const navigateOrder = navigateSpy.mock.invocationCallOrder[0];
    expect(signInOrder).toBeLessThan(navigateOrder);
  });

  it.each([
    [new OtpCodeRejectedError(2), 'auth.errors.codeRejectedWithTries:{"count":2}'],
    [new OtpCodeRejectedError(null), 'auth.errors.codeRejected'],
    [new OtpChallengeOverError(), 'auth.errors.challengeOver'],
    [new OtpRateLimitedError(30), 'auth.errors.rateLimited'],
    [new CustomerSignInUnavailableError(), 'auth.errors.unavailable'],
    [new Error('anything else'), 'errors.generic'],
  ] as const)('maps a failed submit (%j) to the error key "%s", and clears the entered code', async (failure, key) => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    comp.code.set('123456');
    otp.submitCode.mockRejectedValue(failure);

    await comp.submit();

    expect(comp.error()).toBe(key);
    expect(comp.code()).toBe('');
    expect(comp.loading()).toBe(false);
  });

  it('a failure from signIn (after a valid submitCode) is also reported and clears the code', async () => {
    const { fixture, comp, otp } = setUp(VALID_STATE);
    fixture.detectChanges();
    comp.code.set('123456');
    otp.submitCode.mockResolvedValue({ grant: 'grant-1', expiresAt: '' });
    otp.signIn.mockRejectedValue(new OtpChallengeOverError());

    await comp.submit();

    expect(comp.error()).toBe('auth.errors.challengeOver');
    expect(comp.code()).toBe('');
  });
});
