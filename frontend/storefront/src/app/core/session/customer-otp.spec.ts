import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from './customer-otp';
import { APP_CONFIG, type AppConfig } from '../config/app-config';
import { Session } from '../auth/session';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
};

const CHALLENGES_URL =
  '/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/identity/verification-challenges';
const ATTEMPTS_URL = `${CHALLENGES_URL}/c-1/attempts`;
const SESSION_URL =
  '/api/v1/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/10000000-0000-0000-0000-000000000002/identity/sessions';

function setUp(): { otp: CustomerOtp; httpMock: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClientTesting(), { provide: APP_CONFIG, useValue: CONFIG }],
  });
  return { otp: TestBed.inject(CustomerOtp), httpMock: TestBed.inject(HttpTestingController) };
}

function problem(status: number, body: Record<string, unknown>) {
  return { status, statusText: 'x', error: { status, ...body } };
}

describe('CustomerOtp.requestCode error taxonomy', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('404 -> CustomerSignInUnavailableError (no such endpoint)', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    httpMock.expectOne(CHALLENGES_URL).flush({}, problem(404, {}));
    await expect(promise).rejects.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('RATE_LIMIT_EXCEEDED -> OtpRateLimitedError, carrying Retry-After', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    const req = httpMock.expectOne(CHALLENGES_URL);
    req.flush(
      { status: 429, code: 'RATE_LIMIT_EXCEEDED' },
      { status: 429, statusText: 'x', headers: { 'Retry-After': '20' } },
    );
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(OtpRateLimitedError);
    expect((failure as OtpRateLimitedError).retryAfterSeconds).toBe(20);
  });

  it('VALIDATION_FAILED / INVALID_REQUEST -> OtpNumberRejectedError', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    httpMock.expectOne(CHALLENGES_URL).flush({}, problem(400, { code: 'VALIDATION_FAILED' }));
    await expect(promise).rejects.toBeInstanceOf(OtpNumberRejectedError);
  });

  it('500+ -> OtpUndeliverableError, carrying the transport reason', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    httpMock
      .expectOne(CHALLENGES_URL)
      .flush({}, problem(500, { code: 'INTERNAL_ERROR', reason: 'SMS_RECEIVER_BLACKLISTED' }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(OtpUndeliverableError);
    expect((failure as OtpUndeliverableError).reason).toBe('SMS_RECEIVER_BLACKLISTED');
    expect((failure as OtpUndeliverableError).permanent).toBe(true);
  });

  it('500+ with an unrelated reason is not permanent', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    httpMock.expectOne(CHALLENGES_URL).flush({}, problem(500, { code: 'INTERNAL_ERROR' }));
    const failure = await promise.catch((e: unknown) => e);
    expect((failure as OtpUndeliverableError).permanent).toBe(false);
  });

  it('an unrelated failure (e.g. a 409) is passed through untranslated', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.requestCode('+998901234567');
    httpMock.expectOne(CHALLENGES_URL).flush({}, problem(409, { code: 'RESOURCE_CONFLICT' }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).not.toBeInstanceOf(OtpNumberRejectedError);
    expect(failure).not.toBeInstanceOf(OtpUndeliverableError);
    expect((failure as { code?: string }).code).toBe('RESOURCE_CONFLICT');
  });
});

