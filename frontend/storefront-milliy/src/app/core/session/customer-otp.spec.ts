import { TestBed } from '@angular/core/testing';

import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpChallengeOverError,
  OtpCodeRejectedError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from './customer-otp';
import { ApiClient } from '../api/api-client';
import { APP_CONFIG, type AppConfig } from '../config/app-config';
import { HorecaOSApiError, type ErrorCode } from '../api/problem-details';
import { Session } from '../auth/session';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

/**
 * `CustomerOtp`'s translation functions (`translateIssue`, `translateAttempt`,
 * `translateExchange`) branch purely on what `ApiClient.mutate` rejects with --
 * they check `instanceof HorecaOSApiError` and then read `.status`/`.code`. The
 * HTTP wiring that turns a real `HttpErrorResponse` into a `HorecaOSApiError` is
 * `problemDetailsInterceptor`'s job and is covered in `api.interceptors.spec.ts`;
 * this file stubs `ApiClient` directly so it can drive every branch of the
 * translation taxonomy without re-proving the interceptor pipeline.
 */
class FakeApiClient {
  mutate = vi.fn();
}

function apiError(
  status: number,
  code: ErrorCode,
  extra: Record<string, unknown> & { retryAfterSeconds?: number } = {},
): HorecaOSApiError {
  const { retryAfterSeconds, ...problemExtra } = extra;
  return new HorecaOSApiError({
    status,
    code,
    detail: 'x',
    problem: { status, code, ...problemExtra },
    retryAfterSeconds,
  });
}

function setUp(): { otp: CustomerOtp; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { otp: TestBed.inject(CustomerOtp), api };
}

describe('CustomerOtp.requestCode error taxonomy', () => {
  it('404 -> CustomerSignInUnavailableError (no such endpoint)', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));

    await expect(otp.requestCode('+998901234567')).rejects.toBeInstanceOf(
      CustomerSignInUnavailableError,
    );
  });

  it('RATE_LIMIT_EXCEEDED -> OtpRateLimitedError, carrying retryAfterSeconds', async () => {
    const { otp, api } = setUp();
    const failure = apiError(429, 'RATE_LIMIT_EXCEEDED', { retryAfterSeconds: 20 });
    api.mutate.mockRejectedValue(failure);

    const rejection = await otp.requestCode('+998901234567').catch((e: unknown) => e);

    expect(rejection).toBeInstanceOf(OtpRateLimitedError);
    expect((rejection as OtpRateLimitedError).retryAfterSeconds).toBe(20);
  });

  it('VALIDATION_FAILED / INVALID_REQUEST -> OtpNumberRejectedError', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValueOnce(apiError(400, 'VALIDATION_FAILED'));
    await expect(otp.requestCode('+998901234567')).rejects.toBeInstanceOf(OtpNumberRejectedError);

    api.mutate.mockRejectedValueOnce(apiError(400, 'INVALID_REQUEST'));
    await expect(otp.requestCode('+998901234567')).rejects.toBeInstanceOf(OtpNumberRejectedError);
  });

  it('any 5xx -> OtpUndeliverableError, carrying the transport reason', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(
      apiError(500, 'INTERNAL_ERROR', { reason: 'SMS_RECEIVER_BLACKLISTED' }),
    );

    const failure = await otp.requestCode('+998901234567').catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(OtpUndeliverableError);
    expect((failure as OtpUndeliverableError).reason).toBe('SMS_RECEIVER_BLACKLISTED');
    expect((failure as OtpUndeliverableError).permanent).toBe(true);
  });

  it('a 5xx with an unrelated (or absent) reason is not permanent', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(502, 'INTERNAL_ERROR'));

    const failure = await otp.requestCode('+998901234567').catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(OtpUndeliverableError);
    expect((failure as OtpUndeliverableError).permanent).toBe(false);
  });

  it('an unrelated failure (e.g. a 409 conflict) is passed through untranslated', async () => {
    const { otp, api } = setUp();
    const original = apiError(409, 'RESOURCE_CONFLICT');
    api.mutate.mockRejectedValue(original);

    const failure = await otp.requestCode('+998901234567').catch((e: unknown) => e);

    expect(failure).toBe(original);
  });

  it('a failure that is not a HorecaOSApiError at all is passed through untranslated', async () => {
    const { otp, api } = setUp();
    const original = new Error('transport exploded');
    api.mutate.mockRejectedValue(original);

    await expect(otp.requestCode('+998901234567')).rejects.toBe(original);
  });
});

