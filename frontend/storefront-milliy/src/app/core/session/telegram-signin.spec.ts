import { TestBed } from '@angular/core/testing';

import {
  TelegramSignIn,
  TelegramSignInExpiredError,
  TelegramSignInRateLimitedError,
  TelegramSignInUnavailableError,
} from './telegram-signin';
import { TelegramSignInApi, type TelegramSignInPollResult } from '../api/telegram-signin-api';
import { HorecaOSApiError, type ErrorCode } from '../api/problem-details';
import { Session } from '../auth/session';

/**
 * {@link TelegramSignIn}'s own translation branches (`translateMint`,
 * `translatePoll`) and its {@link Session.adopt} discipline -- the ADR 0063
 * counterpart to `customer-otp.spec.ts`'s own suite, stubbing
 * {@link TelegramSignInApi} directly rather than `ApiClient` for the same
 * reason: the HTTP-to-`HorecaOSApiError` translation is `problemDetailsInterceptor`'s
 * job and is covered elsewhere.
 */
class FakeTelegramSignInApi {
  issueCode = vi.fn();
  poll = vi.fn();
}

function apiError(status: number, code: ErrorCode, retryAfterSeconds?: number): HorecaOSApiError {
  return new HorecaOSApiError({ status, code, detail: 'x', problem: { status, code }, retryAfterSeconds });
}

function setUp(): { signIn: TelegramSignIn; api: FakeTelegramSignInApi } {
  // Session persists to localStorage, which jsdom does not reset between
  // tests in this file on its own -- Session.spec.ts's own beforeEach makes
  // the identical call for the identical reason.
  localStorage.clear();
  const api = new FakeTelegramSignInApi();
  TestBed.configureTestingModule({
    providers: [{ provide: TelegramSignInApi, useValue: api }],
  });
  return { signIn: TestBed.inject(TelegramSignIn), api };
}

describe('TelegramSignIn.mintCode error taxonomy', () => {
  it('404 -> TelegramSignInUnavailableError', async () => {
    const { signIn, api } = setUp();
    api.issueCode.mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));

    await expect(signIn.mintCode()).rejects.toBeInstanceOf(TelegramSignInUnavailableError);
  });

  it('RATE_LIMIT_EXCEEDED -> TelegramSignInRateLimitedError, carrying retryAfterSeconds', async () => {
    const { signIn, api } = setUp();
    api.issueCode.mockRejectedValue(apiError(429, 'RATE_LIMIT_EXCEEDED', 45));

    const failure = await signIn.mintCode().catch((e) => e);

    expect(failure).toBeInstanceOf(TelegramSignInRateLimitedError);
    expect((failure as TelegramSignInRateLimitedError).retryAfterSeconds).toBe(45);
  });

  it('an unrecognised failure is rethrown as-is', async () => {
    const { signIn, api } = setUp();
    const original = new Error('network gone');
    api.issueCode.mockRejectedValue(original);

    await expect(signIn.mintCode()).rejects.toBe(original);
  });

  it('on success, returns the code untouched', async () => {
    const { signIn, api } = setUp();
    const code = { code: 'abc123', deepLink: 'https://t.me/bot?start=auth_abc123' };
    api.issueCode.mockResolvedValue(code);

    await expect(signIn.mintCode()).resolves.toEqual(code);
  });
});

describe('TelegramSignIn.pollOnce', () => {
  it('PENDING resolves to false and installs no session', async () => {
    const { signIn, api } = setUp();
    const session = TestBed.inject(Session);
    api.poll.mockResolvedValue({ status: 'PENDING' } satisfies TelegramSignInPollResult);

    await expect(signIn.pollOnce('abc123')).resolves.toBe(false);
    expect(session.isAuthenticated()).toBe(false);
  });

  it('SIGNED_IN installs the session via Session.adopt before resolving true', async () => {
    const { signIn, api } = setUp();
    const session = TestBed.inject(Session);
    api.poll.mockResolvedValue({
      status: 'SIGNED_IN',
      token: 'qcs1.live-token',
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      accountId: 'acc-1',
      accountCreated: true,
    } satisfies TelegramSignInPollResult);

    await expect(signIn.pollOnce('abc123')).resolves.toBe(true);

    expect(session.isAuthenticated()).toBe(true);
    expect(session.accessToken()).toBe('qcs1.live-token');
  });

  it('SIGNED_IN with no token is a platform bug, refused rather than adopted', async () => {
    const { signIn, api } = setUp();
    const session = TestBed.inject(Session);
    api.poll.mockResolvedValue({ status: 'SIGNED_IN' } satisfies TelegramSignInPollResult);

    await expect(signIn.pollOnce('abc123')).rejects.toBeInstanceOf(TelegramSignInUnavailableError);
    expect(session.isAuthenticated()).toBe(false);
  });

  it.each(['EXPIRED', 'ALREADY_CLAIMED'] as const)('%s -> TelegramSignInExpiredError', async (status) => {
    const { signIn, api } = setUp();
    api.poll.mockResolvedValue({ status } satisfies TelegramSignInPollResult);

    await expect(signIn.pollOnce('abc123')).rejects.toBeInstanceOf(TelegramSignInExpiredError);
  });

  it('a 404 from the poll call itself is also TelegramSignInExpiredError', async () => {
    const { signIn, api } = setUp();
    api.poll.mockRejectedValue(apiError(404, 'RESOURCE_NOT_FOUND'));

    await expect(signIn.pollOnce('abc123')).rejects.toBeInstanceOf(TelegramSignInExpiredError);
  });

  it('a transient failure is rethrown as-is, so the caller can retry the next tick', async () => {
    const { signIn, api } = setUp();
    const original = new Error('network gone');
    api.poll.mockRejectedValue(original);

    await expect(signIn.pollOnce('abc123')).rejects.toBe(original);
  });
});
