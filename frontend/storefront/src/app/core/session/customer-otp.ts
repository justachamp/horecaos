import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import { QoidaApiError } from '../api/problem-details';
import { Session } from '../auth/session';

/**
 * Phone-and-SMS-code sign-in for a customer.
 *
 * ## What the platform actually has
 *
 * `StorefrontCustomerIdentityController` is real and is what this client calls.
 * Three endpoints, under
 * `/storefront/tenants/{tenantId}/brands/{brandId}/identity`:
 *
 * ```
 * POST /verification-challenges                      { phone }
 *   -> 202 { challengeId, expiresAt, attemptsAllowed, codeLength }
 *   -> 429 RATE_LIMIT_EXCEEDED, with Retry-After
 *   -> 400 VALIDATION_FAILED    the number is not a deliverable Uzbek mobile
 *   -> 500 INTERNAL_ERROR       with `reason`: the message was not sent
 *
 * POST /verification-challenges/{challengeId}/attempts   { code }
 *   -> 200 { grant, expiresAt }
 *   -> 401 UNAUTHENTICATED      wrong code; carries `attemptsRemaining`
 *   -> 422 UNPROCESSABLE_STATE  unknown, expired, superseded, exhausted or spent
 *   -> 429 RATE_LIMIT_EXCEEDED
 *
 * POST /sessions                                      { grant }
 *   -> 201 { token, expiresAt, accountId, created: true }   a number new here
 *   -> 200 { token, expiresAt, accountId, created: false }  a number already known
 *   -> 401 UNAUTHENTICATED      the grant is spent, expired, or for another brand
 *
 * DELETE /sessions/current                                        (needs a token)
 *   -> 204
 * ```
 *
 * The grant is deliberately **not a session**: it is single-use proof that the
 * bearer controls that number, for that brand, at that moment. `POST /sessions`
 * is what redeems it, and it does the whole of sign-in in one transaction —
 * spends the grant, finds or creates the account the proven number belongs to,
 * and mints the bearer (ADR 0051).
 *
 * ## What the response actually looks like
 *
 * `{ token, expiresAt, accountId, created }`. Not `{ accessToken,
 * expiresInSeconds }`, which is what this file assumed while the endpoint was
 * being written in another session, and which would have made every sign-in
 * throw "the session exchange answered without an access token" against a server
 * that had just minted a perfectly good one.
 *
 * **Do not read this shape out of `api/generated/qoida-api-v1.ts`.** OpenAPI
 * schema names are a flat namespace on the simple class name, and until the
 * platform record was renamed to `CustomerSessionResponse` this operation shared
 * a schema with the dine-in table session — so the generated client described
 * `signIn` as returning `partySize` and `settledTotalMinor`. The controller is
 * the contract.
 *
 * `token` is opaque and is **not a JWT**: 256 bits of CSPRNG behind a fixed
 * `qcs1.` prefix, stored on the platform only as a SHA-256 digest. Nothing here
 * decodes it, and it never goes near `angular-oauth2-oidc`.
 *
 * `/registrations` is not on this path. It needs a realm token and exists for a
 * caller who already holds one — an operator ordering on somebody's behalf. A
 * storefront customer has no such token, and calling it after `POST /sessions`
 * would present an already-spent grant.
 *
 * ## Personal data
 *
 * The phone number travels in a request body and never in a path or a query
 * (ADR 0029): a URL is written to every access log, every reverse proxy and
 * every `Referer` the page emits afterwards. Nothing here returns the number,
 * puts it in an error, or logs it. Neither the code nor the grant is stored.
 */
@Injectable({ providedIn: 'root' })
export class CustomerOtp {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly session = inject(Session);

  private get identityPath(): string {
    return (
      `/storefront/tenants/${this.config.tenantId}` + `/brands/${this.config.brandId}/identity`
    );
  }

  /**
   * Asks for a code to be sent.
   *
   * @param phoneE164 the canonical `+998…` form. Formatting for display happens
   *        in the screen; what crosses the wire is one shape.
   * @param idempotencyKey pass the key from the first attempt when the customer
   *        presses "send again" for the *same* intent, so the platform replays
   *        rather than sending a second message. A genuine resend after the
   *        window is a new intent and takes a new key.
   */
  async requestCode(phoneE164: string, idempotencyKey?: string): Promise<OtpChallenge> {
    try {
      return await this.api.mutate<OtpChallenge>(
        'POST',
        `${this.identityPath}/verification-challenges`,
        { body: { phone: phoneE164 }, idempotencyKey },
      );
    } catch (failure) {
      throw translateIssue(failure);
    }
  }