describe('CustomerOtp.submitCode error taxonomy', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('404 -> CustomerSignInUnavailableError', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '123456' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(404, {}));
    await expect(promise).rejects.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('RATE_LIMIT_EXCEEDED -> OtpRateLimitedError', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '123456' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(429, { code: 'RATE_LIMIT_EXCEEDED' }));
    await expect(promise).rejects.toBeInstanceOf(OtpRateLimitedError);
  });

  it('UNPROCESSABLE_STATE -> OtpChallengeOverError (expired, superseded, exhausted, or spent -- all one answer)', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '123456' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(422, { code: 'UNPROCESSABLE_STATE' }));
    await expect(promise).rejects.toBeInstanceOf(OtpChallengeOverError);
  });

  it('401 / UNAUTHENTICATED -> OtpCodeRejectedError, carrying attemptsRemaining', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '000000' });
    httpMock
      .expectOne(ATTEMPTS_URL)
      .flush({}, problem(401, { code: 'UNAUTHENTICATED', attemptsRemaining: 2 }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(OtpCodeRejectedError);
    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBe(2);
  });

  it('an absent attemptsRemaining reads as null, not zero', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '000000' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(401, { code: 'UNAUTHENTICATED' }));
    const failure = await promise.catch((e: unknown) => e);
    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBeNull();
  });

  it('VALIDATION_FAILED (wrong code length) -> OtpCodeRejectedError with null attemptsRemaining (costs no attempt)', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '1' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(400, { code: 'VALIDATION_FAILED' }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(OtpCodeRejectedError);
    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBeNull();
  });

  it('an unrelated failure is passed through untranslated', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.submitCode({ challengeId: 'c-1', code: '123456' });
    httpMock.expectOne(ATTEMPTS_URL).flush({}, problem(500, { code: 'INTERNAL_ERROR' }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).not.toBeInstanceOf(OtpCodeRejectedError);
    expect(failure).not.toBeInstanceOf(OtpChallengeOverError);
  });
});

describe('CustomerOtp.signIn error taxonomy and session install', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('404 -> CustomerSignInUnavailableError', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.signIn('grant-1');
    httpMock.expectOne(SESSION_URL).flush({}, problem(404, {}));
    await expect(promise).rejects.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('401 / UNAUTHENTICATED -> OtpChallengeOverError (the grant is spent, expired, or for another brand)', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.signIn('grant-1');
    httpMock.expectOne(SESSION_URL).flush({}, problem(401, { code: 'UNAUTHENTICATED' }));
    await expect(promise).rejects.toBeInstanceOf(OtpChallengeOverError);
  });

  it('an unrelated failure is passed through untranslated', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.signIn('grant-1');
    httpMock.expectOne(SESSION_URL).flush({}, problem(500, { code: 'INTERNAL_ERROR' }));
    const failure = await promise.catch((e: unknown) => e);
    expect(failure).not.toBeInstanceOf(OtpChallengeOverError);
    expect(failure).not.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('a 200 with no token throws rather than adopting undefined', async () => {
    const { otp, httpMock } = setUp();
    const promise = otp.signIn('grant-1');
    httpMock.expectOne(SESSION_URL).flush({ accountId: 'acc-1', created: false });
    await expect(promise).rejects.toThrow(/without a session token/);
  });

  it('installs the session via Session.adopt before resolving, and reports created/accountId', async () => {
    const { otp, httpMock } = setUp();
    const session = TestBed.inject(Session);
    const expiresAt = new Date(Date.now() + 60_000).toISOString();

    const promise = otp.signIn('grant-1');
    httpMock
      .expectOne(SESSION_URL)
      .flush({ token: 'qcs1.abc', expiresAt, accountId: 'acc-9', created: true });

    const signedIn = await promise;

    expect(session.accessToken()).toBe('qcs1.abc');
    expect(signedIn).toEqual({ created: true, accountId: 'acc-9' });
  });
});

describe('CustomerOtp.signOut', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('clears the local session even when the platform call fails', async () => {
    const { otp, httpMock } = setUp();
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const promise = otp.signOut();
    httpMock.expectOne(`${SESSION_URL}/current`).flush({}, problem(500, { code: 'INTERNAL_ERROR' }));
    await promise;

    expect(session.accessToken()).toBeNull();
  });

  it('clears the local session on a successful platform call', async () => {
    const { otp, httpMock } = setUp();
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const promise = otp.signOut();
    httpMock.expectOne(`${SESSION_URL}/current`).flush(null, { status: 204, statusText: 'No Content' });
    await promise;

    expect(session.accessToken()).toBeNull();
  });
});
