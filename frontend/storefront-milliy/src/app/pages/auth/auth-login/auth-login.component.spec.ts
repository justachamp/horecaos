import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { AuthLoginComponent } from './auth-login.component';
import { APP_CONFIG, type BrandConfig } from '../../../core/config/app-config';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from '../../../core/session/customer-otp';
import { TranslateService } from '../../../services/translate.service';

const TEST_BRAND: BrandConfig = {
  displayName: 'Test Brand',
  theme: { accent: '#000000', accentDeep: '#000000' },
};

class FakeCustomerOtp {
  requestCode = vi.fn();
}

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

function setUp(brand: BrandConfig = TEST_BRAND) {
  const otp = new FakeCustomerOtp();
  TestBed.configureTestingModule({
    imports: [AuthLoginComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerOtp, useValue: otp },
      { provide: TranslateService, useClass: FakeTranslateService },
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/api/v1',
          tenantId: '10000000-0000-0000-0000-000000000001',
          brandId: '10000000-0000-0000-0000-000000000002',
          channel: 'STOREFRONT',
          yandexMapsApiKey: '',
          brand,
        },
      },
    ],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AuthLoginComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, otp, navigate };
}

function phoneInput(host: HTMLElement): HTMLInputElement {
  return host.querySelector<HTMLInputElement>('[data-testid="auth-phone"]')!;
}

function continueButton(host: HTMLElement): HTMLButtonElement {
  return host.querySelector<HTMLButtonElement>('[data-testid="auth-continue"]')!;
}

function type(fixture: ReturnType<typeof setUp>['fixture'], value: string): void {
  const input = phoneInput(fixture.nativeElement as HTMLElement);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

async function settle(fixture: ReturnType<typeof setUp>['fixture']): Promise<void> {
  await fixture.whenStable();
  fixture.detectChanges();
}

const CHALLENGE = {
  challengeId: 'chal-1',
  expiresAt: '2026-09-29T10:00:00Z',
  attemptsAllowed: 3,
  codeLength: 6,
};

describe('AuthLoginComponent -- the sign-in a guest at a table is sent to', () => {
  it("shows the deployment's own name when it has no mark", () => {
    const { host } = setUp();

    expect(host.textContent).toContain('Test Brand');
    expect(host.querySelector('img')).toBeNull();
  });

  it("shows the deployment's mark when it has one", () => {
    const { host } = setUp({ ...TEST_BRAND, logoUrl: 'https://cdn.example/logo.png' });

    expect(host.querySelector('img')?.getAttribute('src')).toBe('https://cdn.example/logo.png');
  });

  it('will not continue until a full nine-digit Uzbek number is typed', () => {
    const { fixture, host } = setUp();
    expect(continueButton(host).disabled).toBe(true);

    type(fixture, '90123456');
    expect(continueButton(host).disabled).toBe(true);

    type(fixture, '901234567');
    expect(continueButton(host).disabled).toBe(false);
  });

  it.each([
    ['9', '9'],
    ['90', '90'],
    ['901', '90 1'],
    ['90123', '90 123'],
    ['901234', '90 123 4'],
    ['9012345', '90 123 45'],
    ['90123456', '90 123 45 6'],
    ['901234567', '90 123 45 67'],
    ['+998 90 123 45 67', '90 123 45 67'],
    ['998901234567', '90 123 45 67'],
    ['90abc123', '90 123'],
    ['9012345678901', '90 123 45 67'],
  ])('shows %s as %s', (typed, shown) => {
    const { fixture, host } = setUp();

    type(fixture, typed);

    expect(phoneInput(host).value).toBe(shown);
  });

  it('asks for a code for the number in its one canonical form, then goes to the code screen carrying only the challenge', async () => {
    const { fixture, host, otp, navigate } = setUp();
    otp.requestCode.mockResolvedValue(CHALLENGE);
    type(fixture, '90 123 45 67');

    continueButton(host).click();
    await settle(fixture);

    expect(otp.requestCode).toHaveBeenCalledTimes(1);
    expect(otp.requestCode).toHaveBeenCalledWith('+998901234567');
    expect(navigate).toHaveBeenCalledWith(['/auth/code'], {
      state: {
        phone: '+998901234567',
        challengeId: 'chal-1',
        codeLength: 6,
        attemptsAllowed: 3,
        expiresAt: '2026-09-29T10:00:00Z',
      },
    });
  });

  it('never puts the phone number in the URL: no query string, no fragment', async () => {
    const { fixture, host, otp, navigate } = setUp();
    otp.requestCode.mockResolvedValue(CHALLENGE);
    type(fixture, '901234567');

    continueButton(host).click();
    await settle(fixture);

    const [commands, extras] = navigate.mock.calls[0];
    expect(commands).toEqual(['/auth/code']);
    expect(extras).not.toHaveProperty('queryParams');
    expect(extras).not.toHaveProperty('fragment');
    expect(JSON.stringify(commands)).not.toContain('901234567');
  });

  it('sends one request however often the button is pressed while it is in flight', async () => {
    const { fixture, host, otp } = setUp();
    let resolve!: (value: typeof CHALLENGE) => void;
    otp.requestCode.mockReturnValue(new Promise((r) => (resolve = r)));
    type(fixture, '901234567');

    continueButton(host).click();
    continueButton(host).click();
    fixture.detectChanges();
    expect(continueButton(host).disabled).toBe(true);
    resolve(CHALLENGE);
    await settle(fixture);

    expect(otp.requestCode).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['a number no code can reach', new OtpNumberRejectedError(), 'auth.errors.numberRejected'],
    ['a rate limit', new OtpRateLimitedError(30), 'auth.errors.rateLimited'],
    [
      'a transient delivery failure',
      new OtpUndeliverableError('GATEWAY_DOWN'),
      'auth.errors.undeliverable',
    ],
    [
      'a number that opted out',
      new OtpUndeliverableError('SMS_RECEIVER_BLACKLISTED'),
      'auth.errors.undeliverablePermanent',
    ],
    ['sign-in being unavailable', new CustomerSignInUnavailableError(), 'auth.errors.unavailable'],
    ['anything else', new Error('boom'), 'errors.generic'],
  ])('says what happened after %s, and stays on the screen', async (_label, failure, key) => {
    const { fixture, host, otp, navigate } = setUp();
    otp.requestCode.mockRejectedValue(failure);
    type(fixture, '901234567');

    continueButton(host).click();
    await settle(fixture);

    expect(host.querySelector('[data-testid="auth-error"]')?.textContent).toContain(key);
    expect(navigate).not.toHaveBeenCalled();
    expect(continueButton(host).disabled).toBe(false);
  });

  it('clears an error as soon as the number is edited', async () => {
    const { fixture, host, otp } = setUp();
    otp.requestCode.mockRejectedValue(new OtpNumberRejectedError());
    type(fixture, '901234567');
    continueButton(host).click();
    await settle(fixture);
    expect(host.querySelector('[data-testid="auth-error"]')).not.toBeNull();

    type(fixture, '90123456');

    expect(host.querySelector('[data-testid="auth-error"]')).toBeNull();
  });
});
