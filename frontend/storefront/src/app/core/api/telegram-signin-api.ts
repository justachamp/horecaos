import { Injectable, inject } from '@angular/core';

import { ApiClient } from './api-client';
import { APP_CONFIG } from '../config/app-config';

/**
 * "Continue with Telegram" (ADR 0063) — a faithful transcription of
 * `StorefrontTelegramSignInController`, built directly on {@link ApiClient} the
 * same hand-mirrored way {@link TelegramLinkApi} and `CustomerOtp` are rather
 * than the generated `api/generated/horecaos-api-v1.storefront.ts` client.
 * `CustomerOtp`'s own doc comment explains why: OpenAPI schema names are a flat
 * namespace on the simple class name, and this app has already been burned once
 * by a generated shape that quietly meant something else because two operations
 * shared a class name. The controller is the contract.
 *
 * Unlike {@link TelegramLinkApi}'s own endpoints, both of these are
 * unauthenticated — a customer with no account yet has no session to send, the
 * same reason `CustomerOtp`'s three identity calls carry none either.
 */
@Injectable({ providedIn: 'root' })
export class TelegramSignInApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get telegramPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/telegram`;
  }

  /** Mints a fresh `https://t.me/<bot>?start=auth_<code>` deep link. */
  issueCode(idempotencyKey?: string): Promise<TelegramSignInCode> {
    return this.api.mutate<TelegramSignInCode>('POST', `${this.telegramPath}/sign-in-codes`, {
      idempotencyKey,
    });
  }

  /**
   * One poll. `PENDING` until the bot's request_contact handshake redeems the
   * code; `SIGNED_IN` carries the session, once, in the same response —
   * `token` is present if and only if `status === 'SIGNED_IN'`.
   */
  poll(code: string): Promise<TelegramSignInPollResult> {
    return this.api.get<TelegramSignInPollResult>(`${this.telegramPath}/sign-in-codes/${encodeURIComponent(code)}`);
  }
}

/** `StorefrontTelegramSignInController.SignInCodeResponse`. */
export interface TelegramSignInCode {
  readonly code: string;
  readonly deepLink: string;
}

/** `StorefrontTelegramSignInController.SignInPollResponse`, verbatim. */
export interface TelegramSignInPollResult {
  readonly status: 'PENDING' | 'EXPIRED' | 'ALREADY_CLAIMED' | 'SIGNED_IN';
  readonly token?: string | null;
  readonly expiresAt?: string | null;
  readonly accountId?: string | null;
  readonly accountCreated?: boolean | null;
}