  /**
   * Spends one attempt against a challenge.
   *
   * @returns proof of the number. Not a session — see {@link signIn}.
   */
  async submitCode(input: { challengeId: string; code: string }): Promise<VerificationGrant> {
    try {
      return await this.api.mutate<VerificationGrant>(
        'POST',
        `${this.identityPath}/verification-challenges/${input.challengeId}/attempts`,
        { body: { code: input.code } },
      );
    } catch (failure) {
      throw translateAttempt(failure);
    }
  }

  /**
   * Turns a grant into a signed-in application.
   *
   * The token is installed through {@link Session.adopt} before this returns, so
   * a caller cannot end up holding a session the rest of the application does
   * not know about. That was the exact gap the previous version of this file
   * documented and refused to paper over.
   */
  async signIn(grant: string): Promise<SignedIn> {
    let minted: CustomerSessionResponse;
    try {
      minted = await this.api.mutate<CustomerSessionResponse>(
        'POST',
        `${this.identityPath}${SESSION_PATH}`,
        { body: { grant } },
      );
    } catch (failure) {
      throw translateExchange(failure);
    }

    if (!minted?.token) {
      // A 200 with no token is not a session. Saying so beats adopting
      // `undefined` and letting every later call answer 401.
      throw new CustomerSignInUnavailableError(
        'The session exchange answered without a session token.',
      );
    }
    // `expiresAt` and not a duration: the platform reports the deadline its
    // session row carries, and `Session.adopt` takes it as given.
    this.session.adopt({ accessToken: minted.token, expiresAt: minted.expiresAt });

    return { created: minted.created === true, accountId: minted.accountId ?? null };
  }

  /**
   * Ends the session on the platform as well as in this tab.
   *
   * Dropping the token locally is not signing out. The session row survives for
   * thirty days and the token is a bearer: anything that saw it — a proxy log, a
   * shared handset's process memory, a screenshot of a devtools tab — can still
   * spend it. `DELETE /sessions/current` names the session by the header rather
   * than by an id in the path, so there is nothing to edit in order to end
   * somebody else's, and tapping it twice is not an error.
   *
   * The local half runs whatever the platform answered. A customer who pressed
   * "sign out" and got a network error must not still be signed in on the
   * screen in front of them, and a token this tab has thrown away is one it
   * cannot present again regardless.
   */
  async signOut(): Promise<void> {
    try {
      await this.api.mutate<void>('DELETE', `${this.identityPath}${SESSION_PATH}/current`);
    } catch {
      // Already expired, already revoked, or unreachable. All three end the
      // same way here.
    } finally {
      this.session.signOut();
    }
  }
}

/** Relative to the identity path above. */
export const SESSION_PATH = '/sessions';

/** Everything the platform will tell a caller about a challenge. Never the code. */
export interface OtpChallenge {
  readonly challengeId: string;
  /** ISO-8601 instant. The window is short — minutes, not hours. */
  readonly expiresAt: string;
  readonly attemptsAllowed: number;
  readonly codeLength: number;
}

/** Returned once. The secret exists in that response and nowhere else. */
export interface VerificationGrant {
  readonly grant: string;
  readonly expiresAt: string;
}

/**
 * `StorefrontCustomerIdentityController.CustomerSessionResponse`, verbatim.
 *
 * The token is returned once and exists in that response and nowhere else. It is
 * never logged, never stored, and never put in a URL.
 */
interface CustomerSessionResponse {
  readonly token: string;
  /** ISO-8601 instant. Thirty days out, at the time of writing. */
  readonly expiresAt: string;
  readonly accountId: string;
  /** True when this sign-in brought the account into existence. */
  readonly created: boolean;
}

/** How the sign-in ended, for a screen that wants to know if this is somebody new. */
export interface SignedIn {
  /** True when this number had no account at this brand until now. */
  readonly created: boolean;
  readonly accountId: string | null;
}

/**
 * Sign-in cannot be attempted at all here, and pressing the button again does
 * not change that. A 404 means "no such endpoint", not "no such customer".
 */
export class CustomerSignInUnavailableError extends Error {
  constructor(
    detail?: string,
    override readonly cause?: unknown,
  ) {
    super(detail ?? 'The platform did not answer at the customer identity endpoints.');
    this.name = 'CustomerSignInUnavailableError';
  }
}

/** The code was wrong. Retrying the *code* helps, and there are this many tries left. */
export class OtpCodeRejectedError extends Error {
  constructor(
    readonly attemptsRemaining: number | null,
    override readonly cause?: unknown,
  ) {
    super('The verification code was not accepted.');
    this.name = 'OtpCodeRejectedError';
  }
}

/**
 * The challenge is over: expired, superseded, out of attempts, already used, or
 * never known. The platform answers all five identically and this client does
 * not invent a distinction it was not given. The only way forward is a new code.
 */
