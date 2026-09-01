import { Injectable, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { ApiClient } from './api-client';

/**
 * A customer's own Telegram link to the brand's bot (ADR 0058 stage 2, wave 7).
 *
 * A faithful transcription of `StorefrontTelegramLinkController`, built directly
 * on {@link ApiClient} -- the same hand-mirrored convention `CustomerApi` and
 * `CustomerOtp` use rather than the generated
 * `api/generated/horecaos-api-v1.storefront.ts` client. `CustomerOtp`'s own doc
 * comment explains why: OpenAPI schema names are a flat namespace on the simple
 * class name, and this app has already been burned once by a generated shape
 * that quietly meant something else because two operations shared a class name.
 * The controller is the contract.
 *
 * Every method here addresses `/telegram`, under the same
 * `/storefront/tenants/{tenantId}/brands/{brandId}` root `CustomerApi` uses, and
 * like that surface **the account is never in a path**: the server resolves it
 * from the caller's own verified session, and a guest -- signed in, but with no
 * account at this brand yet -- is answered not-found rather than forbidden, for
 * the same reason `CustomerApi`'s own doc comment gives.
 *
 * `code` and `deepLink` are declared below as always present, though the
 * generated client marks them optional (`code?: string`) the way every OpenAPI
 * field defaults unless the schema says otherwise: the controller's
 * `CustomerTelegramLinkCodeResponse` record has no nullable field, and a 200 from
 * `issueCode` always carries both. Nothing here reads or renders an expiry:
 * `POST /link-codes` never returns one -- the server-side TTL is a `Duration`
 * property (`PT15M` by default, `TelegramCustomerLinkService`) that no response
 * field exposes -- so a screen built on this client cannot show a countdown
 * without inventing a number the platform never sent.
 */
@Injectable({ providedIn: 'root' })
export class TelegramLinkApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get telegramPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}/telegram`;
  }

  /**
   * Mints a fresh `https://t.me/<bot>?start=<code>` deep link.
   *
   * Single-use and short-lived on the platform, and safe to call again: a new
   * code coexists with any still-outstanding one rather than replacing it, so
   * asking for a second link after the first seems stuck costs nothing.
   */
  issueCode(idempotencyKey?: string): Promise<TelegramLinkCode> {
    return this.api.mutate<TelegramLinkCode>('POST', `${this.telegramPath}/link-codes`, {
      idempotencyKey,
    });
  }

  /** Whether this account currently has a linked Telegram chat. */
  status(): Promise<TelegramLinkStatus> {
    return this.api.get<TelegramLinkStatus>(`${this.telegramPath}/link`);
  }

  /**
   * Retires the link. 204, no body -- and always succeeds: unlinking what was
   * never linked is the state this call asked for, so the controller never
   * answers it with a failure.
   */
  async unlink(idempotencyKey?: string): Promise<void> {
    await this.api.mutate<void>('DELETE', `${this.telegramPath}/link`, { idempotencyKey });
  }
}

/** `CustomerTelegramLinkCodeResponse`, from `StorefrontTelegramLinkController.issueCode`. */
export interface TelegramLinkCode {
  readonly code: string;
  readonly deepLink: string;
}

/** `CustomerTelegramLinkStatusResponse`, from `StorefrontTelegramLinkController`. */
export interface TelegramLinkStatus {
  readonly linked: boolean;
}
