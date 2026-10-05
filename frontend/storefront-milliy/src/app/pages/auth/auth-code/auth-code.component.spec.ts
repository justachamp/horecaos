import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthCodeComponent } from './auth-code.component';
import { ReturnDestination } from '../../../core/auth/return-destination';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpRateLimitedError,
} from '../../../core/session/customer-otp';
import { DeliverySelectionService } from '../../../services/delivery-selection.service';
import { TranslateService } from '../../../services/translate.service';

class FakeCustomerOtp {
  requestCode = vi.fn();
  submitCode = vi.fn();
  signIn = vi.fn();
}

class FakeDeliverySelectionService {
  rememberSignInPhone = vi.fn();
}

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}:${JSON.stringify(params)}` : key;
  current = (): Record<string, unknown> => ({});
}

interface HistoryState {
  phone?: string;
  challengeId?: string;
  codeLength?: number;
  attemptsAllowed?: number;
  expiresAt?: string;
}

/** A challenge with `seconds` left, measured from whatever "now" is when this is called. */
function validState(seconds = 44, overrides: Partial<HistoryState> = {}): HistoryState {
  return {
    phone: '+998901234567',
    challengeId: 'chal-1',
    codeLength: 6,
    attemptsAllowed: 3,
    expiresAt: new Date(Date.now() + seconds * 1000).toISOString(),
    ...overrides,
  };
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
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AuthCodeComponent);
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, otp, delivery, navigate };
}

type Harness = ReturnType<typeof setUp>;

function box(host: HTMLElement, index: number): HTMLInputElement {
  return host.querySelector<HTMLInputElement>(`[data-code-input="${index}"]`)!;
}

/** Types a whole code into the first box, the way an SMS autofill does. */
function enterCode(h: Harness, code: string): void {
  const first = box(h.host, 0);
  first.value = code;
  first.dispatchEvent(new Event('input'));
  h.fixture.detectChanges();
}

function submitButton(host: HTMLElement): HTMLButtonElement {
  return host.querySelector<HTMLButtonElement>('[data-testid="auth-submit"]')!;
}

function resendButton(host: HTMLElement): HTMLButtonElement | null {
  return host.querySelector<HTMLButtonElement>('[data-testid="auth-resend"]');
}

function errorText(host: HTMLElement): string | null {
  return host.querySelector('[data-testid="auth-error"]')?.textContent?.trim() ?? null;
}

async function settle(h: Harness): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  h.fixture.detectChanges();
}

const GRANT = { grant: 'grant-xyz', expiresAt: '2026-09-29T10:02:00Z' };

describe('AuthCodeComponent -- a screen with nothing to answer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
  });
  afterEach(() => vi.useRealTimers());

  it.each([
    ['no navigation state at all', null],
    ['a phone but no challenge', { phone: '+998901234567' }],
    ['a challenge but no phone', { challengeId: 'chal-1' }],
  ])('goes back to /auth/login for %s', (_label, state) => {
    const h = setUp(state);

    h.fixture.detectChanges();

    expect(h.navigate).toHaveBeenCalledWith(['/auth/login']);
  });

  it('stays, and shows the number masked, when it has a challenge to answer', () => {
    const h = setUp(validState());

    h.fixture.detectChanges();

    expect(h.navigate).not.toHaveBeenCalled();
    expect(h.host.textContent).toContain('+998 90 *** ** 67');
    expect(h.host.textContent).not.toContain('123 45');
  });
});

describe('AuthCodeComponent -- the countdown', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('counts down from the time the challenge really has left, not from a guess', () => {
    const h = setUp(validState(30));
    h.fixture.detectChanges();

    expect(h.host.querySelector('[data-testid="auth-countdown"]')?.textContent).toContain('00:30');

    vi.advanceTimersByTime(5_000);
    h.fixture.detectChanges();
    expect(h.host.querySelector('[data-testid="auth-countdown"]')?.textContent).toContain('00:25');
  });

  it('falls back to forty-four seconds when the challenge names no usable deadline', () => {
    const h = setUp(validState(0, { expiresAt: 'not-a-date' }));
    h.fixture.detectChanges();

    expect(h.host.querySelector('[data-testid="auth-countdown"]')?.textContent).toContain('00:44');
  });

  it("offers a new code at once when the challenge's deadline has already passed", () => {
    const h = setUp(validState(-5));
    h.fixture.detectChanges();

    expect(resendButton(h.host)).not.toBeNull();
    expect(h.host.querySelector('[data-testid="auth-countdown"]')).toBeNull();
  });

  it('stops ticking when the screen is left', () => {
    const h = setUp(validState(30));
    h.fixture.detectChanges();

    h.fixture.destroy();

    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('AuthCodeComponent -- asking again', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('offers no resend while the first code may still arrive', () => {
    const h = setUp(validState(30));
    h.fixture.detectChanges();

    expect(resendButton(h.host)).toBeNull();
  });

  it('a resend takes a fresh challenge, clears the typed code, and answers the new one', async () => {
    const h = setUp(validState(10));
    h.fixture.detectChanges();
    enterCode(h, '123456');
    vi.advanceTimersByTime(10_000);
    h.fixture.detectChanges();
    h.otp.requestCode.mockResolvedValue({
      challengeId: 'chal-2',
      expiresAt: new Date(Date.now() + 44_000).toISOString(),
      attemptsAllowed: 3,
      codeLength: 6,
    });

    resendButton(h.host)!.click();
    await settle(h);

    expect(h.otp.requestCode).toHaveBeenCalledWith('+998901234567');
    expect(box(h.host, 0).value).toBe('');
    expect(resendButton(h.host)).toBeNull();

    h.otp.submitCode.mockResolvedValue(GRANT);
    h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });
    enterCode(h, '654321');
    submitButton(h.host).click();
    await settle(h);

    expect(h.otp.submitCode).toHaveBeenCalledWith({ challengeId: 'chal-2', code: '654321' });
  });

  it('says why when a new code cannot be sent', async () => {
    const h = setUp(validState(-1));
    h.fixture.detectChanges();
    h.otp.requestCode.mockRejectedValue(new OtpRateLimitedError(30));

    resendButton(h.host)!.click();
    await settle(h);

    expect(errorText(h.host)).toContain('auth.errors.rateLimited');
  });
});

describe('AuthCodeComponent -- answering', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
  });
  afterEach(() => vi.useRealTimers());

  it('will not submit until all six digits are in', async () => {
    const h = setUp(validState());
    h.fixture.detectChanges();

    enterCode(h, '123');

    expect(submitButton(h.host).disabled).toBe(true);
    submitButton(h.host).click();
    await settle(h);
    expect(h.otp.submitCode).not.toHaveBeenCalled();
  });

  it('spreads a typed digit into the next box and keeps digits only', () => {
    const h = setUp(validState());
    h.fixture.detectChanges();

    const second = box(h.host, 1);
    second.value = 'x7';
    second.dispatchEvent(new Event('input'));
    h.fixture.detectChanges();

    expect(box(h.host, 1).value).toBe('7');
    expect(submitButton(h.host).disabled).toBe(true);
  });

  it('a digit typed into the fourth box stays in the fourth box, and focus moves to the fifth', () => {
    const h = setUp(validState());
    h.fixture.detectChanges();

    const fourth = box(h.host, 3);
    fourth.value = '7';
    fourth.dispatchEvent(new Event('input'));
    h.fixture.detectChanges();

    expect([0, 1, 2, 3, 4, 5].map((index) => box(h.host, index).value)).toEqual([
      '',
      '',
      '',
      '7',
      '',
      '',
    ]);
    expect(document.activeElement).toBe(box(h.host, 4));
  });

  it('a whole code pasted into a box fills all six', () => {
    const h = setUp(validState());
    h.fixture.detectChanges();

    const paste = new Event('paste', { cancelable: true }) as ClipboardEvent;
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => '12 34-56' } });
    box(h.host, 2).dispatchEvent(paste);
    h.fixture.detectChanges();

    expect([0, 1, 2, 3, 4, 5].map((index) => box(h.host, index).value).join('')).toBe('123456');
    expect(paste.defaultPrevented).toBe(true);
    expect(submitButton(h.host).disabled).toBe(false);
  });

  it('backspace in an empty box steps back to the box before it', () => {
    const h = setUp(validState());
    h.fixture.detectChanges();

    box(h.host, 3).dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace' }));

    expect(document.activeElement).toBe(box(h.host, 2));
  });

  it('signs in in order -- verify the code, redeem the grant, remember the phone -- then leaves for /home', async () => {
    const h = setUp(validState());
    h.fixture.detectChanges();
    const order: string[] = [];
    h.otp.submitCode.mockImplementation(async () => {
      order.push('submitCode');
      return GRANT;
    });
    h.otp.signIn.mockImplementation(async () => {
      order.push('signIn');
      return { created: true, accountId: 'acc-1' };
    });
    h.delivery.rememberSignInPhone.mockImplementation(() => order.push('rememberPhone'));
    h.navigate.mockImplementation(async () => {
      order.push('navigate');
      return true;
    });

    enterCode(h, '123456');
    submitButton(h.host).click();
    await settle(h);

    expect(h.otp.submitCode).toHaveBeenCalledWith({ challengeId: 'chal-1', code: '123456' });
    expect(h.otp.signIn).toHaveBeenCalledWith('grant-xyz');
    expect(h.delivery.rememberSignInPhone).toHaveBeenCalledWith('+998901234567');
    expect(order).toEqual(['submitCode', 'signIn', 'rememberPhone', 'navigate']);
    expect(h.navigate).toHaveBeenCalledWith(['/home'], { replaceUrl: true });
  });

  describe('a guest who signed in from a table', () => {
    it('goes back to the table, not to the front door', async () => {
      const h = setUp(validState());
      h.fixture.detectChanges();
      TestBed.inject(ReturnDestination).remember('/dine-in/table');
      h.otp.submitCode.mockResolvedValue(GRANT);
      h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });

      enterCode(h, '123456');
      submitButton(h.host).click();
      await settle(h);

      expect(h.navigate).toHaveBeenCalledWith(['/dine-in/table'], { replaceUrl: true });
    });

    it('spends the remembered destination: the next sign-in on this tab goes to /home', async () => {
      const h = setUp(validState());
      h.fixture.detectChanges();
      TestBed.inject(ReturnDestination).remember('/dine-in/table');
      h.otp.submitCode.mockResolvedValue(GRANT);
      h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });

      enterCode(h, '123456');
      submitButton(h.host).click();
      await settle(h);

      expect(TestBed.inject(ReturnDestination).consume()).toBeNull();
    });

    it('does not spend the destination while the code is still wrong, so a second try still lands at the table', async () => {
      const h = setUp(validState());
      h.fixture.detectChanges();
      TestBed.inject(ReturnDestination).remember('/dine-in/table');
      h.otp.submitCode.mockRejectedValueOnce(new OtpCodeRejectedError(2));

      enterCode(h, '000000');
      submitButton(h.host).click();
      await settle(h);
      expect(h.navigate).not.toHaveBeenCalled();

      h.otp.submitCode.mockResolvedValue(GRANT);
      h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });
      enterCode(h, '123456');
      submitButton(h.host).click();
      await settle(h);

      expect(h.navigate).toHaveBeenCalledWith(['/dine-in/table'], { replaceUrl: true });
    });

    it('ignores a destination that is not the table screen -- the sign-in is not an open redirect', async () => {
      const h = setUp(validState());
      h.fixture.detectChanges();
      sessionStorage.setItem(
        'horecaos_sign_in_return_to',
        JSON.stringify({ path: 'https://evil.example', at: Date.now() }),
      );
      h.otp.submitCode.mockResolvedValue(GRANT);
      h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });

      enterCode(h, '123456');
      submitButton(h.host).click();
      await settle(h);

      expect(h.navigate).toHaveBeenCalledWith(['/home'], { replaceUrl: true });
    });
  });

  it.each([
    [
      'a wrong code with tries left',
      new OtpCodeRejectedError(2),
      'auth.errors.codeRejectedWithTries:{"count":2}',
    ],
    ['a wrong code with no count', new OtpCodeRejectedError(null), 'auth.errors.codeRejected'],
    ['an ended challenge', new OtpChallengeOverError(), 'auth.errors.challengeOver'],
    ['a rate limit', new OtpRateLimitedError(30), 'auth.errors.rateLimited'],
    ['sign-in being unavailable', new CustomerSignInUnavailableError(), 'auth.errors.unavailable'],
    ['anything else', new Error('boom'), 'errors.generic'],
  ])(
    'says what happened after %s, clears the boxes, and stays',
    async (_label, failure, message) => {
      const h = setUp(validState());
      h.fixture.detectChanges();
      h.otp.submitCode.mockRejectedValue(failure);

      enterCode(h, '123456');
      submitButton(h.host).click();
      await settle(h);

      expect(errorText(h.host)).toContain(message);
      expect(box(h.host, 0).value).toBe('');
      expect(h.navigate).not.toHaveBeenCalled();
      expect(h.delivery.rememberSignInPhone).not.toHaveBeenCalled();
    },
  );

  it('does not remember the phone or leave when the grant cannot be redeemed', async () => {
    const h = setUp(validState());
    h.fixture.detectChanges();
    h.otp.submitCode.mockResolvedValue(GRANT);
    h.otp.signIn.mockRejectedValue(new CustomerSignInUnavailableError());

    enterCode(h, '123456');
    submitButton(h.host).click();
    await settle(h);

    expect(h.delivery.rememberSignInPhone).not.toHaveBeenCalled();
    expect(h.navigate).not.toHaveBeenCalled();
    expect(errorText(h.host)).toContain('auth.errors.unavailable');
  });

  it('sends one attempt however often the button is pressed while it is in flight', async () => {
    const h = setUp(validState());
    h.fixture.detectChanges();
    let resolve!: (value: typeof GRANT) => void;
    h.otp.submitCode.mockReturnValue(new Promise((r) => (resolve = r)));
    h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });

    enterCode(h, '123456');
    submitButton(h.host).click();
    submitButton(h.host).click();
    resolve(GRANT);
    await settle(h);

    expect(h.otp.submitCode).toHaveBeenCalledTimes(1);
  });

  it('never puts the code, the grant or the phone in a URL', async () => {
    const h = setUp(validState());
    h.fixture.detectChanges();
    h.otp.submitCode.mockResolvedValue(GRANT);
    h.otp.signIn.mockResolvedValue({ created: false, accountId: 'acc-1' });

    enterCode(h, '123456');
    submitButton(h.host).click();
    await settle(h);

    const [commands, extras] = h.navigate.mock.calls[0];
    const everything = JSON.stringify([commands, extras]);
    expect(everything).not.toContain('123456');
    expect(everything).not.toContain('grant-xyz');
    expect(everything).not.toContain('901234567');
  });
});