export class OtpChallengeOverError extends Error {
  constructor(override readonly cause?: unknown) {
    super('This verification has ended.');
    this.name = 'OtpChallengeOverError';
  }
}

/** Too many requests from this caller. Waiting helps; pressing again does not. */
export class OtpRateLimitedError extends Error {
  constructor(
    readonly retryAfterSeconds: number | null,
    override readonly cause?: unknown,
  ) {
    super('Too many requests.');
    this.name = 'OtpRateLimitedError';
  }
}

/** The number is not one an Uzbek network can deliver an SMS to. */
export class OtpNumberRejectedError extends Error {
  constructor(override readonly cause?: unknown) {
    super('That number cannot receive a code.');
    this.name = 'OtpNumberRejectedError';
  }
}

/**
 * The challenge was made and the message did not leave the building.
 *
 * The platform withdraws the challenge when this happens, so there is nothing to
 * enter and nothing to resume. Two of the reasons matter to a customer and they
 * are opposites:
 *
 * - `SMS_RECEIVER_BLACKLISTED` — the gateway will never deliver to this number,
 *   because the person behind it opted out at their operator. {@link permanent}
 *   is true. Telling them to try again is a lie: the same number cannot ever
 *   receive this code, and the only real remedy is another number.
 * - anything else — our side, transiently: no transport configured, a gateway
 *   that refused, a network that did not answer. Trying again can work.
 */
export class OtpUndeliverableError extends Error {
  constructor(
    readonly reason: string | null,
    override readonly cause?: unknown,
  ) {
    super('The code could not be sent.');
    this.name = 'OtpUndeliverableError';
  }

  /** True when no retry, at any point in the future, can reach this number. */
  get permanent(): boolean {
    return this.reason === BLACKLISTED;
  }
}

/**
 * The gateway's own word for a number that has opted out of receiving messages.
 *
 * `SmsGateCode.RECEIVER_IN_BLACKLIST` maps to this string on the platform side
 * and it arrives as the `reason` extension of an ADR 0031 problem document.
 */
const BLACKLISTED = 'SMS_RECEIVER_BLACKLISTED';

function translateIssue(failure: unknown): unknown {
  if (!(failure instanceof QoidaApiError)) {
    return failure;
  }
  if (failure.status === 404) {
    return new CustomerSignInUnavailableError(undefined, failure);
  }
  if (failure.code === 'RATE_LIMIT_EXCEEDED') {
    return new OtpRateLimitedError(failure.retryAfterSeconds ?? null, failure);
  }
  if (failure.code === 'VALIDATION_FAILED' || failure.code === 'INVALID_REQUEST') {
    return new OtpNumberRejectedError(failure);
  }
  if (failure.status >= 500) {
    // `reason` is the transport's own code, carried as an ADR 0031 extension.
    // It is the difference between "wait a minute" and "this number will never
    // receive it", and both of those are said to the customer in one line.
    return new OtpUndeliverableError(failure.problem?.reason ?? null, failure);
  }
  return failure;
}

function translateAttempt(failure: unknown): unknown {
  if (!(failure instanceof QoidaApiError)) {
    return failure;
  }
  if (failure.status === 404) {
    return new CustomerSignInUnavailableError(undefined, failure);
  }
  if (failure.code === 'RATE_LIMIT_EXCEEDED') {
    return new OtpRateLimitedError(failure.retryAfterSeconds ?? null, failure);
  }
  if (failure.code === 'UNPROCESSABLE_STATE') {
    return new OtpChallengeOverError(failure);
  }
  if (failure.status === 401 || failure.code === 'UNAUTHENTICATED') {
    return new OtpCodeRejectedError(attemptsRemaining(failure), failure);
  }
  if (failure.code === 'VALIDATION_FAILED') {
    // A code of the wrong length costs no attempt, so this is the same fact the
    // customer sees for a wrong code minus the count.
    return new OtpCodeRejectedError(null, failure);
  }
  return failure;
}

function translateExchange(failure: unknown): unknown {
  if (!(failure instanceof QoidaApiError)) {
    return failure;
  }
  if (failure.status === 404) {
    return new CustomerSignInUnavailableError(undefined, failure);
  }
  if (failure.status === 401 || failure.code === 'UNAUTHENTICATED') {
    // The grant is spent, expired or for another brand. Nothing is left of this
    // verification, so the customer starts from the number again.
    return new OtpChallengeOverError(failure);
  }
  return failure;
}

/** The platform sends how many guesses are left. Absent is not zero. */
function attemptsRemaining(failure: QoidaApiError): number | null {
  const value = (failure.problem as { attemptsRemaining?: unknown } | undefined)?.attemptsRemaining;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