describe('CustomerOtp.submitCode error taxonomy', () => {
  it('404 -> CustomerSignInUnavailableError', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));

    await expect(
      otp.submitCode({ challengeId: 'c-1', code: '123456' }),
    ).rejects.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('RATE_LIMIT_EXCEEDED -> OtpRateLimitedError', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(429, 'RATE_LIMIT_EXCEEDED'));

    await expect(
      otp.submitCode({ challengeId: 'c-1', code: '123456' }),
    ).rejects.toBeInstanceOf(OtpRateLimitedError);
  });

  it('UNPROCESSABLE_STATE -> OtpChallengeOverError (expired, superseded, exhausted, or spent -- all one answer)', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(422, 'UNPROCESSABLE_STATE'));

    await expect(
      otp.submitCode({ challengeId: 'c-1', code: '123456' }),
    ).rejects.toBeInstanceOf(OtpChallengeOverError);
  });

  it('401 / UNAUTHENTICATED -> OtpCodeRejectedError, carrying attemptsRemaining from the problem extension', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(401, 'UNAUTHENTICATED', { attemptsRemaining: 2 }));

    const failure = await otp.submitCode({ challengeId: 'c-1', code: '000000' }).catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(OtpCodeRejectedError);
    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBe(2);
  });

  it('an absent attemptsRemaining reads as null, not zero', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(401, 'UNAUTHENTICATED'));

    const failure = await otp.submitCode({ challengeId: 'c-1', code: '000000' }).catch((e: unknown) => e);

    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBeNull();
  });

  it('VALIDATION_FAILED (wrong code length) -> OtpCodeRejectedError with null attemptsRemaining (costs no attempt)', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(400, 'VALIDATION_FAILED'));

    const failure = await otp.submitCode({ challengeId: 'c-1', code: '1' }).catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(OtpCodeRejectedError);
    expect((failure as OtpCodeRejectedError).attemptsRemaining).toBeNull();
  });

  it('an unrelated failure is passed through untranslated', async () => {
    const { otp, api } = setUp();
    const original = apiError(500, 'INTERNAL_ERROR');
    api.mutate.mockRejectedValue(original);

    const failure = await otp.submitCode({ challengeId: 'c-1', code: '123456' }).catch((e: unknown) => e);

    expect(failure).toBe(original);
  });
});

describe('CustomerOtp.signIn error taxonomy and session install', () => {
  it('404 -> CustomerSignInUnavailableError', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));

    await expect(otp.signIn('grant-1')).rejects.toBeInstanceOf(CustomerSignInUnavailableError);
  });

  it('401 / UNAUTHENTICATED -> OtpChallengeOverError (the grant is spent, expired, or for another brand)', async () => {
    const { otp, api } = setUp();
    api.mutate.mockRejectedValue(apiError(401, 'UNAUTHENTICATED'));

    await expect(otp.signIn('grant-1')).rejects.toBeInstanceOf(OtpChallengeOverError);
  });

  it('an unrelated failure is passed through untranslated', async () => {
    const { otp, api } = setUp();
    const original = apiError(500, 'INTERNAL_ERROR');
    api.mutate.mockRejectedValue(original);

    await expect(otp.signIn('grant-1')).rejects.toBe(original);
  });

  it('a 200 with no token throws rather than adopting undefined', async () => {
    const { otp, api } = setUp();
    api.mutate.mockResolvedValue({ accountId: 'acc-1', created: false });

    await expect(otp.signIn('grant-1')).rejects.toThrow(/without a session token/);
  });

  it('installs the session via Session.adopt before resolving, and reports created/accountId', async () => {
    const { otp, api } = setUp();
    const session = TestBed.inject(Session);
    const expiresAt = new Date(Date.now() + 60_000).toISOString();
    api.mutate.mockResolvedValue({ token: 'qcs1.abc', expiresAt, accountId: 'acc-9', created: true });

    const signedIn = await otp.signIn('grant-1');

    expect(session.accessToken()).toBe('qcs1.abc');
    expect(signedIn).toEqual({ created: true, accountId: 'acc-9' });
  });

  it('reports created: false and a null accountId honestly when the platform omits accountId', async () => {
    const { otp, api } = setUp();
    api.mutate.mockResolvedValue({
      token: 'qcs1.def',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      created: false,
    });

    const signedIn = await otp.signIn('grant-1');

    expect(signedIn).toEqual({ created: false, accountId: null });
  });
});

describe('CustomerOtp.signOut', () => {
  it('clears the local session even when the platform call fails', async () => {
    const { otp, api } = setUp();
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live', expiresAt: new Date(Date.now() + 60_000).toISOString() });
    api.mutate.mockRejectedValue(apiError(500, 'INTERNAL_ERROR'));

    await otp.signOut();

    expect(session.accessToken()).toBeNull();
  });

  it('clears the local session on a successful platform call', async () => {
    const { otp, api } = setUp();
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live', expiresAt: new Date(Date.now() + 60_000).toISOString() });
    api.mutate.mockResolvedValue(undefined);

    await otp.signOut();

    expect(session.accessToken()).toBeNull();
  });
});
