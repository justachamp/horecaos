import { Injectable, inject } from '@angular/core';

import { TelegramSignInApi, type TelegramSignInCode, type TelegramSignInPollResult } from '../api/telegram-signin-api';
import { HorecaOSApiError } from '../api/problem-details';
import { newIdempotencyKey } from '../api/idempotency';
import { Session } from '../auth/session';

/**
 * "Continue with Telegram" (ADR 0063): mints the deep-link code, and polls it
 * into a session exactly the way {@link CustomerOtp.signIn} turns a grant into
 * one — the token is installed through {@link Session.adopt} before
 * {@link pollOnce} returns `true`, so a caller can never end up holding a
 * session the rest of the application does not know about.
 *
 * No polling loop lives here, the same choice `TelegramLinkService` (the wave-7
 * "Connect Telegram" client this mirrors) makes about its own screen: the
 * caller owns the `Subscription`, using `OrdersService.poll`'s idiom -- skip
 * while `document.hidden`, swallow a failed tick.
 */
@Injectable({ providedIn: 'root' })
export class TelegramSignIn {
  private readonly api = inject(TelegramSignInApi);
  private readonly session = inject(Session);

  /** Mints a fresh deep link. Safe to call again if the customer says a first attempt seems stuck. */
  async mintCode(): Promise<TelegramSignInCode> {
    try {
      return await this.api.issueCode(newIdempotencyKey());
    } catch (failure) {
      throw translateMint(failure);
    }
  }

  /**
   * One poll tick.
   *
   * @returns true once the bot's handshake has redeemed the code and this call
   *          has adopted the session -- the caller's polling loop should stop.
   *          false means PENDING: keep polling.
   */
  async pollOnce(code: string): Promise<boolean> {
    let result: TelegramSignInPollResult;
    try {
      result = await this.api.poll(code);
    } catch (failure) {
      throw translatePoll(failure);
    }

    switch (result.status) {
      case 'PENDING':
        return false;
      case 'SIGNED_IN': {
        if (!result.token) {
          // A 200 with no token is not a session -- CustomerOtp.signIn makes
          // the identical refusal for the identical reason.
          throw new TelegramSignInUnavailableError('The poll answered SIGNED_IN without a session token.');
        }
        this.session.adopt({ accessToken: result.token, expiresAt: result.expiresAt });
        return true;
      }
      case 'EXPIRED':
      case 'ALREADY_CLAIMED':
        // To the customer these are the same remedy: the code is spent, and
        // the only way forward is a fresh one. ALREADY_CLAIMED is the rare
        // race a second concurrent poll loses, not something worth a
        // different message for.
        throw new TelegramSignInExpiredError();
    }
  }
}

/** Sign-in cannot be attempted at all here, and pressing the button again does not change that. */
export class TelegramSignInUnavailableError extends Error {
  constructor(
    detail?: string,
    override readonly cause?: unknown,
  ) {
    super(detail ?? 'The platform did not answer at the Telegram sign-in endpoints.');
    this.name = 'TelegramSignInUnavailableError';
  }
}

/** The code is spent -- expired, or already claimed by an earlier poll. Minting a new one is the only way forward. */
export class TelegramSignInExpiredError extends Error {
  constructor(override readonly cause?: unknown) {
    super('This Telegram sign-in link has expired.');
    this.name = 'TelegramSignInExpiredError';
  }
}

/** Too many codes requested from this caller. Waiting helps; pressing again does not. */
export class TelegramSignInRateLimitedError extends Error {
  constructor(
    readonly retryAfterSeconds: number | null,
    override readonly cause?: unknown,
  ) {
    super('Too many requests.');
    this.name = 'TelegramSignInRateLimitedError';
  }
}

function translateMint(failure: unknown): unknown {
  if (!(failure instanceof HorecaOSApiError)) {
    return failure;
  }
  if (failure.status === 404) {
    return new TelegramSignInUnavailableError(undefined, failure);
  }
  if (failure.code === 'RATE_LIMIT_EXCEEDED') {
    return new TelegramSignInRateLimitedError(failure.retryAfterSeconds ?? null, failure);
  }
  return failure;
}

function translatePoll(failure: unknown): unknown {
  if (!(failure instanceof HorecaOSApiError)) {
    return failure;
  }
  if (failure.status === 404) {
    // Either the storefront never reached this endpoint at all, or the code
    // is one this platform never issued (RESOURCE_NOT_FOUND) -- indistinguishable
    // to a customer, and both mean "start again".
    return new TelegramSignInExpiredError(failure);
  }
  return failure;
}
